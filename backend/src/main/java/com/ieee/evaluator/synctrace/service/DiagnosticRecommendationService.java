package com.ieee.evaluator.synctrace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieee.evaluator.service.AiProvider;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.DiagnosticRecommendation;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.DiagnosticRecommendationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DiagnosticRecommendationService {

    private static final int DEFAULT_ANALYZE_MAX_ATTEMPTS = 8;
    private static final long DEFAULT_RETRY_INITIAL_DELAY_MIN_MS = 2_000;
    private static final long DEFAULT_RETRY_INITIAL_DELAY_MAX_MS = 5_000;
    private static final long DEFAULT_RETRY_BACKOFF_MAX_DELAY_MS = 30_000;
    private static final long DEFAULT_RETRY_TIME_LIMIT_MS = 180_000;

    /** What a complete trace looks like, so the model can name the exact artifacts to add. */
    private static final String TRACEABILITY_CONTEXT = """
        HOW TRACEABILITY WORKS IN SYNCTRACE:
        - Each SMART goal from the Proposal is traced through SRS (requirements), SDD (design), \
        SPMP (project plan), STD (testing) and IMPLEMENTATION (source code).
        - Every goal needs SRS use case, activity diagram and wireframe components.
        - Every use case needs its own matching class diagram and sequence diagram in the SDD, \
        and its own test case in the STD.
        - Every wireframe needs a matching UI design in the SDD.
        - Every class diagram needs matching source code files.
        - Components "match" when they describe the same feature, so names should reuse the same key words.
        - Teams link components to goals on the Traceability Mapping page, connect code on the Source Code page, \
        and see results on the Traceability Results page, where they can re-run the analysis.

        """;

    private final ContinuityFindingRepository findingRepository;
    private final DiagnosticRecommendationRepository recommendationRepository;
    private final ProgressEmitter progressEmitter;
    private final Map<String, AiProvider> providers;
    private final Set<String> inFlightAnalyses = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public DiagnosticRecommendationService(
            ContinuityFindingRepository findingRepository,
            DiagnosticRecommendationRepository recommendationRepository,
            ProgressEmitter progressEmitter,
            List<AiProvider> providerList) {
        this.findingRepository = findingRepository;
        this.recommendationRepository = recommendationRepository;
        this.progressEmitter = progressEmitter;
        this.providers = providerList.stream()
                .collect(Collectors.toMap(
                    p -> p.getProviderName().toLowerCase(),
                    Function.identity()
                ));
        this.objectMapper = new ObjectMapper();
    }

    public List<DiagnosticRecommendation> generateRecommendations(
            String rawTeamCode, String aiModel, String sessionId) throws Exception {
        String teamCode = TeamCodeResolver.normalize(rawTeamCode);

        AiProvider provider;
        try {
            provider = resolveProvider(aiModel);
        } catch (Exception e) {
            progressEmitter.error(sessionId, e.getMessage());
            throw e;
        }

        String runKey = buildRunKey(teamCode, provider.getProviderName());

        if (!inFlightAnalyses.add(runKey)) {
            IllegalStateException busy = new IllegalStateException(
                "An analysis is already in progress for this team and provider. Please wait for it to finish.");
            progressEmitter.error(sessionId, busy.getMessage());
            throw busy;
        }

        emit(sessionId, "RECEIVED", "Request accepted — starting diagnostic analysis", 5);

        try {
            List<DiagnosticRecommendation> recommendations = generateWithRetry(teamCode, provider, sessionId);

            emit(sessionId, "COMPLETE", "Diagnostic analysis complete", 100);
            progressEmitter.complete(sessionId);

            return recommendations;
        } catch (Exception e) {
            progressEmitter.error(sessionId, e.getMessage());
            throw e;
        } finally {
            inFlightAnalyses.remove(runKey);
        }
    }

    @Transactional(readOnly = true)
    public List<DiagnosticRecommendation> getRecommendationsForTeam(String teamCode) {
        if (teamCode == null || teamCode.isBlank()) {
            return List.of();
        }

        List<Long> findingIds = findingRepository.findByTeamCodeIgnoreCaseOrderByDetectedAtDescIdAsc(teamCode.trim()).stream()
            .map(ContinuityFinding::getId)
            .filter(Objects::nonNull)
            .toList();

        if (findingIds.isEmpty()) {
            return List.of();
        }

        return recommendationRepository.findByFindingIdIn(findingIds).stream()
            .sorted(Comparator.comparing(DiagnosticRecommendation::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private List<DiagnosticRecommendation> generateWithRetry(
            String teamCode, AiProvider provider, String sessionId) throws Exception {

        int maxAttempts = 3;
        long delayMinMs = DEFAULT_RETRY_INITIAL_DELAY_MIN_MS;
        long delayMaxMs = DEFAULT_RETRY_INITIAL_DELAY_MAX_MS;
        long backoffMaxMs = DEFAULT_RETRY_BACKOFF_MAX_DELAY_MS;
        long timeLimitMs = DEFAULT_RETRY_TIME_LIMIT_MS;

        Exception lastError = null;
        long startedAt = System.currentTimeMillis();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                long delayMs = computeRetryDelayWithJitterMs(attempt, delayMinMs, delayMaxMs, backoffMaxMs);
                long elapsed = System.currentTimeMillis() - startedAt;
                if (elapsed + delayMs > timeLimitMs) {
                    throw new IllegalStateException(
                        "DIAGNOSTIC ERROR: Retry time limit exceeded. Please try again.", lastError);
                }
                emit(sessionId, "RETRYING",
                    "Attempt " + attempt + " of " + maxAttempts + " — retrying after transient error", 20);
                sleepBeforeRetry(delayMs);
            }

            try {
                return generateOnce(teamCode, provider, sessionId);
            } catch (Exception e) {
                lastError = e;
                long elapsed = System.currentTimeMillis() - startedAt;

                log.warn("Diagnostic analysis failed for teamCode={} provider={} on attempt {}/{}: {}",
                    teamCode, provider.getProviderName(), attempt, maxAttempts, e.getMessage());

                String errMsg = e.getMessage() != null ? e.getMessage().toUpperCase() : "";
                if (errMsg.contains("PERMISSION DENIED") ||
                    errMsg.contains("FILE NOT FOUND") ||
                    errMsg.contains("UNSUPPORTED FILE FORMAT")) {
                    throw e;
                }

                if (elapsed >= timeLimitMs) {
                    throw new IllegalStateException(
                        "DIAGNOSTIC ERROR: Retry time limit exceeded. Please try again.", e);
                }
                if (attempt == maxAttempts) throw e;
            }
        }

        throw lastError != null ? lastError
            : new IllegalStateException("DIAGNOSTIC ERROR: All retry attempts exhausted.");
    }

    @Transactional
    private List<DiagnosticRecommendation> generateOnce(
            String teamCode, AiProvider provider, String sessionId) throws Exception {

        emit(sessionId, "LOADING", "Loading continuity findings", 15);

        List<ContinuityFinding> findings = findingRepository.findByTeamCodeIgnoreCaseOrderByDetectedAtDescIdAsc(teamCode);
        if (findings.isEmpty()) {
            emit(sessionId, "COMPLETE", "No findings to analyze", 100);
            return List.of();
        }

        for (ContinuityFinding finding : findings) {
            if (finding.getId() != null) {
                recommendationRepository.deleteByFindingId(finding.getId());
            }
        }

        emit(sessionId, "ANALYZING", "Generating recommendations using AI", 50);

        String prompt = buildDiagnosticPrompt(findings);
        String response = provider.complete(prompt);

        emit(sessionId, "PROCESSING", "Parsing AI response and creating recommendations", 80);

        return parseAndCreateRecommendations(response, findings);
    }

    private String buildDiagnosticPrompt(List<ContinuityFinding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("You help student software teams fix gaps in the traceability of their IEEE project documents. ")
          .append("Analyze the continuity findings below and give a short, clear explanation and fix for each one.\n\n");

        sb.append(TRACEABILITY_CONTEXT);

        sb.append("FINDINGS:\n");
        for (int i = 0; i < findings.size(); i++) {
            ContinuityFinding finding = findings.get(i);
            sb.append(String.format("%d. [%s] %s → %s: %s\n",
                i + 1,
                finding.getSeverity(),
                finding.getDocTypeFrom(),
                finding.getDocTypeTo(),
                finding.getDescription()));
        }

        sb.append("\nReturn your response as a raw JSON array with exactly one object per finding, in the same order. Each object should have:\n");
        sb.append("- \"findingNumber\": The number of the finding it answers.\n");
        sb.append("- \"rootCause\": One short sentence in plain English explaining why the issue happened. Start with \"This happened because\". Use sentence case.\n");
        sb.append("- \"recommendation\": One short plain-English sentence (about 15 words or fewer) that tells the student what to do. ")
          .append("Start with an action verb such as \"Add\", \"Map\", or \"Link\", and name the exact artifact and document involved. ")
          .append("Example: \"Add a wireframe in the SRS that matches the SMART goal.\"\n");
        sb.append("- \"priority\": One of \"HIGH\", \"MEDIUM\", or \"LOW\"\n");
        sb.append("Write for a student, not an expert: avoid jargon, headings, all-caps wording and filler. ")
          .append("Do not include markdown code fences, just the raw JSON array.\n");

        return sb.toString();
    }

    @Transactional
    private List<DiagnosticRecommendation> parseAndCreateRecommendations(
            String aiResponse, List<ContinuityFinding> findings) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(stripJsonCodeFences(aiResponse));
            if (!root.isArray()) {
                throw new RuntimeException("AI response is not a JSON array");
            }

            List<DiagnosticRecommendation> recommendations = new ArrayList<>();
            Set<Long> covered = new HashSet<>();
            for (int i = 0; i < root.size(); i++) {
                JsonNode node = root.get(i);
                // Prefer the finding number the model echoed back; fall back to position.
                int findingIndex = node.path("findingNumber").canConvertToInt()
                    ? node.path("findingNumber").asInt() - 1
                    : i;
                if (findingIndex < 0 || findingIndex >= findings.size()) continue;
                ContinuityFinding finding = findings.get(findingIndex);
                if (!covered.add(finding.getId())) continue;
                
                String rootCause = cleanDiagnosticText(node.path("rootCause").asText());
                String recommendationText = cleanDiagnosticText(node.path("recommendation").asText());
                String priority = node.path("priority").asText("MEDIUM").toUpperCase();

                DiagnosticRecommendation rec = new DiagnosticRecommendation();
                rec.setFindingId(finding.getId());
                rec.setRootCause(rootCause);
                rec.setRecommendation(recommendationText);
                rec.setPriority(priority);
                rec.setCreatedAt(LocalDateTime.now());
                
                recommendations.add(recommendationRepository.save(rec));
            }

            return recommendations;
        } catch (Exception e) {
            log.error("Failed to parse AI response as JSON: {}", e.getMessage());
            throw new RuntimeException(
                "The AI returned a response that could not be understood. Please try running the analysis again.", e);
        }
    }

    /** Defensively strips ```json fences the model may add despite being told not to. */
    private static String stripJsonCodeFences(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        String withoutOpeningFence = firstNewline != -1 ? trimmed.substring(firstNewline + 1) : trimmed;
        int lastFence = withoutOpeningFence.lastIndexOf("```");
        return (lastFence != -1 ? withoutOpeningFence.substring(0, lastFence) : withoutOpeningFence).trim();
    }

    private static String cleanDiagnosticText(String value) {
        String cleaned = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return cleaned;
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }

    private void emit(String sessionId, String step, String message, int percent) {
        if (sessionId == null || sessionId.isBlank()) return;
        progressEmitter.emit(sessionId, step, message, percent);
    }

    private long computeRetryDelayWithJitterMs(int attempt, long delayMinMs, long delayMaxMs, long backoffMaxMs) {
        int retryNumber = attempt - 1;
        int growthPower = Math.min(Math.max(0, retryNumber - 1), 20);
        long exponentialUpperBound = delayMaxMs * (1L << growthPower);
        long jitterUpperBound = Math.min(exponentialUpperBound, backoffMaxMs);
        long jitterLowerBound = Math.min(delayMinMs, jitterUpperBound);
        return ThreadLocalRandom.current().nextLong(jitterLowerBound, jitterUpperBound + 1);
    }

    private void sleepBeforeRetry(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DIAGNOSTIC ERROR: Retry interrupted.", ie);
        }
    }

    private AiProvider resolveProvider(String aiModel) {
        String key = (aiModel == null || aiModel.isBlank() || "auto".equalsIgnoreCase(aiModel))
                     ? "openai"
                     : aiModel.toLowerCase();
        if ("gpt".equals(key)) key = "openai";
        AiProvider provider = providers.get(key);
        if (provider == null) {
            throw new IllegalStateException(
                "No AI provider found for key '" + aiModel + "'. " +
                "Available providers: " + providers.keySet());
        }
        log.info("Resolved AI provider: {} (requested: {})", provider.getProviderName(), aiModel);
        return provider;
    }

    private String buildRunKey(String teamCode, String providerName) {
        return (teamCode == null ? "" : teamCode.trim()) + "|" +
               (providerName == null ? "" : providerName.trim().toLowerCase());
    }
}
