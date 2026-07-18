package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.service.SyncTraceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/synctrace")
public class SyncTraceController {

    private final SyncTraceService syncTraceService;

    public SyncTraceController(SyncTraceService syncTraceService) {
        this.syncTraceService = syncTraceService;
    }

    @GetMapping("/goals")
    public ResponseEntity<?> getSmartGoals() {
        try {
            return ResponseEntity.ok(syncTraceService.getSmartGoals());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to load goals: " + e.getMessage()));
        }
    }

    @PostMapping("/goals")
    public ResponseEntity<?> createSmartGoal(@RequestBody Map<String, String> payload) {
        try {
            return ResponseEntity.ok(syncTraceService.createSmartGoal(payload.get("description")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to create goal: " + e.getMessage()));
        }
    }

    @DeleteMapping("/goals/{goalId}")
    public ResponseEntity<?> deleteSmartGoal(@PathVariable Long goalId) {
        try {
            return ResponseEntity.ok(syncTraceService.deleteSmartGoal(goalId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to delete goal: " + e.getMessage()));
        }
    }

    @GetMapping("/components")
    public ResponseEntity<?> getTraceComponents(
        @RequestParam(required = false) String docType,
        @RequestParam(required = false) String search
    ) {
        try {
            return ResponseEntity.ok(syncTraceService.getTraceComponents(docType, search));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to load components: " + e.getMessage()));
        }
    }

    @PostMapping("/components")
    public ResponseEntity<?> createTraceComponent(@RequestBody Map<String, String> payload) {
        try {
            return ResponseEntity.ok(syncTraceService.createTraceComponent(
                payload.get("docType"),
                payload.get("name"),
                payload.get("content")
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to create component: " + e.getMessage()));
        }
    }

    @PutMapping("/components/{componentId}/rename")
    public ResponseEntity<?> renameTraceComponent(
        @PathVariable Long componentId,
        @RequestBody Map<String, String> payload
    ) {
        try {
            return ResponseEntity.ok(syncTraceService.renameTraceComponent(componentId, payload.get("name")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to rename component: " + e.getMessage()));
        }
    }

    @DeleteMapping("/components/{componentId}")
    public ResponseEntity<?> deleteTraceComponent(@PathVariable Long componentId) {
        try {
            return ResponseEntity.ok(syncTraceService.deleteTraceComponent(componentId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to delete component: " + e.getMessage()));
        }
    }

    @GetMapping("/components/{componentId}")
    public ResponseEntity<?> getTraceComponent(@PathVariable Long componentId) {
        try {
            return ResponseEntity.ok(syncTraceService.getTraceComponent(componentId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to load component: " + e.getMessage()));
        }
    }

    @PostMapping("/components/extract")
    public ResponseEntity<?> extractTraceComponents(@RequestBody Map<String, Object> payload) {
        try {
            Long historyId = toLong(payload.get("historyId"));
            if (historyId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "historyId is required."));
            }
            return ResponseEntity.ok(syncTraceService.extractTraceComponents(historyId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to extract components: " + e.getMessage()));
        }
    }

    @GetMapping("/goals/{goalId}/components")
    public ResponseEntity<?> getGoalComponents(@PathVariable Long goalId) {
        try {
            return ResponseEntity.ok(syncTraceService.getGoalComponents(goalId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to load goal components: " + e.getMessage()));
        }
    }

    @GetMapping("/goal-components")
    public ResponseEntity<?> getAllGoalComponents() {
        try {
            return ResponseEntity.ok(syncTraceService.getAllGoalComponents());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to load goal-component map: " + e.getMessage()));
        }
    }

    @PostMapping("/goals/{goalId}/components")
    public ResponseEntity<?> addGoalComponents(
        @PathVariable Long goalId,
        @RequestBody Map<String, List<Long>> payload
    ) {
        try {
            return ResponseEntity.ok(syncTraceService.addGoalComponents(goalId, payload.get("componentIds")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to add mappings: " + e.getMessage()));
        }
    }

    @DeleteMapping("/goals/{goalId}/components/{componentId}")
    public ResponseEntity<?> removeGoalComponent(@PathVariable Long goalId, @PathVariable Long componentId) {
        try {
            return ResponseEntity.ok(syncTraceService.removeGoalComponent(goalId, componentId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to remove mapping: " + e.getMessage()));
        }
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
