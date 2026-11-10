package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.GitHubRepositoryLink;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.repository.GitHubRepositoryLinkRepository;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.StagedTraceMappingRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
public class GitHubRepositoryLinkService {

    public static final List<String> ROLES = List.of("FRONTEND", "BACKEND", "MOBILE", "SHARED", "OTHER");

    public record RepositoryImpact(List<String> componentNames, long mappingCount) {}
    public record IngestionResult(GitHubRepositoryLink repository, List<TraceComponent> components) {}

    public static class InvalidRepositoryException extends RuntimeException {
        public InvalidRepositoryException(String message) {
            super(message);
        }
    }

    public static class DuplicateRepositoryException extends RuntimeException {
        public DuplicateRepositoryException(String message) {
            super(message);
        }
    }

    public static class RepositoryNotFoundException extends RuntimeException {
        public RepositoryNotFoundException() {
            super("Repository not found.");
        }
    }

    public static class NoSupportedFilesException extends RuntimeException {
        public NoSupportedFilesException() {
            super("No supported source files could be extracted from this repository.");
        }
    }

    public static class RepositoryIngestionException extends RuntimeException {
        public RepositoryIngestionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final GitHubRepositoryLinkRepository repositoryLinkRepository;
    private final TraceComponentRepository componentRepository;
    private final StagedTraceMappingRepository stagedMappingRepository;
    private final GoalComponentMappingRepository goalMappingRepository;
    private final GitHubIngestionService ingestionService;
    private final SyncTraceAccessGuard accessGuard;

    public GitHubRepositoryLinkService(
            GitHubRepositoryLinkRepository repositoryLinkRepository,
            TraceComponentRepository componentRepository,
            StagedTraceMappingRepository stagedMappingRepository,
            GoalComponentMappingRepository goalMappingRepository,
            GitHubIngestionService ingestionService,
            SyncTraceAccessGuard accessGuard) {
        this.repositoryLinkRepository = repositoryLinkRepository;
        this.componentRepository = componentRepository;
        this.stagedMappingRepository = stagedMappingRepository;
        this.goalMappingRepository = goalMappingRepository;
        this.ingestionService = ingestionService;
        this.accessGuard = accessGuard;
    }

    public List<GitHubRepositoryLink> list(String requestedTeamCode) {
        String teamCode = effectiveTeamCode(requestedTeamCode);
        return teamCode == null
                ? repositoryLinkRepository.findAllByActiveTrueOrderByCreatedAtDesc()
                : repositoryLinkRepository.findAllByTeamCodeIgnoreCaseAndActiveTrueOrderByCreatedAtDesc(teamCode);
    }

    public GitHubRepositoryLink link(String requestedTeamCode, String githubUrl, String requestedRole) {
        String teamCode = requiredTeamCode(requestedTeamCode);
        String[] parts = GitHubIngestionService.parseGitHubUrl(githubUrl);
        if (parts == null) {
            throw new InvalidRepositoryException("Enter a valid HTTPS GitHub repository URL.");
        }

        String branch;
        try {
            branch = ingestionService.resolveRepositoryBranch(parts[0], parts[1], parts[2]);
        } catch (Exception e) {
            throw new InvalidRepositoryException("GitHub repository could not be accessed: " + e.getMessage());
        }

        String role = normalizeRole(requestedRole);
        var existing = repositoryLinkRepository.findByOwnerAndRepo(parts[0], parts[1]);
        GitHubRepositoryLink repository;
        if (existing.isPresent()) {
            repository = existing.get();
            if (Boolean.TRUE.equals(repository.getActive())) {
                throw new DuplicateRepositoryException("This repository is already linked.");
            }
            if (repository.getTeamCode() != null
                    && !repository.getTeamCode().equalsIgnoreCase(teamCode)) {
                throw new DuplicateRepositoryException("This repository is already linked to another team.");
            }
        } else {
            repository = new GitHubRepositoryLink();
        }

        repository.setOwner(parts[0]);
        repository.setRepo(parts[1]);
        repository.setTeamCode(teamCode);
        repository.setRole(role);
        repository.setRepositoryUrl("https://github.com/" + parts[0] + "/" + parts[1]);
        repository.setDefaultBranch(branch);
        repository.setActive(true);
        repository.setIngestionStatus("NOT_INGESTED");
        repository.setIngestedFileCount(0);
        repository.setLastIngestError(null);
        repository.setUpdatedAt(LocalDateTime.now());
        return repositoryLinkRepository.save(repository);
    }

    public GitHubRepositoryLink updateRole(Long repositoryId, String requestedTeamCode, String requestedRole) {
        GitHubRepositoryLink repository = requireRepository(repositoryId, requestedTeamCode);
        repository.setRole(normalizeRole(requestedRole));
        return repositoryLinkRepository.save(repository);
    }

    public RepositoryImpact getImpact(Long repositoryId, String requestedTeamCode) {
        GitHubRepositoryLink repository = requireRepository(repositoryId, requestedTeamCode);
        List<TraceComponent> components = findRepositoryComponents(repository);
        List<Long> componentIds = components.stream().map(TraceComponent::getId).toList();
        if (componentIds.isEmpty()) return new RepositoryImpact(List.of(), 0);

        long stagedMappings = stagedMappingRepository.countByTargetIdIn(componentIds)
                + stagedMappingRepository.countBySourceIdInAndStageNot(componentIds, "PROPOSAL_SRS");
        long goalMappings = goalMappingRepository.countByComponentIdIn(componentIds);
        return new RepositoryImpact(
                components.stream().map(TraceComponent::getName).sorted().toList(),
                stagedMappings + goalMappings);
    }

    @Transactional
    public RepositoryImpact unlink(Long repositoryId, String requestedTeamCode) {
        GitHubRepositoryLink repository = requireRepository(repositoryId, requestedTeamCode);
        List<TraceComponent> components = findRepositoryComponents(repository);
        components.forEach(component -> component.setSourceRemoved(true));
        componentRepository.saveAll(components);
        repository.setActive(false);
        repositoryLinkRepository.save(repository);

        List<Long> componentIds = components.stream().map(TraceComponent::getId).toList();
        long mappingCount = componentIds.isEmpty() ? 0
                : stagedMappingRepository.countByTargetIdIn(componentIds)
                    + stagedMappingRepository.countBySourceIdInAndStageNot(componentIds, "PROPOSAL_SRS")
                    + goalMappingRepository.countByComponentIdIn(componentIds);
        log.info("Unlinked GitHub repository {}/{} for team {}; flagged {} components and {} mappings",
                repository.getOwner(), repository.getRepo(), repository.getTeamCode(),
                components.size(), mappingCount);
        return new RepositoryImpact(
                components.stream().map(TraceComponent::getName).sorted().toList(),
                mappingCount);
    }

    public IngestionResult ingest(Long repositoryId, String requestedTeamCode, String sessionId) {
        return ingest(repositoryId, requestedTeamCode, sessionId, true);
    }

    public IngestionResult ingest(
            Long repositoryId, String requestedTeamCode, String sessionId, boolean completeProgress) {
        GitHubRepositoryLink repository = requireRepository(repositoryId, requestedTeamCode);
        repository.setIngestionStatus("INGESTING");
        repository.setLastIngestError(null);
        repositoryLinkRepository.save(repository);

        String ingestionUrl = repository.getRepositoryUrl() + "/tree/" + repository.getDefaultBranch();
        try {
            List<TraceComponent> components = ingestionService.ingestRepository(
                    ingestionUrl, repository.getTeamCode(), sessionId, completeProgress);
            if (components.isEmpty()) {
                throw new NoSupportedFilesException();
            }
            repository.setIngestionStatus("INGESTED");
            repository.setIngestedFileCount(components.size());
            repository.setLastIngestedAt(LocalDateTime.now());
            repository.setLastIngestError(null);
            repositoryLinkRepository.save(repository);
            return new IngestionResult(repository, components);
        } catch (Exception e) {
            repository.setIngestionStatus("FAILED");
            repository.setLastIngestError(messageFor(e));
            repositoryLinkRepository.save(repository);
            if (e instanceof NoSupportedFilesException) throw (NoSupportedFilesException) e;
            log.warn("GitHub ingestion failed for {}/{} (team {})", repository.getOwner(),
                    repository.getRepo(), repository.getTeamCode(), e);
            throw new RepositoryIngestionException(messageFor(e), e);
        }
    }

    public List<GitHubRepositoryLink> findAllForTeam(String requestedTeamCode) {
        return repositoryLinkRepository.findAllByTeamCodeIgnoreCaseAndActiveTrueOrderByCreatedAtDesc(
                requiredTeamCode(requestedTeamCode));
    }

    private GitHubRepositoryLink requireRepository(Long repositoryId, String requestedTeamCode) {
        String teamCode = requiredTeamCode(requestedTeamCode);
        GitHubRepositoryLink repository = repositoryLinkRepository.findById(repositoryId)
                .filter(link -> Boolean.TRUE.equals(link.getActive()))
                .filter(link -> link.getTeamCode() != null && link.getTeamCode().equalsIgnoreCase(teamCode))
                .orElseThrow(RepositoryNotFoundException::new);
        return repository;
    }

    private List<TraceComponent> findRepositoryComponents(GitHubRepositoryLink repository) {
        return componentRepository.findAllBySourceTypeAndSourceRefStartingWithIgnoreCase(
                "GITHUB", repository.getOwner() + "/" + repository.getRepo() + "@");
    }

    private String effectiveTeamCode(String requestedTeamCode) {
        String teamCode = accessGuard.resolveEffectiveTeamCode(requestedTeamCode);
        return teamCode == null || teamCode.isBlank() ? null : teamCode.trim();
    }

    private String requiredTeamCode(String requestedTeamCode) {
        String teamCode = effectiveTeamCode(requestedTeamCode);
        if (teamCode == null) throw new IllegalArgumentException("teamCode is required.");
        return teamCode;
    }

    private String normalizeRole(String role) {
        String normalized = role == null || role.isBlank() ? "OTHER" : role.trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(normalized)) {
            throw new IllegalArgumentException("role must be one of: " + String.join(", ", ROLES));
        }
        return normalized;
    }

    private String messageFor(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? "GitHub ingestion failed."
                : e.getMessage();
    }
}
