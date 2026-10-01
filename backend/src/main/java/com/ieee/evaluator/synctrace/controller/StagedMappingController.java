package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.model.StagedTraceMapping;
import com.ieee.evaluator.synctrace.model.TraceabilityMappingActivity;
import com.ieee.evaluator.synctrace.repository.TraceabilityMappingActivityRepository;
import com.ieee.evaluator.synctrace.service.StagedMappingService;
import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The shared staged mapping workspace: one chain per team, seen by every user of that team. */
@RestController
@RequestMapping("/api/synctrace/staged-mappings")
public class StagedMappingController {

    private final StagedMappingService stagedMappingService;
    private final SyncTraceAccessGuard accessGuard;
    private final TraceabilityMappingActivityRepository mappingActivityRepository;

    public StagedMappingController(StagedMappingService stagedMappingService, SyncTraceAccessGuard accessGuard,
            TraceabilityMappingActivityRepository mappingActivityRepository) {
        this.stagedMappingService = stagedMappingService;
        this.accessGuard = accessGuard;
        this.mappingActivityRepository = mappingActivityRepository;
    }

    @ExceptionHandler(SyncTraceAccessGuard.AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(SyncTraceAccessGuard.AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String teamCode) {
        return ResponseEntity.ok(stagedMappingService.list(scopedTeamCode(teamCode)));
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody Map<String, Object> payload) {
        String teamCode = scopedTeamCode((String) payload.get("teamCode"));
        if (!(payload.get("mappings") instanceof List<?> rawMappings) || rawMappings.isEmpty()) {
            throw new IllegalArgumentException("mappings is required");
        }
        List<StagedMappingService.NewMapping> mappings = new ArrayList<>();
        for (Object raw : rawMappings) {
            if (!(raw instanceof Map<?, ?> m)) throw new IllegalArgumentException("Each mapping must be an object.");
            mappings.add(new StagedMappingService.NewMapping(
                (String) m.get("stage"), toLong(m.get("sourceId")), toLong(m.get("targetId"))));
        }
        List<StagedTraceMapping> rows = stagedMappingService.add(
            teamCode, mappings, accessGuard.currentUserEmail(), accessGuard.isStudent());
        return ResponseEntity.ok(rows);
    }

    /**
     * Removing a link takes effect everywhere at once: the team's goal links are re-derived
     * from the chain immediately, so results and analysis never show a removed mapping.
     */
    @DeleteMapping("/{mappingId}")
    public ResponseEntity<?> remove(@PathVariable Long mappingId, @RequestParam(required = false) String teamCode) {
        String team = scopedTeamCode(teamCode);
        stagedMappingService.remove(team, mappingId);
        StagedMappingService.SyncResult result = syncAndRecord(team, null);
        return ResponseEntity.ok(Map.of("message", "Mapping removed", "analysisCleared", result.analysisCleared()));
    }

    /** Save Mapping: publishes the team's chain to the goal links the results read. */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(@RequestBody Map<String, Object> payload) {
        String teamCode = scopedTeamCode((String) payload.get("teamCode"));
        StagedMappingService.SyncResult result = syncAndRecord(
            teamCode, payload.get("stage") != null ? String.valueOf(payload.get("stage")) : null);

        return ResponseEntity.ok(Map.of(
            "linkCount", result.linkCount(),
            "added", result.added(),
            "removed", result.removed(),
            "analysisCleared", result.analysisCleared()
        ));
    }

    private StagedMappingService.SyncResult syncAndRecord(String teamCode, String stage) {
        StagedMappingService.SyncResult result = stagedMappingService.sync(teamCode);
        // Activity marks when the team's links last changed; results treat any analysis older
        // than that as stale, so a save that changed nothing must not record one.
        if (result.added() + result.removed() == 0) return result;
        TraceabilityMappingActivity activity = new TraceabilityMappingActivity();
        activity.setTeamCode(teamCode);
        activity.setStage(stage);
        activity.setPerformedBy(accessGuard.currentUserEmail());
        activity.setMappingCount(result.linkCount());
        activity.setPerformedAt(LocalDateTime.now());
        mappingActivityRepository.save(activity);
        return result;
    }

    private String scopedTeamCode(String requestedTeamCode) {
        String teamCode = accessGuard.resolveEffectiveTeamCode(requestedTeamCode);
        if (teamCode == null || teamCode.isBlank()) {
            throw new IllegalArgumentException("teamCode is required");
        }
        return teamCode.trim();
    }

    private static Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) return Long.parseLong(s.trim());
        return null;
    }
}
