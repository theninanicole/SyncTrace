package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.model.MappingSuggestion;
import com.ieee.evaluator.synctrace.model.StagedTraceMapping;
import com.ieee.evaluator.synctrace.service.*;
import com.ieee.evaluator.synctrace.validation.service.ValidationRecorder;
import org.springframework.beans.factory.annotation.Autowired;
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

    private ValidationRecorder validationRecorder;

    @Autowired(required = false)
    void setValidationRecorder(ValidationRecorder validationRecorder) {
        this.validationRecorder = validationRecorder;
    }

    /** M5 rows changed; refresh the team's stored validation summary (off the request path). */
    private void teamChanged(String team) {
        if (validationRecorder != null) validationRecorder.teamChanged(team);
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
        String team = team(body.get("teamCode"));
        MappingSuggestionService.GenerationResult result =
            service.generate(team, String.valueOf(body.get("stage")), (String) body.get("sessionId"));
        teamChanged(team);
        return ResponseEntity.ok(result);
    }
    @GetMapping
    public ResponseEntity<?> list(@RequestParam String teamCode, @RequestParam String stage) {
        return ResponseEntity.ok(service.pending(team(teamCode), stage));
    }
    @PatchMapping("/{id}")
    public ResponseEntity<?> decide(@PathVariable Long id, @RequestBody MappingSuggestionService.Decision decision,
            @RequestParam(required = false) String teamCode) {
        String team = team(teamCode);
        List<StagedTraceMapping> rows = service.decide(team, id, decision,
            accessGuard.currentUserEmail(), accessGuard.currentUserId(), accessGuard.isStudent(),
            MappingSuggestion.METHOD_SINGLE);
        if (validationRecorder != null) {
            rows.stream().map(StagedTraceMapping::getStage).distinct()
                .forEach(stage -> validationRecorder.linkChanged(team, stage));
        }
        teamChanged(team);
        return ResponseEntity.ok(Map.of("suggestionId", id, "mappings", rows));
    }
    @PostMapping("/accept-all")
    public ResponseEntity<?> acceptAll(@RequestBody Map<String, Object> body) {
        String team = team(body.get("teamCode"));
        String stage = String.valueOf(body.get("stage"));
        int count = service.acceptAll(team, stage,
            accessGuard.currentUserEmail(), accessGuard.currentUserId(), accessGuard.isStudent());
        if (validationRecorder != null && count > 0) validationRecorder.linkChanged(team, stage);
        teamChanged(team);
        return ResponseEntity.ok(Map.of("count", count));
    }
    @PostMapping("/discard")
    public ResponseEntity<?> discard(@RequestBody Map<String, Object> body) {
        String team = team(body.get("teamCode"));
        service.discard(team, String.valueOf(body.get("stage")));
        teamChanged(team);
        return ResponseEntity.noContent().build();
    }
    private String team(Object requested) {
        String result = accessGuard.resolveEffectiveTeamCode(requested == null ? null : String.valueOf(requested));
        if (result == null || result.isBlank()) throw new IllegalArgumentException("teamCode is required");
        return result.trim();
    }
}
