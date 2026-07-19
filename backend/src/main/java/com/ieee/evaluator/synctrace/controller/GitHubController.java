package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.model.TeamRepository;
import com.ieee.evaluator.service.SubmissionSyncService;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponentSummaryDTO;
import com.ieee.evaluator.synctrace.service.GitHubIngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/synctrace/github")
public class GitHubController {

    private final SubmissionSyncService submissionSyncService;
    private final GitHubIngestionService ingestionService;

    public GitHubController(
            SubmissionSyncService submissionSyncService,
            GitHubIngestionService ingestionService) {
        this.submissionSyncService = submissionSyncService;
        this.ingestionService = ingestionService;
    }

    @GetMapping("/teams")
    public ResponseEntity<?> getTeamRepositories() {
        try {
            List<TeamRepository> repositories = submissionSyncService.getTeamRepositories();
            return ResponseEntity.ok(repositories);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch team repositories: " + e.getMessage()));
        }
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingestRepository(@RequestBody Map<String, String> payload) {
        try {
            String teamCode = payload.get("teamCode");
            String githubUrl = payload.get("githubUrl");
            String sessionId = payload.get("sessionId");

            if (teamCode == null || teamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }
            if (githubUrl == null || githubUrl.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "githubUrl is required"));
            }
            if (sessionId == null || sessionId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
            }

            List<TraceComponent> components = ingestionService.ingestRepository(githubUrl, teamCode, sessionId);
            
            // Convert to summary DTOs to avoid sending full content inline
            List<TraceComponentSummaryDTO> summaries = components.stream()
                .map(this::toSummaryDTO)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(Map.of("components", summaries, "count", summaries.size()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "GitHub ingestion failed: " + e.getMessage()));
        }
    }

    private TraceComponentSummaryDTO toSummaryDTO(TraceComponent component) {
        return new TraceComponentSummaryDTO(
            component.getId(),
            component.getDocType(),
            component.getName(),
            component.getAiExtracted(),
            component.getSourceHistoryId(),
            component.getCreatedAt()
        );
    }
}
