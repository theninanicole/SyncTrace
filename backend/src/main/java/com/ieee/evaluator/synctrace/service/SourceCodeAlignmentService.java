package com.ieee.evaluator.synctrace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieee.evaluator.service.AiProvider;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
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
public class SourceCodeAlignmentService {

    private static final int DEFAULT_ANALYZE_MAX_ATTEMPTS = 8;
    private static final long DEFAULT_RETRY_INITIAL_DELAY_MIN_MS = 2_000;
    private static final long DEFAULT_RETRY_INITIAL_DELAY_MAX_MS = 5_000;
    private static final long DEFAULT_RETRY_BACKOFF_MAX_DELAY_MS = 30_000;
    private static final long DEFAULT_RETRY_TIME_LIMIT_MS = 180_000;

    private final TraceComponentRepository componentRepository;
    private final ContinuityFindingRepository findingRepository;
    private final ProgressEmitter progressEmitter;
    private final TeamComponentResolverService teamComponentResolver;
    private final Map<String, AiProvider> providers;
    private final Set<String> inFlightAnalyses = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public SourceCodeAlignmentService(
            TraceComponentRepository componentRepository,
            ContinuityFindingRepository findingRepository,
            ProgressEmitter progressEmitter,
            TeamComponentResolverService teamComponentResolver,
            List<AiProvider> providerList) {
        this.componentRepository = componentRepository;
        this.findingRepository = findingRepository;
        this.progressEmitter = progressEmitter;
        this.teamComponentResolver = teamComponentResolver;
        this.providers = providerList.stream()
                .collect(Collectors.toMap(
                    p -> p.getProviderName().toLowerCase(),
                    Function.identity()
                ));
        this.objectMapper = new ObjectMapper();
    }

    public List<ContinuityFinding> analyzeAlignment(
            String teamCode, String aiModel, String sessionId) throws Exception {

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

        emit(sessionId, "RECEIVED", "Request accepted — starting source code alignment", 5);

        try {
            List<ContinuityFinding> findings = analyzeWithRetry(teamCode, provider, sessionId);

            emit(sessionId, "COMPLETE", "Source code alignment complete", 100);
            progressEmitter.complete(sessionId);

            return findings;
        } catch (Exception e) {
            progressEmitter.error(sessionId, e.getMessage());
            throw e;
        } finally {
            inFlightAnalyses.remove(runKey);
        }
    }

    private List<ContinuityFinding> analyzeWithRetry(
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
                        "ALIGNMENT ERROR: Retry time limit exceeded. Please try again.", lastError);
                }
                emit(sessionId, "RETRYING",
                    "Attempt " + attempt + " of " + maxAttempts + " — retrying after transient error", 20);
                sleepBeforeRetry(delayMs);
            }

            try {
                return analyzeOnce(teamCode, provider, sessionId);
            } catch (Exception e) {
                lastError = e;
                long elapsed = System.currentTimeMillis() - startedAt;

                log.warn("Alignment analysis failed for teamCode={} provider={} on attempt {}/{}: {}",
                    teamCode, provider.getProviderName(), attempt, maxAttempts, e.getMessage());

                String errMsg = e.getMessage() != null ? e.getMessage().toUpperCase() : "";
                if (errMsg.contains("PERMISSION DENIED") ||
                    errMsg.contains("FILE NOT FOUND") ||
                    errMsg.contains("UNSUPPORTED FILE FORMAT")) {
                    throw e;
                }

                if (elapsed >= timeLimitMs) {
                    throw new IllegalStateException(
                        "ALIGNMENT ERROR: Retry time limit exceeded. Please try again.", e);
                }
                if (attempt == maxAttempts) throw e;
            }
        }

        throw lastError != null ? lastError
            : new IllegalStateException("ALIGNMENT ERROR: All retry attempts exhausted.");
    }

    @Transactional
    private List<ContinuityFinding> analyzeOnce(
            String teamCode, AiProvider provider, String sessionId) throws Exception {

        emit(sessionId, "LOADING", "Loading SDD and IMPLEMENTATION components", 15);

        // Get all components and filter by team using shared resolution logic
        List<TraceComponent> allComponents = componentRepository.findAll();
        List<TraceComponent> teamComponents = teamComponentResolver.filterByTeam(allComponents, teamCode);
        
        // Separate into SDD and IMPLEMENTATION components
        List<TraceComponent> sddComponents = teamComponents.stream()
            .filter(c -> c.getDocType() == DocType.SDD)
            .toList();
        
        List<TraceComponent> implComponents = teamComponents.stream()
            .filter(c -> c.getDocType() == DocType.IMPLEMENTATION)
            .toList();

        if (sddComponents.isEmpty() || implComponents.isEmpty()) {
            String reason = sddComponents.isEmpty() 
                ? "No SDD components found for this team (team code may not match any AI-extracted components)"
                : "No IMPLEMENTATION components found for this team (run GitHub ingestion first)";
            emit(sessionId, "COMPLETE", reason, 100);
            return List.of();
        }

        emit(sessionId, "ANALYZING", "Comparing SDD with IMPLEMENTATION using AI", 50);

        String prompt = buildAlignmentPrompt(sddComponents, implComponents);
        String response = provider.complete(prompt);

        emit(sessionId, "PROCESSING", "Parsing AI response and creating findings", 80);

        return parseAndCreateFindings(response, teamCode);
    }

    private String buildAlignmentPrompt(
            List<TraceComponent> sddComponents, List<TraceComponent> implComponents) {
        
        StringBuilder sb = new StringBuilder();
        sb.append("Compare the following Software Design Description (SDD) components with the IMPLEMENTATION components.\n");
        sb.append("Identify missing implementations, structural deviations, or inconsistencies.\n\n");
        
        sb.append("SDD COMPONENTS:\n");
        for (TraceComponent comp : sddComponents) {
            sb.append("- ").append(comp.getName()).append("\n");
            if (comp.getContent() != null) {
                sb.append("  Content: ").append(comp.getContent().substring(0, Math.min(500, comp.getContent().length()))).append("...\n");
            }
        }
        
        sb.append("\nIMPLEMENTATION COMPONENTS:\n");
        for (TraceComponent comp : implComponents) {
            sb.append("- ").append(comp.getName()).append("\n");
            if (comp.getContent() != null) {
                sb.append("  Content: ").append(comp.getContent().substring(0, Math.min(500, comp.getContent().length()))).append("...\n");
            }
        }
        
        sb.append("\nReturn your response as a raw JSON array of objects. Each object should have:\n");
        sb.append("- \"description\": A clear description of the misalignment\n");
        sb.append("- \"severity\": One of \"LOW\", \"MEDIUM\", \"HIGH\", or \"CRITICAL\"\n");
        sb.append("Do not include markdown code fences, just the raw JSON array.\n");
        
        return sb.toString();
    }

    @Transactional
    private List<ContinuityFinding> parseAndCreateFindings(String aiResponse, String teamCode) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(stripJsonCodeFences(aiResponse));
            if (!root.isArray()) {
                throw new RuntimeException("AI response is not a JSON array");
            }

            List<ContinuityFinding> findings = new ArrayList<>();
            for (JsonNode node : root) {
                String description = node.path("description").asText();
                String severityStr = node.path("severity").asText("MEDIUM").toUpperCase();
                
                ContinuityFinding.Severity severity;
                try {
                    severity = ContinuityFinding.Severity.valueOf(severityStr);
                } catch (IllegalArgumentException e) {
                    severity = ContinuityFinding.Severity.MEDIUM;
                }

                ContinuityFinding finding = new ContinuityFinding();
                finding.setTeamCode(teamCode);
                finding.setDocTypeFrom(DocType.SDD);
                finding.setDocTypeTo(DocType.IMPLEMENTATION);
                finding.setSeverity(severity);
                finding.setDescription(description);
                finding.setDetectedAt(LocalDateTime.now());
                
                findings.add(findingRepository.save(finding));
            }

            return findings;
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
            throw new IllegalStateException("ALIGNMENT ERROR: Retry interrupted.", ie);
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
