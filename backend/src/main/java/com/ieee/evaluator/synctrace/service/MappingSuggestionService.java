package com.ieee.evaluator.synctrace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieee.evaluator.service.AiProvider;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.synctrace.model.*;
import com.ieee.evaluator.synctrace.model.SmartGoal.GoalKind;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.model.TraceComponentSummaryDTO;
import com.ieee.evaluator.synctrace.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MappingSuggestionService {
    private static final int MAX_TARGETS_PER_SOURCE = 3;
    private static final int MAX_CANDIDATES_PER_SOURCE = 40;
    private static final int MAX_IMPLEMENTATION_CANDIDATES = 30;
    private static final long GENERATION_DEADLINE_MS = 55_000;
    private static final int MAX_PROMPT_TEXT = 1_500;

    public record Candidate(Long id, String text) {}
    public record Decision(String decision, List<Long> targetIds) {}
    public record GenerationResult(List<MappingSuggestion> suggestions, boolean partial,
            int sourcesProcessed, int sourcesTotal) {}
    private record BatchResult(Long sourceId, List<JsonNode> suggestions) {}

    public static class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message, Throwable cause) { super(message, cause); }
        public AiUnavailableException(String message) { super(message); }
    }

    private final MappingSuggestionRepository suggestionRepository;
    private final MappingGenerationRunRepository runRepository;
    private final StagedTraceMappingRepository stagedRepository;
    private final SmartGoalRepository goalRepository;
    private final TraceComponentRepository componentRepository;
    private final TeamComponentResolverService teamResolver;
    private final StagedMappingService stagedMappingService;
    private final MappingSuggestionPromptService promptService;
    private final ProgressEmitter progressEmitter;
    private final Map<String, AiProvider> providers;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private TransactionTemplate transactionTemplate;

    public MappingSuggestionService(MappingSuggestionRepository suggestionRepository,
            MappingGenerationRunRepository runRepository, StagedTraceMappingRepository stagedRepository,
            SmartGoalRepository goalRepository, TraceComponentRepository componentRepository,
            TeamComponentResolverService teamResolver, StagedMappingService stagedMappingService,
            MappingSuggestionPromptService promptService, ProgressEmitter progressEmitter,
            List<AiProvider> providers) {
        this.suggestionRepository = suggestionRepository;
        this.runRepository = runRepository;
        this.stagedRepository = stagedRepository;
        this.goalRepository = goalRepository;
        this.componentRepository = componentRepository;
        this.teamResolver = teamResolver;
        this.stagedMappingService = stagedMappingService;
        this.promptService = promptService;
        this.progressEmitter = progressEmitter;
        this.providers = providers.stream().collect(Collectors.toMap(
            provider -> provider.getProviderName().toLowerCase(Locale.ROOT),
            Function.identity()));
    }

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public GenerationResult generate(String teamCode, String stageKey, String sessionId) {
        StagedMappingService.Stage stage = stage(stageKey);
        long started = System.currentTimeMillis();
        List<Candidate> sources = sources(teamCode, stage);
        List<Candidate> targets = targets(teamCode, stage.targetType());
        if (sources.isEmpty() || targets.isEmpty()) {
            throw new IllegalArgumentException(sources.isEmpty()
                ? "No confirmed sources are available. Complete the preceding mapping step first."
                : "No confirmed target components are available. Add or extract the target components first.");
        }

        Set<String> staged = stagedPairs(teamCode, stageKey);
        Set<String> rejected = suggestionRepository
            .findByTeamCodeIgnoreCaseAndStageAndStatus(teamCode, stageKey, MappingSuggestion.Status.REJECTED)
            .stream().map(suggestion -> pair(suggestion.getSourceId(), suggestion.getTargetComponentId()))
            .collect(Collectors.toSet());
        Map<Long, Candidate> sourceById = index(sources);
        Map<Long, Candidate> targetById = index(targets);
        String runId = UUID.randomUUID().toString();
        progressEmitter.emit(sessionId, "RECEIVED", "Suggestion request accepted", 5);

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, Math.max(1, sources.size())));
        CompletionService<BatchResult> completion = new ExecutorCompletionService<>(executor);
        List<Future<BatchResult>> futures = new ArrayList<>();
        try {
            AiProvider provider = resolveProvider("openai");
            for (Candidate source : sources) {
                List<Candidate> candidates = candidateTargets(source, targets, stage.targetType());
                futures.add(completion.submit(() -> requestBatch(provider, stageKey, source, candidates)));
            }

            List<JsonNode> rawSuggestions = new ArrayList<>();
            int failed = 0;
            int processed = 0;
            int successful = 0;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GENERATION_DEADLINE_MS);
            while (processed < futures.size()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    failed += futures.size() - processed;
                    futures.forEach(future -> future.cancel(true));
                    break;
                }
                Future<BatchResult> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) {
                    failed += futures.size() - processed;
                    futures.forEach(candidate -> candidate.cancel(true));
                    break;
                }
                try {
                    BatchResult result = future.get();
                    rawSuggestions.addAll(result.suggestions());
                    successful++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failed++;
                } catch (ExecutionException e) {
                    failed++;
                    log.warn("Mapping suggestion batch failed: {}",
                        e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                }
                processed++;
                progressEmitter.emit(sessionId, "ANALYZING",
                    "Analyzed mapping batch " + processed + " of " + futures.size(),
                    Math.min(95, 5 + (processed * 90 / futures.size())));
            }
            if (failed == futures.size()) {
                throw new AiUnavailableException("AI suggestion generation failed for every batch.");
            }

            List<MappingSuggestion> generated = normalize(rawSuggestions, stageKey, runId,
                sourceById, targetById, staged, rejected);
            List<MappingSuggestion> saved = persistGenerated(teamCode, stageKey, generated, started);
            progressEmitter.emit(sessionId, "COMPLETE", "Suggestion generation complete", 100);
            progressEmitter.complete(sessionId);
            return new GenerationResult(saved, failed > 0, successful, futures.size());
        } catch (AiUnavailableException e) {
            progressEmitter.error(sessionId, e.getMessage());
            throw e;
        } catch (Exception e) {
            progressEmitter.error(sessionId, e.getMessage());
            throw new AiUnavailableException("AI suggestion generation failed.", e);
        } finally {
            executor.shutdownNow();
        }
    }

    @Transactional
    public List<MappingSuggestion> persistGenerated(String teamCode, String stageKey,
            List<MappingSuggestion> generated, long started) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status ->
                persistGeneratedRows(teamCode, stageKey, generated, started));
        }
        return persistGeneratedRows(teamCode, stageKey, generated, started);
    }

    private List<MappingSuggestion> persistGeneratedRows(String teamCode, String stageKey,
            List<MappingSuggestion> generated, long started) {
        suggestionRepository.deletePendingByTeamAndStage(
            teamCode, stageKey, MappingSuggestion.Status.PENDING);
        generated.forEach(suggestion -> suggestion.setTeamCode(teamCode));
        List<MappingSuggestion> saved = suggestionRepository.saveAll(generated);
        MappingGenerationRun run = new MappingGenerationRun();
        run.setTeamCode(teamCode);
        run.setStage(stageKey);
        run.setDurationMs(System.currentTimeMillis() - started);
        run.setSuggestionCount(saved.size());
        run.setStatus("COMPLETE");
        run.setCreatedAt(LocalDateTime.now());
        runRepository.save(run);
        return saved;
    }

    public List<MappingSuggestion> pending(String teamCode, String stage) {
        stage(stage);
        return suggestionRepository
            .findByTeamCodeIgnoreCaseAndStageAndStatusOrderByScoreDesc(
                teamCode, stage, MappingSuggestion.Status.PENDING)
            .stream()
            .filter(suggestion -> componentRepository.existsById(suggestion.getTargetComponentId()))
            .toList();
    }

    @Transactional
    public List<StagedTraceMapping> decide(String teamCode, Long id, Decision decision, String user,
            boolean student) {
        MappingSuggestion suggestion = suggestionRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Suggestion not found."));
        if (!suggestion.getTeamCode().equalsIgnoreCase(teamCode)) {
            throw new SyncTraceAccessGuard.AccessDeniedException(
                "You are not allowed to decide this suggestion.");
        }
        if (suggestion.getStatus() != MappingSuggestion.Status.PENDING) {
            throw new IllegalArgumentException("Suggestion has already been decided.");
        }
        if (!componentRepository.existsById(suggestion.getTargetComponentId())) {
            markStale(suggestion, user);
            throw new IllegalArgumentException(
                "This suggestion targets a component that no longer exists.");
        }

        String action = decision.decision() == null ? "" : decision.decision().toUpperCase(Locale.ROOT);
        if ("REJECT".equals(action)) {
            suggestion.setStatus(MappingSuggestion.Status.REJECTED);
            suggestion.setDecidedBy(user);
            suggestion.setDecidedAt(LocalDateTime.now());
            suggestionRepository.save(suggestion);
            return List.of();
        }
        if (!"ACCEPT".equals(action) && !"EDIT".equals(action)) {
            throw new IllegalArgumentException("decision must be ACCEPT, EDIT, or REJECT.");
        }
        List<Long> targets = "ACCEPT".equals(action)
            ? List.of(suggestion.getTargetComponentId())
            : Optional.ofNullable(decision.targetIds()).orElse(List.of());
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("targetIds are required for EDIT.");
        }
        for (Long targetId : targets) {
            if (!componentRepository.existsById(targetId)) {
                throw new IllegalArgumentException(
                    "An edited suggestion targets a component that no longer exists.");
            }
        }

        StagedTraceMapping.Origin origin = "EDIT".equals(action)
            ? StagedTraceMapping.Origin.AI_EDITED : StagedTraceMapping.Origin.AI_ACCEPTED;
        List<StagedMappingService.NewMapping> mappings = targets.stream()
            .map(target -> new StagedMappingService.NewMapping(
                suggestion.getStage(), suggestion.getSourceId(), target))
            .toList();
        List<StagedTraceMapping> rows = stagedMappingService.addWithOrigin(
            teamCode, mappings, user, student, origin);
        suggestion.setStatus("EDIT".equals(action)
            ? MappingSuggestion.Status.EDITED : MappingSuggestion.Status.ACCEPTED);
        suggestion.setDecidedBy(user);
        suggestion.setDecidedAt(LocalDateTime.now());
        suggestionRepository.save(suggestion);
        return rows;
    }

    @Transactional
    public int acceptAll(String teamCode, String stage, String user, boolean student) {
        int count = 0;
        stage(stage);
        for (MappingSuggestion suggestion : new ArrayList<>(
                suggestionRepository.findByTeamCodeIgnoreCaseAndStageAndStatus(
                    teamCode, stage, MappingSuggestion.Status.PENDING))) {
            try {
                decide(teamCode, suggestion.getId(), new Decision("ACCEPT", null), user, student);
                count++;
            } catch (IllegalArgumentException e) {
                log.info("Skipping suggestion {} during accept-all: {}",
                    suggestion.getId(), e.getMessage());
            }
        }
        return count;
    }

    @Transactional
    public void discard(String teamCode, String stage) {
        stage(stage);
        suggestionRepository.deletePendingByTeamAndStage(
            teamCode, stage, MappingSuggestion.Status.PENDING);
    }

    private List<Candidate> sources(String team, StagedMappingService.Stage stage) {
        if ("PROPOSAL".equals(stage.sourceType())) {
            return goalRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(team).stream()
                .filter(goal -> goal.getGoalKind() == GoalKind.GENERAL || goal.getParentGoalId() == null)
                .map(goal -> new Candidate(goal.getId(), truncate(goalText(goal))))
                .toList();
        }
        StagedMappingService.Stage preceding = StagedMappingService.STAGES.stream()
            .filter(candidate -> candidate.targetType().equals(stage.sourceType()))
            .findFirst().orElseThrow();
        Set<Long> ids = stagedRepository
            .findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(team).stream()
            .filter(mapping -> mapping.getStage().equals(preceding.key()))
            .map(StagedTraceMapping::getTargetId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return findConfirmedTargets(team, DocType.valueOf(stage.sourceType())).stream()
            .filter(component -> ids.contains(component.id()))
            .map(this::candidate)
            .toList();
    }

    private List<Candidate> targets(String team, String type) {
        return findConfirmedTargets(team, DocType.valueOf(type)).stream()
            .map(this::candidate)
            .toList();
    }

    /**
     * Filter out Needs Review components here once UC 1.3 adds the flag.
     */
    private List<TraceComponentSummaryDTO> findConfirmedTargets(String teamCode, DocType docType) {
        return teamResolver.findTeamSummaries(teamCode, docType, "");
    }

    private List<Candidate> candidateTargets(Candidate source, List<Candidate> targets, String targetType) {
        int limit = "IMPLEMENTATION".equals(targetType)
            ? MAX_IMPLEMENTATION_CANDIDATES : MAX_CANDIDATES_PER_SOURCE;
        if (targets.size() <= limit) {
            return targets;
        }
        Set<String> sourceWords = words(source.text());
        return targets.stream()
            .sorted(Comparator.comparingInt((Candidate target) -> overlap(sourceWords, words(target.text())))
                .reversed())
            .limit(limit)
            .toList();
    }

    private BatchResult requestBatch(AiProvider provider, String stage, Candidate source,
            List<Candidate> targets) throws Exception {
        JsonNode output = objectMapper.readTree(provider.complete(
            promptService.build(stage, List.of(source), targets)));
        if (!output.isArray()) {
            throw new IllegalStateException("AI response was not a JSON array.");
        }
        List<JsonNode> rows = new ArrayList<>();
        output.forEach(rows::add);
        return new BatchResult(source.id(), rows);
    }

    private List<MappingSuggestion> normalize(List<JsonNode> rows, String stage, String runId,
            Map<Long, Candidate> sourceById, Map<Long, Candidate> targetById,
            Set<String> staged, Set<String> rejected) {
        List<MappingSuggestion> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<Long, Integer> counts = new HashMap<>();
        for (JsonNode node : rows) {
            Long sourceId = longValue(node, "sourceId");
            Long targetId = longValue(node, "targetId");
            String pair = pair(sourceId, targetId);
            if (!sourceById.containsKey(sourceId) || !targetById.containsKey(targetId)
                    || staged.contains(pair) || rejected.contains(pair)
                    || counts.getOrDefault(sourceId, 0) >= MAX_TARGETS_PER_SOURCE) {
                continue;
            }
            double score = Math.max(0, Math.min(1, node.path("score").asDouble(0)));
            if (score < 0.2) continue;
            if (!seen.add(pair)) continue;
            MappingSuggestion suggestion = new MappingSuggestion();
            suggestion.setStage(stage);
            suggestion.setSourceId(sourceId);
            suggestion.setTargetComponentId(targetId);
            suggestion.setScore(score);
            suggestion.setReason(trimReason(node.path("reason").asText("Likely traceability match")));
            suggestion.setStatus(MappingSuggestion.Status.PENDING);
            suggestion.setRunId(runId);
            suggestion.setCreatedAt(LocalDateTime.now());
            result.add(suggestion);
            counts.merge(sourceId, 1, Integer::sum);
        }
        return result;
    }

    private Set<String> stagedPairs(String teamCode, String stage) {
        return stagedRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(teamCode).stream()
            .filter(mapping -> mapping.getStage().equals(stage))
            .map(mapping -> pair(mapping.getSourceId(), mapping.getTargetId()))
            .collect(Collectors.toSet());
    }

    private void markStale(MappingSuggestion suggestion, String user) {
        suggestion.setStatus(MappingSuggestion.Status.REJECTED);
        suggestion.setReason("Target component no longer exists.");
        suggestion.setDecidedBy(user);
        suggestion.setDecidedAt(LocalDateTime.now());
        suggestionRepository.save(suggestion);
    }

    private String goalText(SmartGoal goal) {
        String children = goalRepository.findByParentGoalIdOrderByCreatedAtAscIdAsc(goal.getId()).stream()
            .map(SmartGoal::getDescription).reduce("", (left, right) -> left + "\n" + right);
        return goal.getDescription() + children;
    }

    private Candidate candidate(TraceComponentSummaryDTO component) {
        return new Candidate(component.id(), truncate(component.name() + "\n" + component.content()));
    }

    private StagedMappingService.Stage stage(String key) {
        return StagedMappingService.STAGES.stream().filter(candidate -> candidate.key().equals(key))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown mapping stage: " + key));
    }

    private AiProvider resolveProvider(String aiModel) {
        String key = (aiModel == null || aiModel.isBlank() || "auto".equalsIgnoreCase(aiModel))
            ? "openai" : aiModel.toLowerCase(Locale.ROOT);
        if ("gpt".equals(key)) key = "openai";
        AiProvider provider = providers.get(key);
        if (provider == null) {
            throw new AiUnavailableException("No AI provider found for key '" + aiModel + "'.");
        }
        return provider;
    }

    private static String truncate(String text) {
        if (text == null) return "";
        String normalized = text.trim();
        return normalized.substring(0, Math.min(MAX_PROMPT_TEXT, normalized.length()));
    }

    private static Set<String> words(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            .filter(word -> word.length() > 2).collect(Collectors.toSet());
    }

    private static int overlap(Set<String> left, Set<String> right) {
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        return intersection.size();
    }

    private static String pair(Long sourceId, Long targetId) {
        return sourceId + ":" + targetId;
    }

    private static Map<Long, Candidate> index(List<Candidate> candidates) {
        Map<Long, Candidate> result = new HashMap<>();
        candidates.forEach(candidate -> result.put(candidate.id(), candidate));
        return result;
    }

    private static Long longValue(JsonNode node, String key) {
        return node.has(key) && node.get(key).canConvertToLong() ? node.get(key).asLong() : null;
    }

    private static String trimReason(String value) {
        String normalized = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.substring(0, Math.min(500, normalized.length()));
    }
}
