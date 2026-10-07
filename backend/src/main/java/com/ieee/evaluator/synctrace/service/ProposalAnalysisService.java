package com.ieee.evaluator.synctrace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieee.evaluator.service.AiProvider;
import com.ieee.evaluator.service.GoogleDocsService;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
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
    private final ProposalPromptService proposalPromptService;
    private final ProgressEmitter progressEmitter;
    private final Map<String, AiProvider> providers;
    private final Set<String> inFlightAnalyses = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public ProposalAnalysisService(
            GoogleDocsService docsService,
            ProposalPromptService proposalPromptService,
            ProgressEmitter progressEmitter,
            List<AiProvider> providerList) {
        this.docsService = docsService;
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

        emit(sessionId, "PROCESSING", "Parsing AI response into SMART goals for review", 80);

        return parseGoalDrafts(response);
    }

    /**
     * Turns the AI response into a draft goal tree for the user to review. Nothing is saved
     * here; the reviewed tree is saved through SmartGoalService.saveReviewedGoals.
     * Shape: [{ goalKind, description, children: [{ goalKind: "SPECIFIC", description }] }]
     */
    List<Map<String, Object>> parseGoalDrafts(String aiResponse) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripCodeFences(aiResponse));
        } catch (Exception e) {
            log.error("Failed to parse AI response as JSON: {}", e.getMessage());
            throw new RuntimeException("Failed to parse AI response as JSON: " + e.getMessage());
        }
        if (!root.isArray()) {
            throw new RuntimeException("Failed to parse AI response as JSON: AI response is not a JSON array");
        }

        List<Map<String, Object>> drafts = new java.util.ArrayList<>();
        // The AI sometimes copies one shared list of specific objectives under every general
        // objective, with small wording differences. Keep only the first occurrence of each.
        Set<String> seenSpecifics = new HashSet<>();
        for (JsonNode node : root) {
            if (node.isTextual()) {
                // Legacy flat string → SPECIFIC
                String text = node.asText("").trim();
                if (!text.isEmpty() && seenSpecifics.add(normalizeGoalText(text))) {
                    drafts.add(draft(SmartGoal.GoalKind.SPECIFIC, text, List.of()));
                }
                continue;
            }
            if (!node.isObject()) continue;

            String description = node.path("description").asText("").trim();
            if (description.isBlank()) continue;

            List<String> children = new java.util.ArrayList<>();
            JsonNode childNodes = node.path("children");
            if (childNodes.isArray()) {
                for (JsonNode child : childNodes) {
                    String childDesc = child.isTextual()
                        ? child.asText("").trim()
                        : child.path("description").asText("").trim();
                    if (!childDesc.isBlank() && seenSpecifics.add(normalizeGoalText(childDesc))) {
                        children.add(childDesc);
                    }
                }
            }

            if (parseGoalKind(node.path("goalKind").asText("SPECIFIC")) == SmartGoal.GoalKind.GENERAL) {
                drafts.add(draft(SmartGoal.GoalKind.GENERAL, description, children));
            } else {
                // Misplaced children under a SPECIFIC become standalone specific objectives.
                if (seenSpecifics.add(normalizeGoalText(description))) {
                    drafts.add(draft(SmartGoal.GoalKind.SPECIFIC, description, List.of()));
                }
                children.forEach(child -> drafts.add(draft(SmartGoal.GoalKind.SPECIFIC, child, List.of())));
            }
        }
        return drafts;
    }

    private static Map<String, Object> draft(SmartGoal.GoalKind kind, String description, List<String> children) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("goalKind", kind.name());
        node.put("description", description);
        node.put("children", children.stream()
            .map(child -> Map.<String, Object>of("goalKind", SmartGoal.GoalKind.SPECIFIC.name(), "description", child))
            .toList());
        return node;
    }

    /** Comparison key for an objective: case, list numbering/bullets, punctuation and spacing ignored. */
    public static String normalizeGoalText(String text) {
        if (text == null) return "";
        return text.toLowerCase(java.util.Locale.ROOT)
            .replaceFirst("^\\s*(?:[\\-*\u2022]|\\(?[0-9a-z]{1,3}[.)])\\s*", "")
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .trim();
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
