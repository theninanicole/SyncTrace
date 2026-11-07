package com.ieee.evaluator.synctrace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ieee.evaluator.service.AiProvider;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.synctrace.model.*;
import com.ieee.evaluator.synctrace.model.SmartGoal.GoalKind;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class MappingSuggestionService {
    public record Candidate(Long id, String text) {}
    public record Decision(String decision, List<Long> targetIds) {}
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
    private final List<AiProvider> providers;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        this.providers = providers;
    }

    @Transactional
    public List<MappingSuggestion> generate(String teamCode, String stageKey, String sessionId) {
        StagedMappingService.Stage stage = stage(stageKey);
        long started = System.currentTimeMillis();
        List<Candidate> sources = sources(teamCode, stage);
        List<Candidate> targets = targets(teamCode, stage.targetType());
        if (sources.isEmpty() || targets.isEmpty()) {
            throw new IllegalArgumentException(sources.isEmpty()
                ? "No confirmed sources are available. Complete the preceding mapping step first."
                : "No confirmed target components are available. Add or extract the target components first.");
        }
        Set<String> staged = new HashSet<>();
        stagedRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(teamCode).stream()
            .filter(m -> m.getStage().equals(stageKey))
            .forEach(m -> staged.add(pair(m.getSourceId(), m.getTargetId())));
        Set<String> rejected = new HashSet<>();
        suggestionRepository.findByTeamCodeIgnoreCaseAndStageAndStatus(teamCode, stageKey,
            MappingSuggestion.Status.REJECTED).forEach(s -> rejected.add(pair(s.getSourceId(), s.getTargetComponentId())));
        Map<Long, Candidate> sourceById = index(sources);
        Map<Long, Candidate> targetById = index(targets);
        String runId = UUID.randomUUID().toString();
        progressEmitter.emit(sessionId, "RECEIVED", "Suggestion request accepted", 5);
        List<MappingSuggestion> generated = new ArrayList<>();
        try {
            AiProvider provider = providers.stream().findFirst()
                .orElseThrow(() -> new AiUnavailableException("No AI provider is configured."));
            progressEmitter.emit(sessionId, "ANALYZING", "Analyzing confirmed sources", 20);
            JsonNode output = objectMapper.readTree(provider.complete(promptService.build(stageKey, sources, targets)));
            if (!output.isArray()) throw new IllegalStateException("AI response was not a JSON array.");
            Map<Long, Integer> counts = new HashMap<>();
            for (JsonNode node : output) {
                Long sourceId = longValue(node, "sourceId");
                Long targetId = longValue(node, "targetId");
                if (!sourceById.containsKey(sourceId) || !targetById.containsKey(targetId)
                        || staged.contains(pair(sourceId, targetId)) || rejected.contains(pair(sourceId, targetId))
                        || counts.getOrDefault(sourceId, 0) >= 3) continue;
                double score = Math.max(0, Math.min(1, node.path("score").asDouble(0)));
                if (score < 0.2) continue;
                MappingSuggestion suggestion = new MappingSuggestion();
                suggestion.setTeamCode(teamCode);
                suggestion.setStage(stageKey);
                suggestion.setSourceId(sourceId);
                suggestion.setTargetComponentId(targetId);
                suggestion.setScore(score);
                suggestion.setReason(trimReason(node.path("reason").asText("Likely traceability match")));
                suggestion.setStatus(MappingSuggestion.Status.PENDING);
                suggestion.setRunId(runId);
                suggestion.setCreatedAt(LocalDateTime.now());
                generated.add(suggestion);
                counts.merge(sourceId, 1, Integer::sum);
            }
        } catch (AiUnavailableException e) {
            progressEmitter.error(sessionId, e.getMessage());
            throw e;
        } catch (Exception e) {
            progressEmitter.error(sessionId, e.getMessage());
            throw new AiUnavailableException("AI suggestion generation failed.", e);
        }
        suggestionRepository.deletePendingByTeamAndStage(teamCode, stageKey);
        List<MappingSuggestion> saved = suggestionRepository.saveAll(generated);
        MappingGenerationRun run = new MappingGenerationRun();
        run.setTeamCode(teamCode); run.setStage(stageKey); run.setDurationMs(System.currentTimeMillis() - started);
        run.setSuggestionCount(saved.size()); run.setStatus("COMPLETE"); run.setCreatedAt(LocalDateTime.now());
        runRepository.save(run);
        progressEmitter.emit(sessionId, "COMPLETE", "Suggestion generation complete", 100);
        progressEmitter.complete(sessionId);
        return saved;
    }

    public List<MappingSuggestion> pending(String teamCode, String stage) {
        stage(stage);
        return suggestionRepository.findByTeamCodeIgnoreCaseAndStageAndStatusOrderByScoreDesc(
            teamCode, stage, MappingSuggestion.Status.PENDING);
    }

    @Transactional
    public List<StagedTraceMapping> decide(String teamCode, Long id, Decision decision, String user, boolean student) {
        MappingSuggestion suggestion = suggestionRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Suggestion not found."));
        if (!suggestion.getTeamCode().equalsIgnoreCase(teamCode))
            throw new SyncTraceAccessGuard.AccessDeniedException("You are not allowed to decide this suggestion.");
        if (suggestion.getStatus() != MappingSuggestion.Status.PENDING)
            throw new IllegalArgumentException("Suggestion has already been decided.");
        String action = decision.decision() == null ? "" : decision.decision().toUpperCase();
        if ("REJECT".equals(action)) {
            suggestion.setStatus(MappingSuggestion.Status.REJECTED);
            suggestion.setDecidedBy(user); suggestion.setDecidedAt(LocalDateTime.now());
            suggestionRepository.save(suggestion);
            return List.of();
        }
        if (!"ACCEPT".equals(action) && !"EDIT".equals(action))
            throw new IllegalArgumentException("decision must be ACCEPT, EDIT, or REJECT.");
        List<Long> targets = "ACCEPT".equals(action) ? List.of(suggestion.getTargetComponentId())
            : Optional.ofNullable(decision.targetIds()).orElse(List.of());
        if (targets.isEmpty()) throw new IllegalArgumentException("targetIds are required for EDIT.");
        StagedTraceMapping.Origin origin = "EDIT".equals(action)
            ? StagedTraceMapping.Origin.AI_EDITED : StagedTraceMapping.Origin.AI_ACCEPTED;
        List<StagedMappingService.NewMapping> mappings = targets.stream()
            .map(target -> new StagedMappingService.NewMapping(suggestion.getStage(), suggestion.getSourceId(), target, origin)).toList();
        List<StagedTraceMapping> rows = stagedMappingService.add(teamCode, mappings, user, student);
        suggestion.setStatus("EDIT".equals(action) ? MappingSuggestion.Status.EDITED : MappingSuggestion.Status.ACCEPTED);
        suggestion.setDecidedBy(user); suggestion.setDecidedAt(LocalDateTime.now());
        suggestionRepository.save(suggestion);
        return rows;
    }

    @Transactional
    public int acceptAll(String teamCode, String stage, String user, boolean student) {
        int count = 0;
        for (MappingSuggestion s : new ArrayList<>(pending(teamCode, stage))) {
            decide(teamCode, s.getId(), new Decision("ACCEPT", null), user, student);
            count++;
        }
        return count;
    }

    @Transactional
    public void discard(String teamCode, String stage) {
        stage(stage);
        suggestionRepository.deletePendingByTeamAndStage(teamCode, stage);
    }

    private List<Candidate> sources(String team, StagedMappingService.Stage stage) {
        if ("PROPOSAL".equals(stage.sourceType())) {
            return goalRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(team).stream()
                .filter(g -> g.getGoalKind() == GoalKind.GENERAL || g.getParentGoalId() == null)
                .map(g -> new Candidate(g.getId(), goalText(g))).toList();
        }
        StagedMappingService.Stage preceding = StagedMappingService.STAGES.stream()
            .filter(s -> s.targetType().equals(stage.sourceType())).findFirst().orElseThrow();
        Set<Long> ids = new LinkedHashSet<>();
        stagedRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(team).stream()
            .filter(m -> m.getStage().equals(preceding.key())).forEach(m -> ids.add(m.getTargetId()));
        return componentRepository.findAllById(ids).stream()
            .filter(c -> c.getDocType().name().equals(stage.sourceType()))
            .map(this::candidate).toList();
    }

    private List<Candidate> targets(String team, String type) {
        return teamResolver.findTeamComponents(team).stream()
            .filter(c -> c.getDocType() == DocType.valueOf(type))
            .map(this::candidate).toList();
    }

    private String goalText(SmartGoal g) {
        String children = goalRepository.findByParentGoalIdOrderByCreatedAtAscIdAsc(g.getId()).stream()
            .map(SmartGoal::getDescription).reduce("", (a, b) -> a + "\n" + b);
        return g.getDescription() + children;
    }
    private Candidate candidate(TraceComponent c) { return new Candidate(c.getId(), c.getName() + "\n" + c.getContent()); }
    private StagedMappingService.Stage stage(String key) {
        return StagedMappingService.STAGES.stream().filter(s -> s.key().equals(key)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown mapping stage: " + key));
    }
    private static String pair(Long a, Long b) { return a + ":" + b; }
    private static Map<Long, Candidate> index(List<Candidate> list) {
        Map<Long, Candidate> result = new HashMap<>(); list.forEach(c -> result.put(c.id(), c)); return result;
    }
    private static Long longValue(JsonNode n, String key) {
        return n.has(key) && n.get(key).canConvertToLong() ? n.get(key).asLong() : null;
    }
    private static String trimReason(String value) {
        if (value == null) return "";
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.substring(0, Math.min(500, normalized.length()));
    }
}
