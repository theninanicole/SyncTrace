package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.synctrace.service.ProposalAnalysisService;
import com.ieee.evaluator.synctrace.service.SmartGoalService;
import com.ieee.evaluator.synctrace.service.SmartGoalsSection;
import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import com.ieee.evaluator.synctrace.service.TeamCodeResolver;
import com.ieee.evaluator.synctrace.validation.model.ValidationOperation;
import com.ieee.evaluator.synctrace.validation.service.ValidationContext;
import com.ieee.evaluator.synctrace.validation.service.ValidationTiming;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/synctrace/proposals")
public class ProposalAnalysisController {

    private final ProposalAnalysisService proposalAnalysisService;
    private final SmartGoalService goalService;
    private final SyncTraceAccessGuard accessGuard;
    private final EvaluationHistoryRepository historyRepository;

    public ProposalAnalysisController(ProposalAnalysisService proposalAnalysisService,
            SmartGoalService goalService, SyncTraceAccessGuard accessGuard,
            EvaluationHistoryRepository historyRepository) {
        this.proposalAnalysisService = proposalAnalysisService;
        this.goalService = goalService;
        this.accessGuard = accessGuard;
        this.historyRepository = historyRepository;
    }

    private ValidationTiming validationTiming = ValidationTiming.passThrough();

    @Autowired(required = false)
    void setValidationTiming(ValidationTiming validationTiming) {
        this.validationTiming = validationTiming;
    }

    /**
     * Extracts a draft goal tree for review. Nothing is saved until /save-goals.
     *
     * <p>With {@code historyId}, the goals come from that evaluation report's SMART Goals section;
     * only a report without that section is extracted from the document by AI. Both paths are
     * one M1 run.
     */
    @PostMapping("/extract-goals")
    public ResponseEntity<?> extractSmartGoals(@RequestBody Map<String, Object> payload) {
        try {
            String fileId = text(payload.get("fileId"));
            String fileName = text(payload.get("fileName"));
            String model = text(payload.get("model"));
            String sessionId = text(payload.get("sessionId"));
            Long historyId = historyId(payload.get("historyId"));
            // Students can only extract for their own team.
            String teamCode = accessGuard.resolveEffectiveTeamCode(text(payload.get("teamCode")));

            if (fileId == null || fileId.isBlank() || fileName == null || fileName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing fileId or fileName"));
            }

            // M1: reading the report's SMART Goals section, or the Drive download, rendering,
            // AI call and retries, until the drafts are ready.
            String[] source = { "ai" };
            List<Map<String, Object>> goals = validationTiming.time(ValidationOperation.GOAL_EXTRACTION,
                ValidationContext.forTeam(teamCode),
                () -> {
                    Optional<List<Map<String, Object>>> fromReport = readReportGoals(historyId, teamCode);
                    if (fromReport.isPresent()) {
                        source[0] = "report";
                        return fromReport.get();
                    }
                    return proposalAnalysisService.extractSmartGoals(fileId, fileName, model, sessionId, teamCode);
                },
                List::size);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("goals", goals);
            body.put("count", goals.size());
            body.put("source", source[0]);
            return ResponseEntity.ok(body);
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

    /** The report's SMART Goals section, or empty when there is no report or no such section. */
    private Optional<List<Map<String, Object>>> readReportGoals(Long historyId, String teamCode) {
        if (historyId == null) return Optional.empty();
        EvaluationHistory history = historyRepository.findById(historyId).orElse(null);
        if (history == null) return Optional.empty();
        if (accessGuard.isStudent() && teamCode != null
                && !teamCode.equalsIgnoreCase(TeamCodeResolver.extractTeamCode(history.getFileName()))) {
            throw new SyncTraceAccessGuard.AccessDeniedException("You can only extract your own team's SMART goals.");
        }
        return SmartGoalsSection.read(history.getEvaluationResult());
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long historyId(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String raw && !raw.isBlank()) {
            try {
                return Long.valueOf(raw.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
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
