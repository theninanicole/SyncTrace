package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.model.SmartGoal.GoalKind;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceabilityMappingActivity;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.service.SmartGoalService;
import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import com.ieee.evaluator.synctrace.service.TeamComponentResolverService;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import com.ieee.evaluator.synctrace.repository.TraceabilityMappingActivityRepository;
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
    private final TraceComponentRepository componentRepository;
    private final TeamComponentResolverService teamComponentResolver;
        private final TraceabilityMappingActivityRepository mappingActivityRepository;

    public SmartGoalController(SmartGoalService goalService, SmartGoalRepository goalRepository,
            SyncTraceAccessGuard accessGuard,
            TraceComponentRepository componentRepository, TeamComponentResolverService teamComponentResolver,
            TraceabilityMappingActivityRepository mappingActivityRepository) {
        this.goalService = goalService;
        this.goalRepository = goalRepository;
        this.accessGuard = accessGuard;
        this.componentRepository = componentRepository;
        this.teamComponentResolver = teamComponentResolver;
        this.mappingActivityRepository = mappingActivityRepository;
    }

    @GetMapping
    public ResponseEntity<?> getSmartGoals(@RequestParam(required = false) String teamCode) {
        try {
            String effectiveTeamCode = accessGuard.resolveEffectiveTeamCode(teamCode);
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
    public ResponseEntity<?> addGoalComponents(@PathVariable Long goalId, @RequestBody Map<String, Object> payload) {
        try {
            Object rawComponentIds = payload.get("componentIds");
            if (!(rawComponentIds instanceof List<?> rawIds) || rawIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "componentIds is required"));
            }
            List<Long> componentIds = rawIds.stream()
                    .map(value -> ((Number) value).longValue())
                    .toList();
            SmartGoal goal = goalRepository.findById(goalId)
                    .orElseThrow(() -> new SyncTraceAccessGuard.AccessDeniedException("Goal not found"));
            String effectiveTeamCode = accessGuard.resolveEffectiveTeamCode(goal.getTeamCode());
            if (accessGuard.isStudent()
                    && (goal.getTeamCode() == null || !effectiveTeamCode.equalsIgnoreCase(goal.getTeamCode()))) {
                throw new SyncTraceAccessGuard.AccessDeniedException("You are not allowed to update this goal.");
            }
            List<TraceComponent> components = componentRepository.findAllById(componentIds);
            if (components.size() != new java.util.HashSet<>(componentIds).size()
                    || (accessGuard.isStudent() && components.stream()
                    .anyMatch(component -> !teamComponentResolver.belongsToTeam(component, effectiveTeamCode)))) {
                throw new SyncTraceAccessGuard.AccessDeniedException("You are not allowed to map one or more components.");
            }
            goalService.addGoalComponents(goalId, componentIds);
            TraceabilityMappingActivity activity = new TraceabilityMappingActivity();
            activity.setTeamCode(effectiveTeamCode);
            activity.setStage(payload.get("stage") != null ? String.valueOf(payload.get("stage")) : null);
            activity.setPerformedBy(accessGuard.currentUserEmail());
            activity.setMappingCount(componentIds.size());
            activity.setPerformedAt(java.time.LocalDateTime.now());
            mappingActivityRepository.save(activity);
            return ResponseEntity.ok(Map.of("message", "Components added successfully"));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to add components: " + e.getMessage()));
        }
    }

    @GetMapping("/activity")
    public ResponseEntity<?> getLatestMappingActivity(@RequestParam String teamCode) {
        try {
            String effectiveTeamCode = accessGuard.resolveEffectiveTeamCode(teamCode);
            return ResponseEntity.ok(mappingActivityRepository
                    .findTopByTeamCodeIgnoreCaseOrderByPerformedAtDesc(effectiveTeamCode)
                    .orElse(null));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch mapping activity: " + e.getMessage()));
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
