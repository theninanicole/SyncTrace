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
import com.ieee.evaluator.synctrace.service.TeamAnalysisLock;
import com.ieee.evaluator.synctrace.validation.model.ValidationEvent;
import com.ieee.evaluator.synctrace.validation.model.ValidationOperation;
import com.ieee.evaluator.synctrace.validation.service.ValidationContext;
import com.ieee.evaluator.synctrace.validation.service.ValidationRecorder;
import com.ieee.evaluator.synctrace.validation.service.ValidationTiming;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.UUID;

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
    private final TeamAnalysisLock analysisLock;

    public ContinuityController(
            SourceCodeAlignmentService alignmentService,
            ContinuityGapDetectionService gapDetectionService,
            ContinuityReadinessService readinessService,
            DiagnosticRecommendationService recommendationService,
            ContinuityFindingRepository findingRepository,
            ContinuityAnalysisRunRepository analysisRunRepository,
            SyncTraceAccessGuard accessGuard,
            TeamAnalysisLock analysisLock) {
        this.alignmentService = alignmentService;
        this.gapDetectionService = gapDetectionService;
        this.readinessService = readinessService;
        this.recommendationService = recommendationService;
        this.findingRepository = findingRepository;
        this.analysisRunRepository = analysisRunRepository;
        this.accessGuard = accessGuard;
        this.analysisLock = analysisLock;
    }

    private ValidationTiming validationTiming = ValidationTiming.passThrough();
    private ValidationRecorder validationRecorder;

    @Autowired(required = false)
    void setValidationMetrics(ValidationTiming validationTiming, ValidationRecorder validationRecorder) {
        this.validationTiming = validationTiming;
        this.validationRecorder = validationRecorder;
    }

    private void recordCoverage(String teamCode, String analysisRunId, String status) {
        if (validationRecorder != null) validationRecorder.recordCoverage(teamCode, analysisRunId, status);
    }

    /** The client sends one analysisRunId for both steps of a run; without one, each request is its own run. */
    private static String runId(Object requested) {
        String value = requested == null ? null : String.valueOf(requested).trim();
        if (value == null || value.isEmpty()) return UUID.randomUUID().toString();
        return value.length() > 64 ? value.substring(0, 64) : value;
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

            // Recorded under M6 as ALIGNMENT, but not counted toward the M6 target.
            List<ContinuityFinding> findings = validationTiming.time(ValidationOperation.ALIGNMENT,
                ValidationContext.forTeam(teamCode).analysisRunId(runId(payload.get("analysisRunId"))),
                () -> alignmentService.analyzeAlignment(teamCode, model, sessionId), List::size);

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

            // Teachers and students can both run analysis now; serialize per team so two
            // overlapping runs can't both insert and leave duplicate findings.
            final String scopedTeam = teamCode;
            // M6 step 1: includes waiting for the team's analysis lock.
            List<ContinuityFinding> findings = validationTiming.time(ValidationOperation.GAP_DETECTION,
                    ValidationContext.forTeam(scopedTeam).analysisRunId(runId(payload.get("analysisRunId"))),
                    () -> analysisLock.run(scopedTeam, () -> gapDetectionService.detectGaps(scopedTeam, goalId)),
                    List::size);

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

            // M6 step 2 (AI) and, whatever its outcome, the M7 coverage snapshot of this run.
            ValidationContext metric = ValidationContext.forTeam(teamCode)
                .analysisRunId(runId(payload.get("analysisRunId")));
            List<DiagnosticRecommendation> recommendations;
            try {
                recommendations = validationTiming.time(ValidationOperation.RECOMMENDATIONS, metric,
                    () -> recommendationService.generateRecommendations(teamCode, model, sessionId), List::size);
            } catch (Exception e) {
                String status = ValidationTiming.statusOf(e, metric);
                if (status != null) recordCoverage(teamCode, metric.analysisRunId(), status);
                throw e;
            }
            recordCoverage(teamCode, metric.analysisRunId(), ValidationEvent.SUCCESS);

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
            List<ContinuityFinding> findings = findingRepository.findByTeamCodeIgnoreCaseOrderByDetectedAtDescIdAsc(teamCode.trim());
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
