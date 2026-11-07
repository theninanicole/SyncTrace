package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.StagedTraceMapping;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.repository.StagedTraceMappingRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * The team's shared staged mapping workspace. Every user editing a team's mapping reads and
 * writes the same chain; Save Mapping (sync) turns that chain into the goal-to-component
 * links the traceability results read.
 */
@Service
public class StagedMappingService {

    /** Mirrors STAGES in the frontend's synctrace/constants.js. */
    public record Stage(String key, String sourceType, String targetType) {}

    public static final List<Stage> STAGES = List.of(
        new Stage("PROPOSAL_SRS", "PROPOSAL", "SRS"),
        new Stage("SRS_SDD", "SRS", "SDD"),
        new Stage("SRS_STD", "SRS", "STD"),
        new Stage("SRS_SPMP", "SRS", "SPMP"),
        new Stage("SDD_IMPLEMENTATION", "SDD", "IMPLEMENTATION")
    );

    public record NewMapping(String stage, Long sourceId, Long targetId, StagedTraceMapping.Origin origin) {
        public NewMapping(String stage, Long sourceId, Long targetId) {
            this(stage, sourceId, targetId, StagedTraceMapping.Origin.MANUAL);
        }
    }

    public record SyncResult(int linkCount, int added, int removed, boolean analysisCleared) {}

    private final StagedTraceMappingRepository stagedRepository;
    private final GoalComponentMappingRepository mappingRepository;
    private final SmartGoalRepository goalRepository;
    private final TraceComponentRepository componentRepository;
    private final SmartGoalService goalService;
    private final TeamComponentResolverService teamComponentResolver;
    private final ContinuityAnalysisResetService analysisResetService;

    public StagedMappingService(
            StagedTraceMappingRepository stagedRepository,
            GoalComponentMappingRepository mappingRepository,
            SmartGoalRepository goalRepository,
            TraceComponentRepository componentRepository,
            SmartGoalService goalService,
            TeamComponentResolverService teamComponentResolver,
            ContinuityAnalysisResetService analysisResetService) {
        this.stagedRepository = stagedRepository;
        this.mappingRepository = mappingRepository;
        this.goalRepository = goalRepository;
        this.componentRepository = componentRepository;
        this.goalService = goalService;
        this.teamComponentResolver = teamComponentResolver;
        this.analysisResetService = analysisResetService;
    }

    public List<StagedTraceMapping> list(String teamCode) {
        return stagedRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(teamCode);
    }

    /**
     * Adds links to the team's chain, skipping ones already present. Returns the rows for
     * every requested link (new or existing) so the caller can use their server ids.
     *
     * @param restrictToTeam when true (students), components owned by another team are refused
     */
    @Transactional
    public List<StagedTraceMapping> add(String teamCode, List<NewMapping> mappings, String createdBy,
            boolean restrictToTeam) {
        return addInternal(teamCode, mappings, createdBy, restrictToTeam, StagedTraceMapping.Origin.MANUAL);
    }

    List<StagedTraceMapping> addWithOrigin(String teamCode, List<NewMapping> mappings, String createdBy,
            boolean restrictToTeam, StagedTraceMapping.Origin origin) {
        return addInternal(teamCode, mappings, createdBy, restrictToTeam, origin);
    }

    private List<StagedTraceMapping> addInternal(String teamCode, List<NewMapping> mappings, String createdBy,
            boolean restrictToTeam, StagedTraceMapping.Origin origin) {
        Map<String, StagedTraceMapping> existing = new HashMap<>();
        for (StagedTraceMapping m : list(teamCode)) existing.put(key(m.getStage(), m.getSourceId(), m.getTargetId()), m);

        List<StagedTraceMapping> result = new ArrayList<>();
        for (NewMapping requested : mappings) {
            Stage stage = stageFor(requested.stage());
            if (requested.sourceId() == null || requested.targetId() == null) {
                throw new IllegalArgumentException("sourceId and targetId are required.");
            }
            validateSource(stage, requested.sourceId(), teamCode, restrictToTeam);
            validateComponent(requested.targetId(), stage.targetType(), teamCode, restrictToTeam);

            String k = key(stage.key(), requested.sourceId(), requested.targetId());
            StagedTraceMapping row = existing.get(k);
            if (row == null) {
                row = new StagedTraceMapping();
                row.setTeamCode(teamCode);
                row.setStage(stage.key());
                row.setSourceId(requested.sourceId());
                row.setTargetId(requested.targetId());
                row.setCreatedBy(createdBy);
                row.setCreatedAt(LocalDateTime.now());
                row.setOrigin(origin);
                row = stagedRepository.save(row);
                existing.put(k, row);
            }
            result.add(row);
        }
        return result;
    }

    @Transactional
    public void remove(String teamCode, Long mappingId) {
        stagedRepository.findById(mappingId).ifPresent(row -> {
            if (!row.getTeamCode().equalsIgnoreCase(teamCode)) {
                throw new SyncTraceAccessGuard.AccessDeniedException("You are not allowed to change this mapping.");
            }
            stagedRepository.delete(row);
        });
    }

    /**
     * Makes the team's goal-to-component links exactly what the chain implies: adds missing
     * links and removes every other link on the team's goals, including ones saved before the
     * workspace was shared. The shared chain is the only source of goal links.
     */
    @Transactional
    public SyncResult sync(String teamCode) {
        Set<Long> teamGoalIds = new HashSet<>();
        goalRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(teamCode)
            .forEach(goal -> teamGoalIds.add(goal.getId()));

        List<StagedTraceMapping> chain = list(teamCode);
        Set<List<Long>> derived = deriveGoalLinks(chain, teamGoalIds);
        Map<List<Long>, GoalComponentMapping.MappingSource> origins = deriveGoalLinkOrigins(chain, teamGoalIds);

        int removed = 0;
        Set<List<Long>> existing = new HashSet<>();
        for (GoalComponentMapping mapping : teamGoalIds.isEmpty()
                ? List.<GoalComponentMapping>of()
                : mappingRepository.findByGoalIdIn(teamGoalIds)) {
            List<Long> pair = List.of(mapping.getGoalId(), mapping.getComponentId());
            if (derived.contains(pair)) {
                existing.add(pair);
                mapping.setSource(origins.getOrDefault(pair, GoalComponentMapping.MappingSource.MANUAL));
                mappingRepository.save(mapping);
            } else {
                mappingRepository.delete(mapping);
                removed++;
            }
        }

        int added = 0;
        for (List<Long> pair : derived) {
            if (existing.contains(pair)) continue;
            goalService.addGoalComponents(pair.get(0), List.of(pair.get(1)),
                origins.getOrDefault(pair, GoalComponentMapping.MappingSource.MANUAL));
            added++;
        }

        // An analysis of mappings that changed (or no longer exist) would be misleading.
        boolean analysisCleared = added + removed > 0 || derived.isEmpty();
        if (analysisCleared) analysisResetService.resetTeam(teamCode);
        return new SyncResult(derived.size(), added, removed, analysisCleared);
    }

    /** Every (goalId, componentId) pair the chain implies, limited to the team's goals. */
    static Set<List<Long>> deriveGoalLinks(List<StagedTraceMapping> chain, Set<Long> teamGoalIds) {
        Set<List<Long>> links = new LinkedHashSet<>();
        for (StagedTraceMapping m : chain) {
            Stage stage = STAGES.stream().filter(s -> s.key().equals(m.getStage())).findFirst().orElse(null);
            if (stage == null) continue;
            for (Long goalId : resolveGoalIds(m.getSourceId(), stage, chain)) {
                if (!teamGoalIds.contains(goalId)) continue;
                links.add(List.of(goalId, m.getTargetId()));
                if (!"PROPOSAL".equals(stage.sourceType())) links.add(List.of(goalId, m.getSourceId()));
            }

        }
        return links;
    }

    static Map<List<Long>, GoalComponentMapping.MappingSource> deriveGoalLinkOrigins(
            List<StagedTraceMapping> chain, Set<Long> teamGoalIds) {
        Map<List<Long>, GoalComponentMapping.MappingSource> origins = new LinkedHashMap<>();
        for (StagedTraceMapping m : chain) {
            Stage stage = STAGES.stream().filter(s -> s.key().equals(m.getStage())).findFirst().orElse(null);
            if (stage == null) continue;
            GoalComponentMapping.MappingSource source = switch (m.effectiveOrigin()) {
                case AI_ACCEPTED -> GoalComponentMapping.MappingSource.AI_ACCEPTED;
                case AI_EDITED -> GoalComponentMapping.MappingSource.AI_EDITED;
                default -> GoalComponentMapping.MappingSource.MANUAL;
            };
            for (Long goalId : resolveGoalIds(m.getSourceId(), stage, chain)) {
                if (!teamGoalIds.contains(goalId)) continue;
                origins.put(List.of(goalId, m.getTargetId()), source);
                if (!"PROPOSAL".equals(stage.sourceType())) {
                    origins.putIfAbsent(List.of(goalId, m.getSourceId()), source);
                }
            }
        }
        return origins;
    }

    /** Walks a source back through earlier stages to the goal(s) it originates from. */
    private static Set<Long> resolveGoalIds(Long sourceId, Stage stage, List<StagedTraceMapping> chain) {
        if ("PROPOSAL".equals(stage.sourceType())) return Set.of(sourceId);
        Set<Long> goalIds = new HashSet<>();
        for (Stage preceding : STAGES) {
            if (!preceding.targetType().equals(stage.sourceType())) continue;
            for (StagedTraceMapping m : chain) {
                if (m.getStage().equals(preceding.key()) && m.getTargetId().equals(sourceId)) {
                    goalIds.addAll(resolveGoalIds(m.getSourceId(), preceding, chain));
                }
            }
        }
        return goalIds;
    }

    private Stage stageFor(String key) {
        return STAGES.stream().filter(s -> s.key().equals(key)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown mapping stage: " + key));
    }

    private void validateSource(Stage stage, Long sourceId, String teamCode, boolean restrictToTeam) {
        if (!"PROPOSAL".equals(stage.sourceType())) {
            validateComponent(sourceId, stage.sourceType(), teamCode, restrictToTeam);
            return;
        }
        SmartGoal goal = goalRepository.findById(sourceId)
            .orElseThrow(() -> new IllegalArgumentException("SMART goal " + sourceId + " not found."));
        if (goal.getTeamCode() != null && !goal.getTeamCode().equalsIgnoreCase(teamCode)) {
            throw new SyncTraceAccessGuard.AccessDeniedException("That SMART goal belongs to another team.");
        }
    }

    private void validateComponent(Long componentId, String docType, String teamCode, boolean restrictToTeam) {
        TraceComponent component = componentRepository.findById(componentId)
            .orElseThrow(() -> new IllegalArgumentException("Component " + componentId + " not found."));
        if (!component.getDocType().name().equals(docType)) {
            throw new IllegalArgumentException("Component " + componentId + " is not a " + docType + " component.");
        }
        if (restrictToTeam) {
            teamComponentResolver.resolveTeamCode(component)
                .filter(owner -> !owner.equalsIgnoreCase(teamCode))
                .ifPresent(owner -> {
                    throw new SyncTraceAccessGuard.AccessDeniedException("That component belongs to another team.");
                });
        }
    }

    private static String key(String stage, Long sourceId, Long targetId) {
        return stage + ":" + sourceId + ":" + targetId;
    }
}
