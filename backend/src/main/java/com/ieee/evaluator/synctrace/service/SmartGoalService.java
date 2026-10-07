package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.SmartGoal.GoalKind;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.model.TraceComponentSummaryDTO;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.StagedTraceMappingRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SmartGoalService {

    private final SmartGoalRepository goalRepository;
    private final GoalComponentMappingRepository mappingRepository;
    private final TraceComponentRepository componentRepository;
    private final StagedTraceMappingRepository stagedMappingRepository;
    private final TeamComponentResolverService teamComponentResolver;

    public SmartGoalService(
            SmartGoalRepository goalRepository,
            GoalComponentMappingRepository mappingRepository,
            TraceComponentRepository componentRepository,
            StagedTraceMappingRepository stagedMappingRepository,
            TeamComponentResolverService teamComponentResolver) {
        this.goalRepository = goalRepository;
        this.mappingRepository = mappingRepository;
        this.componentRepository = componentRepository;
        this.stagedMappingRepository = stagedMappingRepository;
        this.teamComponentResolver = teamComponentResolver;
    }

    public List<Map<String, Object>> getAllGoalsWithCategoryStatus(String teamCode) {
        List<SmartGoal> goals = (teamCode != null && !teamCode.isBlank())
            ? goalRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(teamCode.trim())
            : goalRepository.findAllByOrderByCreatedAtAscIdAsc();

        List<Long> goalIds = goals.stream().map(SmartGoal::getId).toList();
        Map<Long, List<DocType>> goalDocTypes = new HashMap<>();

        if (!goalIds.isEmpty()) {
            List<Object[]> mappings = mappingRepository.findDocTypesByGoalIds(goalIds);
            for (Object[] row : mappings) {
                Long goalId = (Long) row[0];
                DocType docType = (DocType) row[1];
                goalDocTypes.computeIfAbsent(goalId, k -> new ArrayList<>()).add(docType);
            }
        }

        return goals.stream().map(goal -> toGoalMap(goal, goalDocTypes)).collect(Collectors.toList());
    }

    public List<Map<String, Object>> getAllGoalsWithCategoryStatus() {
        return getAllGoalsWithCategoryStatus(null);
    }

    private Map<String, Object> toGoalMap(SmartGoal goal, Map<Long, List<DocType>> goalDocTypes) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", goal.getId());
        result.put("description", goal.getDescription());
        result.put("goalKind", goal.getGoalKind() != null ? goal.getGoalKind().name() : GoalKind.SPECIFIC.name());
        result.put("parentGoalId", goal.getParentGoalId());
        result.put("teamCode", goal.getTeamCode());
        result.put("createdAt", goal.getCreatedAt());

        Set<DocType> mappedTypes = goalDocTypes.getOrDefault(goal.getId(), List.of()).stream()
            .collect(Collectors.toSet());

        Map<String, Boolean> categoryStatus = new HashMap<>();
        for (DocType type : DocType.values()) {
            if (type == DocType.PROPOSAL) continue;
            categoryStatus.put(type.name(), mappedTypes.contains(type));
        }
        result.put("categoryStatus", categoryStatus);
        return result;
    }

    @Transactional
    public SmartGoal createGoal(String description) {
        return createGoal(description, GoalKind.SPECIFIC, null, null);
    }

    @Transactional
    public SmartGoal createGoal(String description, String teamCode) {
        return createGoal(description, GoalKind.SPECIFIC, null, teamCode);
    }

    @Transactional
    public SmartGoal createGoal(String description, GoalKind goalKind, Long parentGoalId, String teamCode) {
        GoalKind kind = goalKind != null ? goalKind : GoalKind.SPECIFIC;

        Optional<SmartGoal> existing = goalRepository.findByDescriptionIgnoreCase(description.trim());
        if (existing.isPresent()) {
            SmartGoal found = existing.get();
            boolean dirty = false;
            if (kind == GoalKind.GENERAL && found.getGoalKind() != GoalKind.GENERAL) {
                found.setGoalKind(GoalKind.GENERAL);
                found.setParentGoalId(null);
                dirty = true;
            }
            if (kind == GoalKind.SPECIFIC && parentGoalId != null && found.getParentGoalId() == null) {
                found.setParentGoalId(parentGoalId);
                found.setGoalKind(GoalKind.SPECIFIC);
                dirty = true;
            }
            if (blankToNull(teamCode) != null && (found.getTeamCode() == null || found.getTeamCode().isBlank())) {
                found.setTeamCode(blankToNull(teamCode));
                dirty = true;
            }
            return dirty ? goalRepository.save(found) : found;
        }

        if (kind == GoalKind.GENERAL && parentGoalId != null) {
            throw new IllegalArgumentException("GENERAL goals cannot have a parent goal.");
        }
        if (kind == GoalKind.SPECIFIC && parentGoalId != null) {
            SmartGoal parent = goalRepository.findById(parentGoalId)
                .orElseThrow(() -> new IllegalArgumentException("Parent goal not found: " + parentGoalId));
            if (parent.getGoalKind() != GoalKind.GENERAL) {
                throw new IllegalArgumentException("Parent goal must be a GENERAL objective.");
            }
        }

        SmartGoal goal = new SmartGoal();
        goal.setDescription(description.trim());
        goal.setGoalKind(kind);
        goal.setParentGoalId(kind == GoalKind.SPECIFIC ? parentGoalId : null);
        goal.setTeamCode(blankToNull(teamCode));
        goal.setCreatedAt(LocalDateTime.now());
        return goalRepository.save(goal);
    }

    /**
     * Teacher-entered goal. Unlike createGoal (used by extraction), this never reuses an
     * existing goal with the same wording, which could belong to another team.
     */
    @Transactional
    public SmartGoal addGoal(String description, GoalKind goalKind, Long parentGoalId, String teamCode) {
        String text = description == null ? "" : description.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Description is required.");
        GoalKind kind = goalKind != null ? goalKind : GoalKind.SPECIFIC;

        if (kind == GoalKind.GENERAL && parentGoalId != null) {
            throw new IllegalArgumentException("GENERAL goals cannot have a parent goal.");
        }
        String scope = blankToNull(teamCode);
        if (kind == GoalKind.SPECIFIC && parentGoalId != null) {
            SmartGoal parent = goalRepository.findById(parentGoalId)
                .orElseThrow(() -> new IllegalArgumentException("Parent goal not found: " + parentGoalId));
            if (parent.getGoalKind() != GoalKind.GENERAL) {
                throw new IllegalArgumentException("Parent goal must be a GENERAL objective.");
            }
            // A specific objective always belongs to its general objective's team.
            scope = parent.getTeamCode();
        }

        SmartGoal goal = new SmartGoal();
        goal.setDescription(text);
        goal.setGoalKind(kind);
        goal.setParentGoalId(kind == GoalKind.SPECIFIC ? parentGoalId : null);
        goal.setTeamCode(scope);
        goal.setCreatedAt(LocalDateTime.now());
        return goalRepository.save(goal);
    }

    /**
     * Saves a goal tree the user reviewed after extraction:
     * [{ goalKind, description, children: [{ description }] }].
     * Goals already on file for the team with the same wording (ignoring case, numbering and
     * punctuation) are reused, so saving the same proposal twice adds nothing new and existing
     * mappings are kept. Returns the number of goals created.
     */
    @Transactional
    public int saveReviewedGoals(String teamCode, List<Map<String, Object>> tree) {
        String scope = blankToNull(teamCode);
        List<SmartGoal> existing = scope != null
            ? goalRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(scope)
            : goalRepository.findAllByOrderByCreatedAtAscIdAsc().stream()
                .filter(g -> g.getTeamCode() == null).collect(Collectors.toCollection(ArrayList::new));
        Map<String, SmartGoal> generalByText = new HashMap<>();
        Map<String, SmartGoal> specificByText = new HashMap<>();
        for (SmartGoal goal : existing) {
            String key = ProposalAnalysisService.normalizeGoalText(goal.getDescription());
            (goal.getGoalKind() == GoalKind.GENERAL ? generalByText : specificByText).putIfAbsent(key, goal);
        }

        int created = 0;
        for (Map<String, Object> node : tree == null ? List.<Map<String, Object>>of() : tree) {
            String description = textOf(node);
            if (description == null) continue;
            boolean general = "GENERAL".equalsIgnoreCase(String.valueOf(node.get("goalKind")));
            List<String> children = childTexts(node.get("children"));

            if (!general) {
                created += saveReviewedSpecific(description, null, scope, specificByText) ? 1 : 0;
                for (String child : children) {
                    created += saveReviewedSpecific(child, null, scope, specificByText) ? 1 : 0;
                }
                continue;
            }

            String key = ProposalAnalysisService.normalizeGoalText(description);
            SmartGoal parent = generalByText.get(key);
            if (parent == null) {
                parent = newGoal(description, GoalKind.GENERAL, null, scope);
                generalByText.put(key, parent);
                created++;
            }
            for (String child : children) {
                created += saveReviewedSpecific(child, parent.getId(), scope, specificByText) ? 1 : 0;
            }
        }
        return created;
    }

    private boolean saveReviewedSpecific(String description, Long parentId, String scope,
            Map<String, SmartGoal> specificByText) {
        String key = ProposalAnalysisService.normalizeGoalText(description);
        SmartGoal found = specificByText.get(key);
        if (found != null) {
            // Already on file: attach a previously standalone objective to its reviewed parent.
            if (parentId != null && found.getParentGoalId() == null) {
                found.setParentGoalId(parentId);
                goalRepository.save(found);
            }
            return false;
        }
        specificByText.put(key, newGoal(description, GoalKind.SPECIFIC, parentId, scope));
        return true;
    }

    private SmartGoal newGoal(String description, GoalKind kind, Long parentId, String teamCode) {
        SmartGoal goal = new SmartGoal();
        goal.setDescription(description);
        goal.setGoalKind(kind);
        goal.setParentGoalId(parentId);
        goal.setTeamCode(teamCode);
        goal.setCreatedAt(LocalDateTime.now());
        return goalRepository.save(goal);
    }

    private static String textOf(Object node) {
        Object raw = node instanceof Map<?, ?> map ? map.get("description") : node;
        if (raw == null) return null;
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : text;
    }

    private static List<String> childTexts(Object children) {
        if (!(children instanceof List<?> list)) return List.of();
        return list.stream().map(SmartGoalService::textOf).filter(Objects::nonNull).toList();
    }

    /** Rewords a goal. Its kind, parent, team and mappings are unchanged. */
    @Transactional
    public SmartGoal updateGoalDescription(Long goalId, String description) {
        String text = description == null ? "" : description.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Description is required.");
        SmartGoal goal = goalRepository.findById(goalId)
            .orElseThrow(() -> new IllegalArgumentException("Goal not found: " + goalId));
        goal.setDescription(text);
        return goalRepository.save(goal);
    }

    @Transactional
    public void deleteGoal(Long goalId) {
        List<SmartGoal> children = goalRepository.findByParentGoalIdOrderByCreatedAtAscIdAsc(goalId);
        for (SmartGoal child : children) {
            deleteGoalLinks(child.getId());
            goalRepository.deleteById(child.getId());
        }
        deleteGoalLinks(goalId);
        goalRepository.deleteById(goalId);
    }

    private void deleteGoalLinks(Long goalId) {
        mappingRepository.deleteByGoalId(goalId);
        stagedMappingRepository.deleteGoalReferences(goalId);
    }

    public List<TraceComponentSummaryDTO> getGoalComponents(Long goalId) {
        List<Long> componentIds = mappingRepository.findByGoalId(goalId).stream()
            .map(mapping -> mapping.getComponentId())
            .toList();

        if (componentIds.isEmpty()) {
            return List.of();
        }

        List<TraceComponent> components = componentRepository.findAllById(componentIds);
        return components.stream().map(this::toSummary).toList();
    }

    public Map<Long, List<TraceComponentSummaryDTO>> getAllGoalComponentsMap() {
        return getAllGoalComponentsMap(null);
    }

    public Map<Long, List<TraceComponentSummaryDTO>> getAllGoalComponentsMap(String teamCode) {
        List<com.ieee.evaluator.synctrace.model.GoalComponentMapping> allMappings;
        if (teamCode == null || teamCode.isBlank()) {
            allMappings = mappingRepository.findAll();
        } else {
            List<Long> goalIds = goalRepository
                .findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(teamCode.trim())
                .stream()
                .map(SmartGoal::getId)
                .toList();
            if (goalIds.isEmpty()) return Map.of();
            allMappings = mappingRepository.findByGoalIdIn(goalIds);
        }

        if (allMappings.isEmpty()) {
            return Map.of();
        }

        allMappings = new ArrayList<>(allMappings);
        allMappings.sort(Comparator.comparing(
            com.ieee.evaluator.synctrace.model.GoalComponentMapping::getId,
            Comparator.nullsLast(Comparator.naturalOrder())));
        Map<Long, List<Long>> componentIdsByGoal = new HashMap<>();
        for (var mapping : allMappings) {
            componentIdsByGoal.computeIfAbsent(mapping.getGoalId(), k -> new ArrayList<>())
                .add(mapping.getComponentId());
        }

        Set<Long> allComponentIds = new HashSet<>();
        for (List<Long> ids : componentIdsByGoal.values()) {
            allComponentIds.addAll(ids);
        }

        if (allComponentIds.isEmpty()) {
            return Map.of();
        }

        List<TraceComponent> allComponents = componentRepository.findAllById(allComponentIds);
        Map<Long, TraceComponentSummaryDTO> componentLookup = new HashMap<>();
        boolean teamScoped = teamCode != null && !teamCode.isBlank();
        for (TraceComponent c : allComponents) {
            // Same ownership rule gap detection and readiness use, so the matrix never
            // shows a component the analysis ignores (or vice versa).
            if (teamScoped && !teamComponentResolver.countsForTeam(c, teamCode)) continue;
            componentLookup.put(c.getId(), toSummary(c));
        }

        Map<Long, List<TraceComponentSummaryDTO>> result = new HashMap<>();
        for (Map.Entry<Long, List<Long>> entry : componentIdsByGoal.entrySet()) {
            List<TraceComponentSummaryDTO> summaries = new ArrayList<>();
            for (Long componentId : entry.getValue()) {
                TraceComponentSummaryDTO summary = componentLookup.get(componentId);
                if (summary != null) summaries.add(summary);
            }
            result.put(entry.getKey(), summaries);
        }
        return result;
    }

    @Transactional
    public void addGoalComponents(Long goalId, List<Long> componentIds) {
        addGoalComponents(goalId, componentIds, GoalComponentMapping.MappingSource.MANUAL);
    }

    @Transactional
    public void addGoalComponents(Long goalId, List<Long> componentIds, GoalComponentMapping.MappingSource source) {
        for (Long componentId : componentIds) {
            if (mappingRepository.findByGoalIdAndComponentId(goalId, componentId).isEmpty()) {
                var mapping = new GoalComponentMapping();
                mapping.setGoalId(goalId);
                mapping.setComponentId(componentId);
                mapping.setSource(source);
                mapping.setCreatedAt(LocalDateTime.now());
                mappingRepository.save(mapping);
            }
        }
    }

    @Transactional
    public void removeGoalComponent(Long goalId, Long componentId) {
        mappingRepository.findByGoalIdAndComponentId(goalId, componentId)
            .ifPresent(mappingRepository::delete);
    }

    private TraceComponentSummaryDTO toSummary(TraceComponent c) {
        ArtifactKind kind = c.getArtifactKind() != null ? c.getArtifactKind() : ArtifactKind.UNSPECIFIED;
        String code = ComponentCodeHelper.resolveDisplayCode(c.getCodeName(), c.getName(), c.getContent());
        return new TraceComponentSummaryDTO(
            c.getId(),
            c.getDocType(),
            kind,
            c.getName(),
            c.getContent(),
            code,
            c.getAiExtracted(),
            c.getSourceHistoryId(),
            c.getSourceType(),
            c.getSourceRef(),
            c.getSourceUrl(),
            c.getSourceCapturedAt(),
            c.getCreatedAt()
        );
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
