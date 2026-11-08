package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.GitHubRepositoryLink;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.repository.GitHubRepositoryLinkRepository;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.StagedTraceMappingRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubRepositoryLinkServiceTest {

    @Mock private GitHubRepositoryLinkRepository repositoryLinkRepository;
    @Mock private TraceComponentRepository componentRepository;
    @Mock private StagedTraceMappingRepository stagedMappingRepository;
    @Mock private GoalComponentMappingRepository goalMappingRepository;
    @Mock private GitHubIngestionService ingestionService;
    @Mock private SyncTraceAccessGuard accessGuard;

    private GitHubRepositoryLinkService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new GitHubRepositoryLinkService(
                repositoryLinkRepository, componentRepository, stagedMappingRepository,
                goalMappingRepository, ingestionService, accessGuard);
        when(accessGuard.resolveEffectiveTeamCode(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(ingestionService.resolveRepositoryBranch(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String requestedBranch = invocation.getArgument(2);
                    return requestedBranch == null ? "main" : requestedBranch;
                });
        lenient().when(repositoryLinkRepository.save(any(GitHubRepositoryLink.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void linkPersistsValidatedRepositoryAndRoleForRequestedTeam() {
        when(repositoryLinkRepository.findByOwnerAndRepo("owner", "repo")).thenReturn(Optional.empty());

        GitHubRepositoryLink linked = service.link(
                "TEAM-1", "https://github.com/owner/repo/tree/feature/login", "frontend");

        assertEquals("TEAM-1", linked.getTeamCode());
        assertEquals("FRONTEND", linked.getRole());
        assertEquals("feature/login", linked.getDefaultBranch());
        assertEquals("NOT_INGESTED", linked.getIngestionStatus());
        verify(repositoryLinkRepository).save(linked);
    }

    @Test
    void linkingAnAlreadyActiveRepositoryIsRejected() {
        GitHubRepositoryLink existing = new GitHubRepositoryLink();
        existing.setActive(true);
        when(repositoryLinkRepository.findByOwnerAndRepo("owner", "repo")).thenReturn(Optional.of(existing));

        assertThrows(GitHubRepositoryLinkService.DuplicateRepositoryException.class,
                () -> service.link("TEAM-1", "https://github.com/owner/repo", "OTHER"));
        verify(repositoryLinkRepository, never()).save(any(GitHubRepositoryLink.class));
    }

    @Test
    void invalidGithubUrlsAreRejectedBeforeLinking() throws Exception {
        assertThrows(GitHubRepositoryLinkService.InvalidRepositoryException.class,
                () -> service.link("TEAM-1", "https://example.com/github.com/owner/repo", "OTHER"));
        verify(ingestionService, never()).resolveRepositoryBranch(anyString(), anyString(), any());
    }

    @Test
    void unlinkMarksComponentsAsSourceRemovedWithoutDeletingTheirMappings() {
        GitHubRepositoryLink repository = linkedRepository();
        TraceComponent component = new TraceComponent();
        component.setId(5L);
        component.setName("TEAM-1 - src/App.java");
        when(repositoryLinkRepository.findById(7L)).thenReturn(Optional.of(repository));
        when(componentRepository.findAllBySourceTypeAndSourceRefStartingWithIgnoreCase(
                "GITHUB", "owner/repo@")).thenReturn(List.of(component));
        when(stagedMappingRepository.countByTargetIdIn(List.of(5L))).thenReturn(2L);
        when(stagedMappingRepository.countBySourceIdInAndStageNot(List.of(5L), "PROPOSAL_SRS")).thenReturn(1L);
        when(goalMappingRepository.countByComponentIdIn(List.of(5L))).thenReturn(1L);

        GitHubRepositoryLinkService.RepositoryImpact impact = service.unlink(7L, "TEAM-1");

        assertFalse(repository.getActive());
        assertTrue(component.getSourceRemoved());
        assertEquals(List.of("TEAM-1 - src/App.java"), impact.componentNames());
        assertEquals(4, impact.mappingCount());
        verify(componentRepository).saveAll(List.of(component));
        verify(stagedMappingRepository, never()).deleteComponentReferences(any());
        verify(goalMappingRepository, never()).deleteByComponentId(any());
    }

    private GitHubRepositoryLink linkedRepository() {
        GitHubRepositoryLink repository = new GitHubRepositoryLink();
        repository.setId(7L);
        repository.setOwner("owner");
        repository.setRepo("repo");
        repository.setTeamCode("TEAM-1");
        repository.setRepositoryUrl("https://github.com/owner/repo");
        repository.setDefaultBranch("main");
        repository.setActive(true);
        return repository;
    }
}
