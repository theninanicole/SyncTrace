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
            String teamCode, String aiModel, String sessionId) throws Exception {

        AiProvider provider = resolveProvider(aiModel);
        String runKey = buildRunKey(teamCode, provider.getProviderName());

        if (!inFlightAnalyses.add(runKey)) {
            throw new IllegalStateException(
                "An analysis is already in progress for this team and provider. Please wait for it to finish.");
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

        List<ContinuityFinding> findings = findingRepository.findByTeamCode(teamCode);
        if (findings.isEmpty()) {
            emit(sessionId, "COMPLETE", "No findings to analyze", 100);
            return List.of();
        }

        emit(sessionId, "ANALYZING", "Generating recommendations using AI", 50);

        String prompt = buildDiagnosticPrompt(findings);
        String response = provider.complete(prompt);

        emit(sessionId, "PROCESSING", "Parsing AI response and creating recommendations", 80);

        return parseAndCreateRecommendations(response, findings);
    }

    private String buildDiagnosticPrompt(List<ContinuityFinding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze the following continuity findings and provide root cause analysis and recommendations.\n\n");
        
        sb.append("FINDINGS:\n");
        for (ContinuityFinding finding : findings) {
            sb.append(String.format("- [%s] %s → %s: %s\n",
                finding.getSeverity(),
                finding.getDocTypeFrom(),
                finding.getDocTypeTo(),
                finding.getDescription()));
        }
        
        sb.append("\nReturn your response as a raw JSON array of objects. Each object should have:\n");
        sb.append("- \"rootCause\": Analysis of why this issue occurred\n");
        sb.append("- \"recommendation\": Specific actionable steps to fix the issue\n");
        sb.append("- \"priority\": One of \"HIGH\", \"MEDIUM\", or \"LOW\"\n");
        sb.append("Do not include markdown code fences, just the raw JSON array.\n");
        
        return sb.toString();
    }

    @Transactional
    private List<DiagnosticRecommendation> parseAndCreateRecommendations(
            String aiResponse, List<ContinuityFinding> findings) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(aiResponse);
            if (!root.isArray()) {
                throw new RuntimeException("AI response is not a JSON array");
            }

            List<DiagnosticRecommendation> recommendations = new ArrayList<>();
            for (int i = 0; i < root.size() && i < findings.size(); i++) {
                JsonNode node = root.get(i);
                ContinuityFinding finding = findings.get(i);
                
                String rootCause = node.path("rootCause").asText();
                String recommendationText = node.path("recommendation").asText();
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
            throw new RuntimeException("Failed to parse AI response as JSON: " + e.getMessage());
        }
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
