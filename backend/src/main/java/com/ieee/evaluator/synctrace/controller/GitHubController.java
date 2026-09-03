package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.model.TeamRepository;
import com.ieee.evaluator.service.SubmissionSyncService;
import com.ieee.evaluator.synctrace.model.GitHubRepositoryLink;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponentSummaryDTO;
import com.ieee.evaluator.synctrace.repository.GitHubRepositoryLinkRepository;
import com.ieee.evaluator.synctrace.service.ComponentCodeHelper;
import com.ieee.evaluator.synctrace.service.GitHubIngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/synctrace/github")
public class GitHubController {

    private final SubmissionSyncService submissionSyncService;
    private final GitHubIngestionService ingestionService;
    private final GitHubRepositoryLinkRepository repositoryLinkRepository;

    public GitHubController(
            SubmissionSyncService submissionSyncService,
            GitHubIngestionService ingestionService,
            GitHubRepositoryLinkRepository repositoryLinkRepository) {
        this.submissionSyncService = submissionSyncService;
        this.ingestionService = ingestionService;
        this.repositoryLinkRepository = repositoryLinkRepository;
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

    @GetMapping("/repositories")
    public ResponseEntity<?> getLinkedGitHubRepositories() {
        try {
            List<GitHubRepositoryLink> repositories = repositoryLinkRepository.findAllByOrderByCreatedAtDesc();
            return ResponseEntity.ok(repositories);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch linked GitHub repositories: " + e.getMessage()));
        }
    }

    @PostMapping("/repositories/link")
    public ResponseEntity<?> linkGitHubRepository(@RequestBody Map<String, String> payload) {
        try {
            String githubUrl = payload.get("githubUrl");
            String teamCode = payload.get("teamCode");
            if (githubUrl == null || githubUrl.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "githubUrl is required"));
            }
            if (teamCode == null || teamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }

            String[] parts = parseGitHubUrl(githubUrl);
            if (parts == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid GitHub URL"));
            }

            String owner = parts[0];
            String repo = parts[1];
            String branch = parts.length > 2 ? parts[2] : "main";

            GitHubRepositoryLink repository = repositoryLinkRepository.findByOwnerAndRepo(owner, repo)
                    .orElseGet(GitHubRepositoryLink::new);

            repository.setOwner(owner);
            repository.setRepo(repo);
            repository.setRepositoryUrl(normalizeRepositoryUrl(owner, repo));
            repository.setDefaultBranch(branch == null || branch.isBlank() ? "main" : branch);
            repository.setActive(true);
            repository.setUpdatedAt(LocalDateTime.now());
            repository.setLastIngestedAt(repository.getLastIngestedAt());

            GitHubRepositoryLink saved = repositoryLinkRepository.save(repository);
            return ResponseEntity.ok(Map.of(
                    "message", "Repository linked successfully",
                    "repository", saved,
                    "teamCode", teamCode
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to link repository: " + e.getMessage()));
        }
    }

    @PostMapping("/repositories/{repositoryId}/ingest")
    public ResponseEntity<?> ingestLinkedRepository(
            @PathVariable Long repositoryId,
            @RequestBody Map<String, String> payload) {
        try {
            String teamCode = payload.get("teamCode");
            String sessionId = payload.get("sessionId");
            if (teamCode == null || teamCode.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "teamCode is required"));
            }
            if (sessionId == null || sessionId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
            }

            Optional<GitHubRepositoryLink> repositoryOpt = repositoryLinkRepository.findById(repositoryId);
            if (repositoryOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Repository not found"));
            }

            GitHubRepositoryLink repository = repositoryOpt.get();
            List<TraceComponent> components = ingestionService.ingestRepository(
                    repository.getRepositoryUrl(), teamCode, sessionId);

            repository.setLastIngestedAt(LocalDateTime.now());
            repositoryLinkRepository.save(repository);

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
            repositoryLinkRepository.findByOwnerAndRepo(parseGitHubUrl(githubUrl)[0], parseGitHubUrl(githubUrl)[1])
                    .ifPresent(repository -> {
                        repository.setLastIngestedAt(LocalDateTime.now());
                        repositoryLinkRepository.save(repository);
                    });

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
            component.getArtifactKind() != null
                ? component.getArtifactKind()
                : com.ieee.evaluator.synctrace.model.ArtifactKind.UNSPECIFIED,
            component.getName(),
            ComponentCodeHelper.resolveDisplayCode(
                component.getCodeName(), component.getName(), component.getContent()),
            component.getAiExtracted(),
            component.getSourceHistoryId(),
            component.getCreatedAt()
        );
    }

    private static String normalizeRepositoryUrl(String owner, String repo) {
        return "https://github.com/" + owner.trim() + "/" + repo.trim();
    }

    private static String[] parseGitHubUrl(String githubUrl) {
        if (githubUrl == null || githubUrl.isBlank()) {
            return null;
        }

        String normalized = githubUrl.trim();
        normalized = normalized.replace("https://github.com/", "")
                .replace("http://github.com/", "")
                .replace("https://www.github.com/", "")
                .replace("http://www.github.com/", "");

        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        String[] segments = normalized.split("/");
        if (segments.length < 2 || segments[0].isBlank() || segments[1].isBlank()) {
            return null;
        }

        if (segments.length >= 3 && ("tree".equalsIgnoreCase(segments[2]) || "blob".equalsIgnoreCase(segments[2]))) {
            return new String[] { segments[0], segments[1], segments[2] };
        }

        return new String[] { segments[0], segments[1], segments.length > 2 ? segments[2] : "main" };
    }
}
