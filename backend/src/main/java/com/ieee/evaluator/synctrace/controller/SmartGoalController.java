package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.model.SmartGoal.GoalKind;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.MappingStage;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.service.AiTraceabilityMappingService;
import com.ieee.evaluator.synctrace.service.SmartGoalService;
import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/synctrace/goals")
public class SmartGoalController {

    private final SmartGoalService goalService;
    private final SmartGoalRepository goalRepository;
    private final SyncTraceAccessGuard accessGuard;
    private final AiTraceabilityMappingService aiTraceabilityMappingService;

    public SmartGoalController(SmartGoalService goalService, SmartGoalRepository goalRepository,
            SyncTraceAccessGuard accessGuard, AiTraceabilityMappingService aiTraceabilityMappingService) {
        this.goalService = goalService;
        this.goalRepository = goalRepository;
        this.accessGuard = accessGuard;
        this.aiTraceabilityMappingService = aiTraceabilityMappingService;
    }

    @PostMapping("/ai-mapping")
    public ResponseEntity<?> generateAiMapping(
            @RequestParam String teamCode,
            @RequestParam String stage,
            @RequestParam(required = false) String aiModel) {
        try {
            String effectiveTeamCode = accessGuard.resolveEffectiveTeamCode(teamCode);
            if (effectiveTeamCode == null || effectiveTeamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }

            MappingStage mappingStage;
            try {
                mappingStage = MappingStage.valueOf(stage);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown stage: " + stage));
            }

            Map<String, Object> result = aiTraceabilityMappingService.generateMappings(
                    effectiveTeamCode, mappingStage, aiModel, accessGuard.isStudent());
            return ResponseEntity.ok(result);
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to generate AI traceability mapping: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> getSmartGoals(@RequestParam(required = false) String teamCode) {
        try {
            String effectiveTeamCode = accessGuard.resolveEffectiveTeamCode(teamCode);
            if (effectiveTeamCode != null) {
                accessGuard.requirePublishedIfStudent(effectiveTeamCode);
            }
            return ResponseEntity.ok(goalService.getAllGoalsWithCategoryStatus(effectiveTeamCode));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch SMART goals: " + e.getMessage()));
        }
    }

    @GetMapping("/components")
    public ResponseEntity<?> getAllGoalComponents(@RequestParam(required = false) String teamCode) {
        try {
            String effectiveTeamCode = accessGuard.resolveEffectiveTeamCode(teamCode);
            if (effectiveTeamCode == null) {
                return ResponseEntity.ok(goalService.getAllGoalComponentsMap());
            }
            accessGuard.requirePublishedIfStudent(effectiveTeamCode);

            Set<Long> goalIds = goalService.getAllGoalsWithCategoryStatus(effectiveTeamCode).stream()
                    .map(goal -> ((Number) goal.get("id")).longValue())
                    .collect(java.util.stream.Collectors.toSet());
            Map<Long, ?> allComponents = goalService.getAllGoalComponentsMap();
            Map<Long, Object> filtered = new java.util.LinkedHashMap<>();
            allComponents.forEach((goalId, components) -> {
                if (goalIds.contains(goalId)) {
                    filtered.put(goalId, components);
                }
            });
            return ResponseEntity.ok(filtered);
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch all goal components: " + e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> createSmartGoal(@RequestBody Map<String, Object> payload) {
        try {
            String description = payload.get("description") != null
                ? String.valueOf(payload.get("description")) : null;
            if (description == null || description.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Description is required"));
            }

            GoalKind goalKind = GoalKind.SPECIFIC;
            if (payload.get("goalKind") != null && !String.valueOf(payload.get("goalKind")).isBlank()) {
                goalKind = GoalKind.valueOf(String.valueOf(payload.get("goalKind")).trim().toUpperCase());
            }

            Long parentGoalId = null;
            if (payload.get("parentGoalId") != null && !String.valueOf(payload.get("parentGoalId")).isBlank()) {
                parentGoalId = Long.valueOf(String.valueOf(payload.get("parentGoalId")));
            }

            String teamCode = payload.get("teamCode") != null
                ? String.valueOf(payload.get("teamCode")) : null;

            return ResponseEntity.ok(goalService.createGoal(description, goalKind, parentGoalId, teamCode));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create goal: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{goalId}")
    public ResponseEntity<?> deleteSmartGoal(@PathVariable Long goalId) {
        try {
            goalService.deleteGoal(goalId);
            return ResponseEntity.ok(Map.of("message", "Goal deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete goal: " + e.getMessage()));
        }
    }

    @GetMapping("/{goalId}/components")
    public ResponseEntity<?> getGoalComponents(@PathVariable Long goalId) {
        try {
            SmartGoal goal = goalRepository.findById(goalId)
                    .orElseThrow(() -> new SyncTraceAccessGuard.AccessDeniedException("Goal not found"));
            String goalTeamCode = goal.getTeamCode();
            String effectiveTeamCode = accessGuard.resolveEffectiveTeamCode(goalTeamCode);
            if (effectiveTeamCode != null) {
                accessGuard.requirePublishedIfStudent(effectiveTeamCode);
            }
            if (accessGuard.isStudent() && (goalTeamCode == null || !effectiveTeamCode.equalsIgnoreCase(goalTeamCode))) {
                throw new SyncTraceAccessGuard.AccessDeniedException("You are not allowed to access this goal.");
            }
            return ResponseEntity.ok(goalService.getGoalComponents(goalId));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            HttpStatus status = "Goal not found".equals(e.getMessage()) ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN;
            return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch goal components: " + e.getMessage()));
        }
    }

    @PostMapping("/{goalId}/components")
    public ResponseEntity<?> addGoalComponents(@PathVariable Long goalId, @RequestBody Map<String, List<Long>> payload) {
        try {
            List<Long> componentIds = payload.get("componentIds");
            if (componentIds == null || componentIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "componentIds is required"));
            }
            goalService.addGoalComponents(goalId, componentIds);
            return ResponseEntity.ok(Map.of("message", "Components added successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to add components: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{goalId}/components/{componentId}")
    public ResponseEntity<?> removeGoalComponent(@PathVariable Long goalId, @PathVariable Long componentId) {
        try {
            goalService.removeGoalComponent(goalId, componentId);
            return ResponseEntity.ok(Map.of("message", "Component removed successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to remove component: " + e.getMessage()));
        }
    }
}
