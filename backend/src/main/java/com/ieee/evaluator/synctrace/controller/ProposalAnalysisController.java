package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.service.ProposalAnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/synctrace/proposals")
public class ProposalAnalysisController {

    private final ProposalAnalysisService proposalAnalysisService;

    public ProposalAnalysisController(ProposalAnalysisService proposalAnalysisService) {
        this.proposalAnalysisService = proposalAnalysisService;
    }

    @PostMapping("/extract-goals")
    public ResponseEntity<?> extractSmartGoals(@RequestBody Map<String, String> payload) {
        try {
            String fileId = payload.get("fileId");
            String fileName = payload.get("fileName");
            String model = payload.get("model");
            String sessionId = payload.get("sessionId");

            if (fileId == null || fileId.isBlank() || fileName == null || fileName.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing fileId or fileName"));
            }

            List<Map<String, Object>> goals = proposalAnalysisService.extractSmartGoals(
                fileId, fileName, model, sessionId);
            
            return ResponseEntity.ok(Map.of("goals", goals, "count", goals.size()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Proposal analysis failed: " + e.getMessage()));
        }
    }
}
