package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.ContinuityAnalysisRun;
import com.ieee.evaluator.synctrace.model.DiagnosticRecommendation;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.ContinuityAnalysisRunRepository;
import com.ieee.evaluator.synctrace.service.ContinuityGapDetectionService;
import com.ieee.evaluator.synctrace.service.ContinuityReadinessService;
import com.ieee.evaluator.synctrace.service.DiagnosticRecommendationService;
import com.ieee.evaluator.synctrace.service.SourceCodeAlignmentService;
import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Collections;

@RestController
@RequestMapping("/api/synctrace/continuity")
public class ContinuityController {

    private final SourceCodeAlignmentService alignmentService;
    private final ContinuityGapDetectionService gapDetectionService;
    private final ContinuityReadinessService readinessService;
    private final DiagnosticRecommendationService recommendationService;
    private final ContinuityFindingRepository findingRepository;
    private final ContinuityAnalysisRunRepository analysisRunRepository;
    private final SyncTraceAccessGuard accessGuard;

    public ContinuityController(
            SourceCodeAlignmentService alignmentService,
            ContinuityGapDetectionService gapDetectionService,
            ContinuityReadinessService readinessService,
            DiagnosticRecommendationService recommendationService,
            ContinuityFindingRepository findingRepository,
            ContinuityAnalysisRunRepository analysisRunRepository,
            SyncTraceAccessGuard accessGuard) {
        this.alignmentService = alignmentService;
        this.gapDetectionService = gapDetectionService;
        this.readinessService = readinessService;
        this.recommendationService = recommendationService;
        this.findingRepository = findingRepository;
        this.analysisRunRepository = analysisRunRepository;
        this.accessGuard = accessGuard;
    }

    // Students can run and read analysis, but only ever for their own team.
    @ExceptionHandler(SyncTraceAccessGuard.AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(SyncTraceAccessGuard.AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    private String scopedTeamCode(String requestedTeamCode) {
        return accessGuard.resolveEffectiveTeamCode(requestedTeamCode);
    }

    @PostMapping("/align")
    public ResponseEntity<?> analyzeSourceCodeAlignment(@RequestBody Map<String, String> payload) {
        try {
            String teamCode = scopedTeamCode(payload.get("teamCode"));
            String model = payload.get("model");
            String sessionId = payload.get("sessionId");

            if (teamCode == null || teamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }

            List<ContinuityFinding> findings = alignmentService.analyzeAlignment(teamCode, model, sessionId);
            
            return ResponseEntity.ok(Map.of("findings", findings, "count", findings.size()));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return handleAccessDenied(e);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Alignment analysis failed: " + e.getMessage()));
        }
    }

    @PostMapping("/detect-gaps")
    public ResponseEntity<?> detectContinuityGaps(@RequestBody Map<String, Object> payload) {
        try {
            String teamCode = scopedTeamCode((String) payload.get("teamCode"));
            Long goalId = payload.get("goalId") != null ? ((Number) payload.get("goalId")).longValue() : null;

            List<ContinuityFinding> findings = gapDetectionService.detectGaps(teamCode, goalId);
            
            return ResponseEntity.ok(Map.of("findings", findings, "count", findings.size()));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return handleAccessDenied(e);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Gap detection failed: " + e.getMessage()));
        }
    }

    @PostMapping("/recommendations")
    public ResponseEntity<?> generateRecommendations(@RequestBody Map<String, String> payload) {
        try {
            String teamCode = scopedTeamCode(payload.get("teamCode"));
            String model = payload.get("model");
            String sessionId = payload.get("sessionId");

            if (teamCode == null || teamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }

            List<DiagnosticRecommendation> recommendations = 
                recommendationService.generateRecommendations(teamCode, model, sessionId);
            
            return ResponseEntity.ok(Map.of("recommendations", recommendations, "count", recommendations.size()));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return handleAccessDenied(e);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Recommendation generation failed: " + e.getMessage()));
        }
    }

    @GetMapping("/findings/{teamCode}")
    public ResponseEntity<?> getFindings(@PathVariable String teamCode) {
        teamCode = scopedTeamCode(teamCode);
        try {
            List<ContinuityFinding> findings = findingRepository.findByTeamCodeOrderByDetectedAtDesc(teamCode);
            return ResponseEntity.ok(Map.of("findings", findings, "count", findings.size()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch findings: " + e.getMessage()));
        }
    }

    @GetMapping("/analysis-status/{teamCode}")
    public ResponseEntity<?> getAnalysisStatus(@PathVariable String teamCode) {
        teamCode = scopedTeamCode(teamCode);
        try {
            if (teamCode == null || teamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }

            return ResponseEntity.ok(analysisRunRepository.findByTeamCodeIgnoreCase(teamCode.trim())
                    .map(ContinuityAnalysisRun::getLastAnalyzedAt)
                    .map(lastAnalyzedAt -> Map.of("lastAnalyzedAt", lastAnalyzedAt))
                    .orElseGet(() -> Collections.singletonMap("lastAnalyzedAt", null)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch analysis status: " + e.getMessage()));
        }
    }

    @GetMapping("/recommendations/{teamCode}")
    public ResponseEntity<?> getRecommendations(@PathVariable String teamCode) {
        teamCode = scopedTeamCode(teamCode);
        try {
            if (teamCode == null || teamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }

            List<DiagnosticRecommendation> recommendations = recommendationService.getRecommendationsForTeam(teamCode);
            return ResponseEntity.ok(Map.of("recommendations", recommendations, "count", recommendations.size()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch recommendations: " + e.getMessage()));
        }
    }

    @GetMapping("/summary/{teamCode}")
    public ResponseEntity<?> getReadinessSummary(@PathVariable String teamCode) {
        teamCode = scopedTeamCode(teamCode);
        try {
            if (teamCode == null || teamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }

            return ResponseEntity.ok(readinessService.getTeamReadinessSummary(teamCode));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to build readiness summary: " + e.getMessage()));
        }
    }
}
