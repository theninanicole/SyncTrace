package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.SmartGoal;
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

    public List<Map<String, Object>> getAllGoalsWithCategoryStatus() {
        List<SmartGoal> goals = goalRepository.findAllByOrderByCreatedAtDesc();
        
        // Fetch all mappings for all goals in one query
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

        return goals.stream().map(goal -> {
            Map<String, Object> result = new HashMap<>();
            result.put("id", goal.getId());
            result.put("description", goal.getDescription());
            result.put("createdAt", goal.getCreatedAt());
            
            // Compute categoryStatus: which of the 5 DOC_TYPES have at least one mapped component
            Set<DocType> mappedTypes = goalDocTypes.getOrDefault(goal.getId(), List.of()).stream()
                .collect(Collectors.toSet());
            
            Map<String, Boolean> categoryStatus = new HashMap<>();
            for (DocType type : DocType.values()) {
                categoryStatus.put(type.name(), mappedTypes.contains(type));
            }
            result.put("categoryStatus", categoryStatus);
            
            return result;
        }).collect(Collectors.toList());
    }

    @Transactional
    public SmartGoal createGoal(String description) {
        // Dedupe by exact string match
        Optional<SmartGoal> existing = goalRepository.findByDescriptionIgnoreCase(description.trim());
        if (existing.isPresent()) {
            return existing.get();
        }

        SmartGoal goal = new SmartGoal();
        goal.setDescription(description.trim());
        goal.setCreatedAt(LocalDateTime.now());
        return goalRepository.save(goal);
    }

    @Transactional
    public void deleteGoal(Long goalId) {
        // Cascade delete mappings
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
        return components.stream()
            .map(c -> new TraceComponentSummaryDTO(
                c.getId(),
                c.getDocType(),
                c.getName(),
                c.getAiExtracted(),
                c.getSourceHistoryId(),
                c.getCreatedAt()
            ))
            .toList();
    }

    public Map<Long, List<TraceComponentSummaryDTO>> getAllGoalComponentsMap() {
        // Fetch all mappings
        List<com.ieee.evaluator.synctrace.model.GoalComponentMapping> allMappings = 
            mappingRepository.findAll();
        
        if (allMappings.isEmpty()) {
            return Map.of();
        }
        
        // Group componentIds by goalId
        Map<Long, List<Long>> componentIdsByGoal = new HashMap<>();
        for (var mapping : allMappings) {
            componentIdsByGoal.computeIfAbsent(mapping.getGoalId(), k -> new ArrayList<>())
                .add(mapping.getComponentId());
        }
        
        // Batch fetch all components
        Set<Long> allComponentIds = new HashSet<>();
        for (List<Long> ids : componentIdsByGoal.values()) {
            allComponentIds.addAll(ids);
        }
        
        if (allComponentIds.isEmpty()) {
            return Map.of();
        }
        
        List<TraceComponent> allComponents = componentRepository.findAllById(allComponentIds);
        
        // Build component lookup map
        Map<Long, TraceComponentSummaryDTO> componentLookup = new HashMap<>();
        for (TraceComponent c : allComponents) {
            componentLookup.put(c.getId(), new TraceComponentSummaryDTO(
                c.getId(),
                c.getDocType(),
                c.getName(),
                c.getAiExtracted(),
                c.getSourceHistoryId(),
                c.getCreatedAt()
            ));
        }
        
        // Build result map
        Map<Long, List<TraceComponentSummaryDTO>> result = new HashMap<>();
        for (Map.Entry<Long, List<Long>> entry : componentIdsByGoal.entrySet()) {
            Long goalId = entry.getKey();
            List<TraceComponentSummaryDTO> summaries = new ArrayList<>();
            for (Long componentId : entry.getValue()) {
                TraceComponentSummaryDTO summary = componentLookup.get(componentId);
                if (summary != null) {
                    summaries.add(summary);
                }
            }
            result.put(goalId, summaries);
        }
        
        return result;
    }

    @Transactional
    public void addGoalComponents(Long goalId, List<Long> componentIds) {
        for (Long componentId : componentIds) {
            // Check if mapping already exists
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
}
