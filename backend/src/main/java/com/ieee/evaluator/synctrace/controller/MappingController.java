package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.model.GoalSummaryDTO;
import com.ieee.evaluator.synctrace.model.Goal;
import com.ieee.evaluator.synctrace.model.Component;
import com.ieee.evaluator.synctrace.model.ComponentMapping;
import com.ieee.evaluator.synctrace.model.DocType;
import com.ieee.evaluator.synctrace.repository.GoalRepository;
import com.ieee.evaluator.synctrace.repository.ComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.ComponentRepository;
import com.ieee.evaluator.synctrace.service.ComponentExtractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/synctrace")
@Slf4j
public class MappingController {

    private final GoalRepository              goalRepository;
    private final ComponentRepository         componentRepository;
    private final ComponentMappingRepository  mappingRepository;
    private final ComponentExtractionService  extractionService;

    public MappingController(
            GoalRepository goalRepository,
            ComponentRepository componentRepository,
            ComponentMappingRepository mappingRepository,
            ComponentExtractionService extractionService) {
        this.goalRepository      = goalRepository;
        this.componentRepository = componentRepository;
        this.mappingRepository   = mappingRepository;
        this.extractionService   = extractionService;
    }

    // ── Goals ────────────────────────────────────────────────────────────────

    @GetMapping("/goals")
    public ResponseEntity<?> listGoals() {
        try {
            List<Goal> goals = goalRepository.findAllByOrderByIdAsc();
            List<Long> goalIds = goals.stream().map(Goal::getId).toList();

            Map<Long, List<ComponentMapping>> mappingsByGoal = mappingRepository
                .findByGoalIdIn(goalIds).stream()
                .collect(Collectors.groupingBy(ComponentMapping::getGoalId));

            Map<Long, Component> componentsById = componentRepository.findAll().stream()
                .collect(Collectors.toMap(Component::getId, c -> c));

            List<GoalSummaryDTO> result = goals.stream().map(goal -> {
                Map<DocType, Boolean> status = new EnumMap<>(DocType.class);
                for (DocType dt : DocType.values()) status.put(dt, false);

                for (ComponentMapping m : mappingsByGoal.getOrDefault(goal.getId(), List.of())) {
                    Component c = componentsById.get(m.getComponentId());
                    if (c != null) status.put(c.getDocType(), true);
                }
                return new GoalSummaryDTO(goal, status);
            }).toList();

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to list goals: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to load goals."));
        }
    }

    @PostMapping("/goals")
    public ResponseEntity<?> createGoal(@RequestBody Map<String, String> payload) {
        try {
            String description = payload.get("description");
            if (description == null || description.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "description is required."));
            }
            Goal goal = new Goal();
            goal.setDescription(description.trim());
            goal.setCreatedAt(LocalDateTime.now());
            return ResponseEntity.status(HttpStatus.CREATED).body(goalRepository.save(goal));
        } catch (Exception e) {
            log.error("Failed to create goal: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to create goal."));
        }
    }

    @DeleteMapping("/goals/{goalId}")
    public ResponseEntity<?> deleteGoal(@PathVariable Long goalId) {
        try {
            if (!goalRepository.existsById(goalId)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Goal not found."));
            }
            mappingRepository.deleteByGoalId(goalId);
            goalRepository.deleteById(goalId);
            return ResponseEntity.ok(Map.of("message", "Goal deleted."));
        } catch (Exception e) {
            log.error("Failed to delete goal {}: {}", goalId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to delete goal."));
        }
    }

    // ── Component library ───────────────────────────────────────────────────

    @GetMapping("/components")
    public ResponseEntity<?> listComponents(
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) String search) {
        try {
            DocType parsedType = parseDocType(docType);
            boolean hasSearch = search != null && !search.isBlank();

            List<Component> result;
            if (parsedType != null && hasSearch) {
                result = componentRepository.findByDocTypeAndNameContainingIgnoreCaseOrderByIdDesc(parsedType, search.trim());
            } else if (parsedType != null) {
                result = componentRepository.findByDocTypeOrderByIdDesc(parsedType);
            } else if (hasSearch) {
                result = componentRepository.findByNameContainingIgnoreCaseOrderByIdDesc(search.trim());
            } else {
                result = componentRepository.findAllByOrderByIdDesc();
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to list components: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to load components."));
        }
    }

    @PostMapping("/components")
    public ResponseEntity<?> createComponent(@RequestBody Map<String, String> payload) {
        try {
            String name = payload.get("name");
            DocType docType = parseDocType(payload.get("docType"));
            if (name == null || name.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "name is required."));
            }
            if (docType == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "docType must be one of SRS, SDD, SPMP, STD, IMPLEMENTATION."));
            }

            Component existing = componentRepository.findByDocTypeAndNameIgnoreCase(docType, name.trim()).orElse(null);
            if (existing != null) {
                return ResponseEntity.ok(existing);
            }

            Component component = new Component();
            component.setDocType(docType);
            component.setName(name.trim());
            component.setContent(Optional.ofNullable(payload.get("content")).map(String::trim).orElse(null));
            component.setAiExtracted(false);
            component.setCreatedAt(LocalDateTime.now());
            return ResponseEntity.status(HttpStatus.CREATED).body(componentRepository.save(component));
        } catch (Exception e) {
            log.error("Failed to create component: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to create component."));
        }
    }

    @PutMapping("/components/{componentId}")
    public ResponseEntity<?> renameComponent(@PathVariable Long componentId, @RequestBody Map<String, String> payload) {
        try {
            String name = payload.get("name");
            if (name == null || name.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "name is required."));
            }

            Component component = componentRepository.findById(componentId).orElse(null);
            if (component == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Component not found."));
            }

            Component duplicate = componentRepository
                .findByDocTypeAndNameIgnoreCase(component.getDocType(), name.trim())
                .orElse(null);
            if (duplicate != null && !duplicate.getId().equals(componentId)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Another " + component.getDocType() + " component already has that name."));
            }

            component.setName(name.trim());
            return ResponseEntity.ok(componentRepository.save(component));
        } catch (Exception e) {
            log.error("Failed to rename component {}: {}", componentId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to rename component."));
        }
    }

    @DeleteMapping("/components/{componentId}")
    public ResponseEntity<?> deleteComponent(@PathVariable Long componentId) {
        try {
            if (!componentRepository.existsById(componentId)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Component not found."));
            }
            mappingRepository.deleteByComponentId(componentId);
            componentRepository.deleteById(componentId);
            return ResponseEntity.ok(Map.of("message", "Component deleted."));
        } catch (Exception e) {
            log.error("Failed to delete component {}: {}", componentId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to delete component."));
        }
    }

    @PostMapping("/components/extract")
    public ResponseEntity<?> extractComponents(@RequestBody Map<String, Object> payload) {
        try {
            Long historyId = toLong(payload.get("historyId"));
            if (historyId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "historyId is required."));
            }
            List<Component> extracted = extractionService.extractFromHistory(historyId);
            return ResponseEntity.ok(Map.of(
                "components", extracted,
                "count", extracted.size()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Component extraction failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Extraction failed: " + e.getMessage()));
        }
    }

    // ── Goal <-> component mappings ─────────────────────────────────────────

    @GetMapping("/goals/{goalId}/components")
    public ResponseEntity<?> getGoalComponents(@PathVariable Long goalId) {
        try {
            List<Long> componentIds = mappingRepository.findByGoalId(goalId).stream()
                .map(ComponentMapping::getComponentId)
                .toList();
            List<Component> components = componentRepository.findAllById(componentIds);
            return ResponseEntity.ok(components);
        } catch (Exception e) {
            log.error("Failed to load mappings for goal {}: {}", goalId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to load mappings."));
        }
    }

    @PostMapping("/goals/{goalId}/components")
    public ResponseEntity<?> addGoalComponents(@PathVariable Long goalId, @RequestBody Map<String, Object> payload) {
        try {
            if (!goalRepository.existsById(goalId)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Goal not found."));
            }
            Object rawIds = payload.get("componentIds");
            if (!(rawIds instanceof List<?> idList) || idList.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "componentIds must be a non-empty array."));
            }

            int added = 0;
            for (Object idObj : idList) {
                Long componentId = toLong(idObj);
                if (componentId == null) continue;
                if (mappingRepository.existsByGoalIdAndComponentId(goalId, componentId)) continue;

                ComponentMapping mapping = new ComponentMapping();
                mapping.setGoalId(goalId);
                mapping.setComponentId(componentId);
                mapping.setCreatedAt(LocalDateTime.now());
                mappingRepository.save(mapping);
                added++;
            }

            return ResponseEntity.ok(Map.of("added", added));
        } catch (Exception e) {
            log.error("Failed to add mappings for goal {}: {}", goalId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to add mappings."));
        }
    }

    @DeleteMapping("/goals/{goalId}/components/{componentId}")
    public ResponseEntity<?> removeGoalComponent(@PathVariable Long goalId, @PathVariable Long componentId) {
        try {
            mappingRepository.deleteByGoalIdAndComponentId(goalId, componentId);
            return ResponseEntity.ok(Map.of("message", "Mapping removed."));
        } catch (Exception e) {
            log.error("Failed to remove mapping goal={} component={}: {}", goalId, componentId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to remove mapping."));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private DocType parseDocType(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) return null;
        try {
            return DocType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); }
        catch (NumberFormatException e) { return null; }
    }
}
