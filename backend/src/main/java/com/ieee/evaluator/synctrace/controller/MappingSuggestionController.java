package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.model.MappingSuggestion;
import com.ieee.evaluator.synctrace.model.StagedTraceMapping;
import com.ieee.evaluator.synctrace.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/synctrace/mapping-suggestions")
public class MappingSuggestionController {
    private final MappingSuggestionService service;
    private final SyncTraceAccessGuard accessGuard;

    public MappingSuggestionController(MappingSuggestionService service, SyncTraceAccessGuard accessGuard) {
        this.service = service; this.accessGuard = accessGuard;
    }

    @ExceptionHandler(SyncTraceAccessGuard.AccessDeniedException.class)
    public ResponseEntity<?> forbidden(SyncTraceAccessGuard.AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }
    @ExceptionHandler(MappingSuggestionService.AiUnavailableException.class)
    public ResponseEntity<?> unavailable(MappingSuggestionService.AiUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("code", "AI_UNAVAILABLE", "error", e.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(service.generate(team(body.get("teamCode")), String.valueOf(body.get("stage")),
            (String) body.get("sessionId")));
    }
    @GetMapping
    public ResponseEntity<?> list(@RequestParam String teamCode, @RequestParam String stage) {
        return ResponseEntity.ok(service.pending(team(teamCode), stage));
    }
    @PatchMapping("/{id}")
    public ResponseEntity<?> decide(@PathVariable Long id, @RequestBody MappingSuggestionService.Decision decision,
            @RequestParam(required = false) String teamCode) {
        List<StagedTraceMapping> rows = service.decide(team(teamCode), id, decision,
            accessGuard.currentUserEmail(), accessGuard.isStudent());
        return ResponseEntity.ok(Map.of("suggestionId", id, "mappings", rows));
    }
    @PostMapping("/accept-all")
    public ResponseEntity<?> acceptAll(@RequestBody Map<String, Object> body) {
        int count = service.acceptAll(team(body.get("teamCode")), String.valueOf(body.get("stage")),
            accessGuard.currentUserEmail(), accessGuard.isStudent());
        return ResponseEntity.ok(Map.of("count", count));
    }
    @PostMapping("/discard")
    public ResponseEntity<?> discard(@RequestBody Map<String, Object> body) {
        service.discard(team(body.get("teamCode")), String.valueOf(body.get("stage")));
        return ResponseEntity.noContent().build();
    }
    private String team(Object requested) {
        String result = accessGuard.resolveEffectiveTeamCode(requested == null ? null : String.valueOf(requested));
        if (result == null || result.isBlank()) throw new IllegalArgumentException("teamCode is required");
        return result.trim();
    }
}
