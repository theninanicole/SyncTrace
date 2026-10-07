package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.service.ProposalAnalysisService;
import com.ieee.evaluator.synctrace.service.SmartGoalService;
import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/synctrace/proposals")
public class ProposalAnalysisController {

    private final ProposalAnalysisService proposalAnalysisService;
    private final SmartGoalService goalService;
    private final SyncTraceAccessGuard accessGuard;

    public ProposalAnalysisController(ProposalAnalysisService proposalAnalysisService,
            SmartGoalService goalService, SyncTraceAccessGuard accessGuard) {
        this.proposalAnalysisService = proposalAnalysisService;
        this.goalService = goalService;
        this.accessGuard = accessGuard;
    }

    /** Extracts a draft goal tree for review. Nothing is saved until /save-goals. */
    @PostMapping("/extract-goals")
    public ResponseEntity<?> extractSmartGoals(@RequestBody Map<String, String> payload) {
        try {
            String fileId = payload.get("fileId");
            String fileName = payload.get("fileName");
            String model = payload.get("model");
            String sessionId = payload.get("sessionId");
            // Students can only extract for their own team.
            String teamCode = accessGuard.resolveEffectiveTeamCode(payload.get("teamCode"));

            if (fileId == null || fileId.isBlank() || fileName == null || fileName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing fileId or fileName"));
            }

            List<Map<String, Object>> goals = proposalAnalysisService.extractSmartGoals(
                fileId, fileName, model, sessionId, teamCode);
            
            return ResponseEntity.ok(Map.of("goals", goals, "count", goals.size()));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Proposal analysis failed: " + e.getMessage()));
        }
    }

    /** Saves the goal tree a teacher or student reviewed and edited after extraction. */
    @PostMapping("/save-goals")
    public ResponseEntity<?> saveReviewedGoals(@RequestBody Map<String, Object> payload) {
        try {
            Object rawTeam = payload.get("teamCode");
            String teamCode = accessGuard.resolveEffectiveTeamCode(rawTeam != null ? String.valueOf(rawTeam) : null);
            if (!(payload.get("goals") instanceof List<?> rawGoals) || rawGoals.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "There are no SMART goals to save."));
            }
            List<Map<String, Object>> goals = rawGoals.stream()
                .filter(Map.class::isInstance)
                .map(node -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) node;
                    return map;
                })
                .toList();
            int created = goalService.saveReviewedGoals(teamCode, goals);
            return ResponseEntity.ok(Map.of("created", created));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to save SMART goals: " + e.getMessage()));
        }
    }
}
