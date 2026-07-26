package com.ieee.evaluator.synctrace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieee.evaluator.service.AiProvider;
import com.ieee.evaluator.service.GoogleDocsService;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ProposalAnalysisService {

    private static final int MAX_PAGES_TO_RENDER = 999;
    private static final int DEFAULT_ANALYZE_MAX_ATTEMPTS = 8;
    private static final long DEFAULT_RETRY_INITIAL_DELAY_MIN_MS = 2_000;
    private static final long DEFAULT_RETRY_INITIAL_DELAY_MAX_MS = 5_000;
    private static final long DEFAULT_RETRY_BACKOFF_MAX_DELAY_MS = 30_000;
    private static final long DEFAULT_RETRY_TIME_LIMIT_MS = 180_000;

    private final GoogleDocsService docsService;
    private final SmartGoalService smartGoalService;
    private final ProposalPromptService proposalPromptService;
    private final ProgressEmitter progressEmitter;
    private final Map<String, AiProvider> providers;
    private final Set<String> inFlightAnalyses = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public ProposalAnalysisService(
            GoogleDocsService docsService,
            SmartGoalService smartGoalService,
            ProposalPromptService proposalPromptService,
            ProgressEmitter progressEmitter,
            List<AiProvider> providerList) {
        this.docsService = docsService;
        this.smartGoalService = smartGoalService;
        this.proposalPromptService = proposalPromptService;
        this.progressEmitter = progressEmitter;
        this.providers = providerList.stream()
                .collect(Collectors.toMap(
                    p -> p.getProviderName().toLowerCase(),
                    Function.identity()
                ));
        this.objectMapper = new ObjectMapper();
    }

    public List<Map<String, Object>> extractSmartGoals(
            String fileId, String fileName, String aiModel, String sessionId) throws Exception {
        return extractSmartGoals(fileId, fileName, aiModel, sessionId, null);
    }

    public List<Map<String, Object>> extractSmartGoals(
            String fileId, String fileName, String aiModel, String sessionId, String teamCode) throws Exception {

        AiProvider provider = resolveProvider(aiModel);
        String runKey = buildRunKey(fileId, provider.getProviderName());

        if (!inFlightAnalyses.add(runKey)) {
            throw new IllegalStateException(
                "An analysis is already in progress for this file and provider. Please wait for it to finish.");
        }

        emit(sessionId, "RECEIVED", "Request accepted — starting proposal analysis", 5);

        try {
            List<Map<String, Object>> goals = extractWithRetry(fileId, fileName, provider, sessionId, teamCode);

            emit(sessionId, "COMPLETE", "Proposal analysis complete", 100);
            progressEmitter.complete(sessionId);

            return goals;
        } catch (Exception e) {
            progressEmitter.error(sessionId, e.getMessage());
            throw e;
        } finally {
            inFlightAnalyses.remove(runKey);
        }
    }

    private List<Map<String, Object>> extractWithRetry(
            String fileId, String fileName, AiProvider provider, String sessionId, String teamCode) throws Exception {

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
                        "PROPOSAL ANALYSIS ERROR: Retry time limit exceeded. Please try again.", lastError);
                }
                emit(sessionId, "RETRYING",
                    "Attempt " + attempt + " of " + maxAttempts + " — retrying after transient error", 20);
                sleepBeforeRetry(delayMs);
            }

            try {
                return extractOnce(fileId, fileName, provider, sessionId, teamCode);
            } catch (Exception e) {
                lastError = e;
                long elapsed = System.currentTimeMillis() - startedAt;

                log.warn("Proposal analysis failed for fileId={} provider={} on attempt {}/{}: {}",
                    fileId, provider.getProviderName(), attempt, maxAttempts, e.getMessage());

                String errMsg = e.getMessage() != null ? e.getMessage().toUpperCase() : "";
                if (errMsg.contains("PERMISSION DENIED") ||
                    errMsg.contains("FILE NOT FOUND") ||
                    errMsg.contains("UNSUPPORTED FILE FORMAT") ||
                    errMsg.contains("NO READABLE TEXT")) {
                    throw e;
                }

                if (elapsed >= timeLimitMs) {
                    throw new IllegalStateException(
                        "PROPOSAL ANALYSIS ERROR: Retry time limit exceeded. Please try again.", e);
                }
                if (attempt == maxAttempts) throw e;
            }
        }

        throw lastError != null ? lastError
            : new IllegalStateException("PROPOSAL ANALYSIS ERROR: All retry attempts exhausted.");
    }

    private List<Map<String, Object>> extractOnce(
            String fileId, String fileName, AiProvider provider, String sessionId, String teamCode) throws Exception {

        emit(sessionId, "EXTRACTING", "Downloading and extracting proposal text from Google Drive", 12);
        GoogleDocsService.DocumentData docData =
            docsService.extractDocumentContent(fileId, MAX_PAGES_TO_RENDER, sessionId, progressEmitter);

        if (docData.text() == null || docData.text().isBlank()) {
            throw new RuntimeException("No readable text found in this proposal document.");
        }

        emit(sessionId, "ANALYZING", "Extracting SMART goals using AI", 50);

        String prompt = proposalPromptService.smartGoalExtractionPrompt(docData.text());
        String response = provider.complete(prompt);

        emit(sessionId, "PROCESSING", "Parsing AI response and creating goals", 80);

        return parseAndCreateGoals(response, teamCode);
    }

    @Transactional
    protected List<Map<String, Object>> parseAndCreateGoals(String aiResponse, String teamCode) throws Exception {
        try {
            String cleaned = stripCodeFences(aiResponse);
            JsonNode root = objectMapper.readTree(cleaned);
            if (!root.isArray()) {
                throw new RuntimeException("AI response is not a JSON array");
            }

            List<Map<String, Object>> createdGoals = new java.util.ArrayList<>();
            for (JsonNode node : root) {
                if (node.isTextual()) {
                    // Legacy flat string → SPECIFIC
                    createdGoals.add(persistGoal(node.asText(), SmartGoal.GoalKind.SPECIFIC, null, teamCode));
                    continue;
                }
                if (!node.isObject()) continue;

                String description = node.path("description").asText("").trim();
                if (description.isBlank()) continue;

                SmartGoal.GoalKind kind = parseGoalKind(node.path("goalKind").asText("SPECIFIC"));
                if (kind == SmartGoal.GoalKind.GENERAL) {
                    Map<String, Object> parentMap = persistGoal(description, SmartGoal.GoalKind.GENERAL, null, teamCode);
                    createdGoals.add(parentMap);
                    Long parentId = (Long) parentMap.get("id");

                    JsonNode children = node.path("children");
                    if (children.isArray()) {
                        for (JsonNode child : children) {
                            String childDesc = child.isTextual()
                                ? child.asText("").trim()
                                : child.path("description").asText("").trim();
                            if (childDesc.isBlank()) continue;
                            createdGoals.add(persistGoal(childDesc, SmartGoal.GoalKind.SPECIFIC, parentId, teamCode));
                        }
                    }
                } else {
                    createdGoals.add(persistGoal(description, SmartGoal.GoalKind.SPECIFIC, null, teamCode));
                    JsonNode children = node.path("children");
                    if (children.isArray()) {
                        // Misplaced children under SPECIFIC — still create as SPECIFIC orphans
                        for (JsonNode child : children) {
                            String childDesc = child.isTextual()
                                ? child.asText("").trim()
                                : child.path("description").asText("").trim();
                            if (childDesc.isBlank()) continue;
                            createdGoals.add(persistGoal(childDesc, SmartGoal.GoalKind.SPECIFIC, null, teamCode));
                        }
                    }
                }
            }

            return createdGoals;
        } catch (Exception e) {
            log.error("Failed to parse AI response as JSON: {}", e.getMessage());
            throw new RuntimeException("Failed to parse AI response as JSON: " + e.getMessage());
        }
    }

    private Map<String, Object> persistGoal(
            String description, SmartGoal.GoalKind kind, Long parentGoalId, String teamCode) {
        var goal = smartGoalService.createGoal(description, kind, parentGoalId, teamCode);
        Map<String, Object> goalMap = new java.util.HashMap<>();
        goalMap.put("id", goal.getId());
        goalMap.put("description", goal.getDescription());
        goalMap.put("goalKind", goal.getGoalKind() != null ? goal.getGoalKind().name() : "SPECIFIC");
        goalMap.put("parentGoalId", goal.getParentGoalId());
        goalMap.put("teamCode", goal.getTeamCode());
        goalMap.put("createdAt", goal.getCreatedAt());
        return goalMap;
    }

    private static SmartGoal.GoalKind parseGoalKind(String raw) {
        if (raw == null || raw.isBlank()) return SmartGoal.GoalKind.SPECIFIC;
        try {
            return SmartGoal.GoalKind.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return SmartGoal.GoalKind.SPECIFIC;
        }
    }

    private static String stripCodeFences(String raw) {
        if (raw == null) return "[]";
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
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
            throw new IllegalStateException("PROPOSAL ANALYSIS ERROR: Retry interrupted.", ie);
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

    private String buildRunKey(String fileId, String providerName) {
        return (fileId == null ? "" : fileId.trim()) + "|" +
               (providerName == null ? "" : providerName.trim().toLowerCase());
    }
}
