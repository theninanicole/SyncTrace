package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.DiagnosticRecommendation;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.service.ContinuityGapDetectionService;
import com.ieee.evaluator.synctrace.service.ContinuityReadinessService;
import com.ieee.evaluator.synctrace.service.DiagnosticRecommendationService;
import com.ieee.evaluator.synctrace.service.SourceCodeAlignmentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/synctrace/continuity")
public class ContinuityController {

    private final SourceCodeAlignmentService alignmentService;
    private final ContinuityGapDetectionService gapDetectionService;
    private final ContinuityReadinessService readinessService;
    private final DiagnosticRecommendationService recommendationService;
    private final ContinuityFindingRepository findingRepository;

    public ContinuityController(
            SourceCodeAlignmentService alignmentService,
            ContinuityGapDetectionService gapDetectionService,
            ContinuityReadinessService readinessService,
            DiagnosticRecommendationService recommendationService,
            ContinuityFindingRepository findingRepository) {
        this.alignmentService = alignmentService;
        this.gapDetectionService = gapDetectionService;
        this.readinessService = readinessService;
        this.recommendationService = recommendationService;
        this.findingRepository = findingRepository;
    }

    @PostMapping("/align")
    public ResponseEntity<?> analyzeSourceCodeAlignment(@RequestBody Map<String, String> payload) {
        try {
            String teamCode = payload.get("teamCode");
            String model = payload.get("model");
            String sessionId = payload.get("sessionId");

            if (teamCode == null || teamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }

            List<ContinuityFinding> findings = alignmentService.analyzeAlignment(teamCode, model, sessionId);
            
            return ResponseEntity.ok(Map.of("findings", findings, "count", findings.size()));
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
            String teamCode = (String) payload.get("teamCode");
            Long goalId = payload.get("goalId") != null ? ((Number) payload.get("goalId")).longValue() : null;

            List<ContinuityFinding> findings = gapDetectionService.detectGaps(teamCode, goalId);
            
            return ResponseEntity.ok(Map.of("findings", findings, "count", findings.size()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Gap detection failed: " + e.getMessage()));
        }
    }

    @PostMapping("/recommendations")
    public ResponseEntity<?> generateRecommendations(@RequestBody Map<String, String> payload) {
        try {
            String teamCode = payload.get("teamCode");
            String model = payload.get("model");
            String sessionId = payload.get("sessionId");

            if (teamCode == null || teamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }

            List<DiagnosticRecommendation> recommendations = 
                recommendationService.generateRecommendations(teamCode, model, sessionId);
            
            return ResponseEntity.ok(Map.of("recommendations", recommendations, "count", recommendations.size()));
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
        try {
            List<ContinuityFinding> findings = findingRepository.findByTeamCodeOrderByDetectedAtDesc(teamCode);
            return ResponseEntity.ok(Map.of("findings", findings, "count", findings.size()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch findings: " + e.getMessage()));
        }
    }

    @GetMapping("/summary/{teamCode}")
    public ResponseEntity<?> getReadinessSummary(@PathVariable String teamCode) {
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
