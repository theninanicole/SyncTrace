package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.model.TeamRepository;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.service.SubmissionSyncService;
import com.ieee.evaluator.synctrace.model.GitHubRepositoryLink;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponentSummaryDTO;
import com.ieee.evaluator.synctrace.repository.GitHubRepositoryLinkRepository;
import com.ieee.evaluator.synctrace.service.ComponentCodeHelper;
import com.ieee.evaluator.synctrace.service.GitHubIngestionService;
import com.ieee.evaluator.synctrace.service.GitHubRepositoryLinkService;
import com.ieee.evaluator.synctrace.service.GitHubRepositoryLinkService.DuplicateRepositoryException;
import com.ieee.evaluator.synctrace.service.GitHubRepositoryLinkService.InvalidRepositoryException;
import com.ieee.evaluator.synctrace.service.GitHubRepositoryLinkService.NoSupportedFilesException;
import com.ieee.evaluator.synctrace.service.GitHubRepositoryLinkService.RepositoryImpact;
import com.ieee.evaluator.synctrace.service.GitHubRepositoryLinkService.RepositoryIngestionException;
import com.ieee.evaluator.synctrace.service.GitHubRepositoryLinkService.RepositoryNotFoundException;
import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/synctrace/github")
public class GitHubController {

    private final SubmissionSyncService submissionSyncService;
    private final GitHubIngestionService ingestionService;
    private final GitHubRepositoryLinkRepository repositoryLinkRepository;
    private final GitHubRepositoryLinkService repositoryLinkService;
    private final SyncTraceAccessGuard accessGuard;
    private final ProgressEmitter progressEmitter;

    public GitHubController(
            SubmissionSyncService submissionSyncService,
            GitHubIngestionService ingestionService,
            GitHubRepositoryLinkRepository repositoryLinkRepository,
            GitHubRepositoryLinkService repositoryLinkService,
            SyncTraceAccessGuard accessGuard,
            ProgressEmitter progressEmitter) {
        this.submissionSyncService = submissionSyncService;
        this.ingestionService = ingestionService;
        this.repositoryLinkRepository = repositoryLinkRepository;
        this.repositoryLinkService = repositoryLinkService;
        this.accessGuard = accessGuard;
        this.progressEmitter = progressEmitter;
    }

    @GetMapping("/teams")
    public ResponseEntity<?> getTeamRepositories() {
        try {
            List<TeamRepository> repositories = submissionSyncService.getTeamRepositories();
            String teamCode = accessGuard.resolveEffectiveTeamCode(null);
            if (teamCode != null && !teamCode.isBlank()) {
                repositories = repositories.stream()
                        .filter(repository -> teamCode.equalsIgnoreCase(repository.teamCode()))
                        .toList();
            }
            return ResponseEntity.ok(repositories);
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fetch team repositories: " + e.getMessage());
        }
    }

    @GetMapping("/repositories")
    public ResponseEntity<?> getLinkedGitHubRepositories(@RequestParam(required = false) String teamCode) {
        try {
            return ResponseEntity.ok(repositoryLinkService.list(teamCode));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fetch linked GitHub repositories: " + e.getMessage());
        }
    }

    @PostMapping({"/repositories", "/repositories/link"})
    public ResponseEntity<?> linkGitHubRepository(@RequestBody Map<String, String> payload) {
        try {
            String githubUrl = payload.get("githubUrl");
            if (githubUrl == null || githubUrl.isBlank()) {
                return error(HttpStatus.BAD_REQUEST, "githubUrl is required.");
            }
            GitHubRepositoryLink repository = repositoryLinkService.link(
                    payload.get("teamCode"), githubUrl, payload.get("role"));
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "message", "Repository linked successfully.",
                    "repository", repository));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (DuplicateRepositoryException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        } catch (InvalidRepositoryException | IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to link repository: " + e.getMessage());
        }
    }

    @PatchMapping("/repositories/{repositoryId}")
    public ResponseEntity<?> updateRepositoryRole(
            @PathVariable Long repositoryId, @RequestBody Map<String, String> payload) {
        try {
            return ResponseEntity.ok(repositoryLinkService.updateRole(
                    repositoryId, payload.get("teamCode"), payload.get("role")));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (RepositoryNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update repository role: " + e.getMessage());
        }
    }

    @GetMapping("/repositories/{repositoryId}/impact")
    public ResponseEntity<?> getRepositoryImpact(
            @PathVariable Long repositoryId, @RequestParam(required = false) String teamCode) {
        try {
            return ResponseEntity.ok(repositoryLinkService.getImpact(repositoryId, teamCode));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (RepositoryNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to inspect repository impact: " + e.getMessage());
        }
    }

    @DeleteMapping("/repositories/{repositoryId}")
    public ResponseEntity<?> unlinkGitHubRepository(
            @PathVariable Long repositoryId, @RequestParam(required = false) String teamCode) {
        try {
            repositoryLinkService.unlink(repositoryId, teamCode);
            return ResponseEntity.noContent().build();
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (RepositoryNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to unlink repository: " + e.getMessage());
        }
    }

    @PostMapping("/repositories/{repositoryId}/ingest")
    public ResponseEntity<?> ingestLinkedRepository(
            @PathVariable Long repositoryId, @RequestBody Map<String, String> payload) {
        return ingestLinkedRepositoryResult(repositoryId, payload.get("teamCode"), payload.get("sessionId"));
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingestRepository(@RequestBody Map<String, Object> payload) {
        String teamCode = stringValue(payload.get("teamCode"));
        String sessionId = stringValue(payload.get("sessionId"));
        if (teamCode == null || teamCode.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "teamCode is required.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "sessionId is required.");
        }

        Long repositoryId = longValue(payload.get("repositoryId"));
        if (repositoryId != null) {
            return ingestLinkedRepositoryResult(repositoryId, teamCode, sessionId);
        }
        if (Boolean.parseBoolean(stringValue(payload.get("all")))) {
            try {
                List<Map<String, Object>> results = repositoryLinkService.findAllForTeam(teamCode).stream()
                        .map(repository -> ingestOne(repository.getId(), teamCode, sessionId))
                        .toList();
                progressEmitter.complete(sessionId);
                return ResponseEntity.ok(Map.of("results", results));
            } catch (SyncTraceAccessGuard.AccessDeniedException e) {
                progressEmitter.error(sessionId, e.getMessage());
                return error(HttpStatus.FORBIDDEN, e.getMessage());
            }
        }

        return ingestUnlinkedRepository(
                teamCode, stringValue(payload.get("githubUrl")), sessionId);
    }

    private ResponseEntity<?> ingestLinkedRepositoryResult(Long repositoryId, String teamCode, String sessionId) {
        if (teamCode == null || teamCode.isBlank()) return error(HttpStatus.BAD_REQUEST, "teamCode is required.");
        if (sessionId == null || sessionId.isBlank()) return error(HttpStatus.BAD_REQUEST, "sessionId is required.");
        try {
            GitHubRepositoryLinkService.IngestionResult result =
                    repositoryLinkService.ingest(repositoryId, teamCode, sessionId);
            List<TraceComponentSummaryDTO> summaries = result.components().stream()
                    .map(this::toSummaryDTO).collect(Collectors.toList());
            return ResponseEntity.ok(Map.of(
                    "repository", result.repository(),
                    "components", summaries,
                    "count", summaries.size()));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (RepositoryNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (NoSupportedFilesException e) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (RepositoryIngestionException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().contains("GITHUB API RATE LIMIT")
                    ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
            return error(status, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "GitHub ingestion failed: " + e.getMessage());
        }
    }

    private Map<String, Object> ingestOne(Long repositoryId, String teamCode, String sessionId) {
        try {
            GitHubRepositoryLinkService.IngestionResult result =
                    repositoryLinkService.ingest(repositoryId, teamCode, sessionId, false);
            return Map.of(
                    "repositoryId", repositoryId,
                    "status", "INGESTED",
                    "count", result.components().size());
        } catch (Exception e) {
            return Map.of(
                    "repositoryId", repositoryId,
                    "status", "FAILED",
                    "error", e.getMessage() == null ? "GitHub ingestion failed." : e.getMessage());
        }
    }

    private ResponseEntity<?> ingestUnlinkedRepository(String requestedTeamCode, String githubUrl, String sessionId) {
        try {
            String teamCode = accessGuard.resolveEffectiveTeamCode(requestedTeamCode);
            if (teamCode == null || teamCode.isBlank()) return error(HttpStatus.BAD_REQUEST, "teamCode is required.");
            if (githubUrl == null || githubUrl.isBlank()) return error(HttpStatus.BAD_REQUEST, "githubUrl is required.");

            List<TraceComponent> components = ingestionService.ingestRepository(githubUrl, teamCode, sessionId);
            String[] parsedUrl = GitHubIngestionService.parseGitHubUrl(githubUrl);
            if (parsedUrl != null) {
                repositoryLinkRepository.findByOwnerAndRepo(parsedUrl[0], parsedUrl[1])
                        .filter(repository -> Boolean.TRUE.equals(repository.getActive()))
                        .ifPresent(repository -> {
                            repository.setLastIngestedAt(LocalDateTime.now());
                            repository.setIngestionStatus("INGESTED");
                            repository.setIngestedFileCount(components.size());
                            repository.setLastIngestError(null);
                            repositoryLinkRepository.save(repository);
                        });
            }
            List<TraceComponentSummaryDTO> summaries = components.stream()
                    .map(this::toSummaryDTO).collect(Collectors.toList());
            return ResponseEntity.ok(Map.of("components", summaries, "count", summaries.size()));
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return error(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalStateException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.BAD_GATEWAY, "GitHub ingestion failed: " + e.getMessage());
        }
    }

    private TraceComponentSummaryDTO toSummaryDTO(TraceComponent component) {
        return new TraceComponentSummaryDTO(
                component.getId(),
                component.getDocType(),
                component.getArtifactKind() != null
                        ? component.getArtifactKind()
                        : com.ieee.evaluator.synctrace.model.ArtifactKind.UNSPECIFIED,
                component.getName(),
                component.getContent(),
                ComponentCodeHelper.resolveDisplayCode(
                        component.getCodeName(), component.getName(), component.getContent()),
                component.getAiExtracted(),
                component.getSourceHistoryId(),
                component.getSourceType(),
                component.getSourceRef(),
                component.getSourceUrl(),
                component.getSourceRemoved(),
                component.getSourceCapturedAt(),
                component.getCreatedAt());
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
