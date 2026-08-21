package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.SmartGoal.GoalKind;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.model.TraceComponentSummaryDTO;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
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

    public SmartGoalService(
            SmartGoalRepository goalRepository,
            GoalComponentMappingRepository mappingRepository,
            TraceComponentRepository componentRepository) {
        this.goalRepository = goalRepository;
        this.mappingRepository = mappingRepository;
        this.componentRepository = componentRepository;
    }

    public List<Map<String, Object>> getAllGoalsWithCategoryStatus(String teamCode) {
        List<SmartGoal> goals = (teamCode != null && !teamCode.isBlank())
            ? goalRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtDesc(teamCode.trim())
            : goalRepository.findAllByOrderByCreatedAtDesc();

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

    @Transactional
    public void deleteGoal(Long goalId) {
        List<SmartGoal> children = goalRepository.findByParentGoalIdOrderByCreatedAtDesc(goalId);
        for (SmartGoal child : children) {
            mappingRepository.deleteByGoalId(child.getId());
            goalRepository.deleteById(child.getId());
        }
        mappingRepository.deleteByGoalId(goalId);
        goalRepository.deleteById(goalId);
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
        List<com.ieee.evaluator.synctrace.model.GoalComponentMapping> allMappings =
            mappingRepository.findAll();

        if (allMappings.isEmpty()) {
            return Map.of();
        }

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
        for (TraceComponent c : allComponents) {
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
        for (Long componentId : componentIds) {
            if (mappingRepository.findByGoalIdAndComponentId(goalId, componentId).isEmpty()) {
                var mapping = new com.ieee.evaluator.synctrace.model.GoalComponentMapping();
                mapping.setGoalId(goalId);
                mapping.setComponentId(componentId);
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
            code,
            c.getAiExtracted(),
            c.getSourceHistoryId(),
            c.getCreatedAt()
        );
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
