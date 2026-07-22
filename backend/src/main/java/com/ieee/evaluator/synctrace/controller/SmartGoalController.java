package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.service.SmartGoalService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/synctrace/goals")
public class SmartGoalController {

    private final SmartGoalService goalService;

    public SmartGoalController(SmartGoalService goalService) {
        this.goalService = goalService;
    }

    @GetMapping
    public ResponseEntity<?> getSmartGoals() {
        try {
            return ResponseEntity.ok(goalService.getAllGoalsWithCategoryStatus());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch SMART goals: " + e.getMessage()));
        }
    }

    @GetMapping("/components")
    public ResponseEntity<?> getAllGoalComponents() {
        try {
            return ResponseEntity.ok(goalService.getAllGoalComponentsMap());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch all goal components: " + e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> createSmartGoal(@RequestBody Map<String, String> payload) {
        try {
            String description = payload.get("description");
            if (description == null || description.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Description is required"));
            }
            return ResponseEntity.ok(goalService.createGoal(description));
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
            return ResponseEntity.ok(goalService.getGoalComponents(goalId));
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
