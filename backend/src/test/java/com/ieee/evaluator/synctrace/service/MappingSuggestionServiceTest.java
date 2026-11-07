package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.service.AiProvider;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.synctrace.model.*;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MappingSuggestionServiceTest {
    private MappingSuggestionRepository suggestions;
    private MappingGenerationRunRepository runs;
    private StagedTraceMappingRepository staged;
    private SmartGoalRepository goals;
    private TraceComponentRepository components;
    private TeamComponentResolverService resolver;
    private StagedMappingService stagedService;
    private ProgressEmitter progress;
    private AiProvider provider;
    private MappingSuggestionService service;

    @BeforeEach
    void setUp() {
        suggestions = mock(MappingSuggestionRepository.class);
        runs = mock(MappingGenerationRunRepository.class);
        staged = mock(StagedTraceMappingRepository.class);
        goals = mock(SmartGoalRepository.class);
        components = mock(TraceComponentRepository.class);
        resolver = mock(TeamComponentResolverService.class);
        stagedService = mock(StagedMappingService.class);
        progress = mock(ProgressEmitter.class);
        provider = mock(AiProvider.class);
        when(provider.getProviderName()).thenReturn("openai");
        service = new MappingSuggestionService(suggestions, runs, staged, goals, components,
            resolver, stagedService, new MappingSuggestionPromptService(), progress, List.of(provider));

        SmartGoal goal = new SmartGoal();
        goal.setId(1L);
        goal.setTeamCode("TEAM");
        goal.setGoalKind(SmartGoal.GoalKind.GENERAL);
        goal.setDescription("Manage accounts");
        when(goals.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc("TEAM")).thenReturn(List.of(goal));
        when(goals.findByParentGoalIdOrderByCreatedAtAscIdAsc(1L)).thenReturn(List.of());
        when(staged.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc("TEAM")).thenReturn(List.of());
        when(suggestions.findByTeamCodeIgnoreCaseAndStageAndStatus(
            eq("TEAM"), eq("PROPOSAL_SRS"), eq(MappingSuggestion.Status.REJECTED)))
            .thenReturn(List.of());
        when(suggestions.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(resolver.findTeamSummaries("TEAM", DocType.SRS, ""))
            .thenReturn(List.of(summary(10L, "Account use case")));
        when(components.existsById(anyLong())).thenReturn(true);
    }

    @Test
    void excludesStagedAndRejectedPairs() throws Exception {
        when(provider.complete(anyString())).thenReturn("[{\"sourceId\":1,\"targetId\":10,\"score\":0.9}]");
        StagedTraceMapping existing = link("PROPOSAL_SRS", 1, 10);
        when(staged.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc("TEAM")).thenReturn(List.of(existing));
        when(suggestions.findByTeamCodeIgnoreCaseAndStageAndStatus(
            eq("TEAM"), eq("PROPOSAL_SRS"), eq(MappingSuggestion.Status.REJECTED)))
            .thenReturn(List.of(rejected(1L, 10L)));

        assertTrue(service.generate("TEAM", "PROPOSAL_SRS", "session").isEmpty());
    }

    @Test
    void dropsHallucinationsDeduplicatesAndCapsPerSource() throws Exception {
        when(resolver.findTeamSummaries("TEAM", DocType.SRS, ""))
            .thenReturn(List.of(summary(10L, "A"), summary(11L, "B"), summary(12L, "C"),
                summary(13L, "D")));
        when(provider.complete(anyString())).thenReturn("""
            [{"sourceId":1,"targetId":10,"score":0.9},
             {"sourceId":1,"targetId":10,"score":0.8},
             {"sourceId":1,"targetId":11,"score":0.8},
             {"sourceId":1,"targetId":12,"score":0.8},
             {"sourceId":1,"targetId":13,"score":0.8},
             {"sourceId":999,"targetId":10,"score":0.9}]
            """);

        List<MappingSuggestion> result = service.generate("TEAM", "PROPOSAL_SRS", "session");

        assertEquals(3, result.size());
        assertEquals(3, result.stream().map(MappingSuggestion::getTargetComponentId).distinct().count());
    }

    @Test
    void oneFailedBatchDoesNotDiscardOtherResults() throws Exception {
        SmartGoal second = new SmartGoal();
        second.setId(2L);
        second.setTeamCode("TEAM");
        second.setGoalKind(SmartGoal.GoalKind.GENERAL);
        second.setDescription("Export reports");
        SmartGoal first = new SmartGoal();
        first.setId(1L);
        first.setTeamCode("TEAM");
        first.setGoalKind(SmartGoal.GoalKind.GENERAL);
        first.setDescription("Manage accounts");
        when(goals.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc("TEAM"))
            .thenReturn(List.of(first, second));
        when(provider.complete(anyString())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            if (prompt.contains("Manage accounts")) throw new IllegalStateException("batch failed");
            return "[{\"sourceId\":2,\"targetId\":10,\"score\":0.9}]";
        });

        assertEquals(1, service.generate("TEAM", "PROPOSAL_SRS", "session").size());
    }

    @Test
    void allBatchesFailKeepsOldPendingSuggestions() throws Exception {
        when(provider.complete(anyString())).thenThrow(new IllegalStateException("offline"));
        when(suggestions.findByTeamCodeIgnoreCaseAndStageAndStatusOrderByScoreDesc(
            "TEAM", "PROPOSAL_SRS", MappingSuggestion.Status.PENDING))
            .thenReturn(List.of());

        assertThrows(MappingSuggestionService.AiUnavailableException.class,
            () -> service.generate("TEAM", "PROPOSAL_SRS", "session"));
        verify(suggestions, never()).deletePendingByTeamAndStage(anyString(), anyString(), any());
    }

    @Test
    void nonFirstStageUsesOnlyTargetsFromThePrecedingStage() throws Exception {
        when(resolver.findTeamSummaries("TEAM", DocType.SRS, ""))
            .thenReturn(List.of(summary(10L, "Mapped use case"), summary(11L, "Unmapped use case")));
        when(resolver.findTeamSummaries("TEAM", DocType.SDD, ""))
            .thenReturn(List.of(summary(DocType.SDD, 20L, "Class component")));
        when(staged.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc("TEAM"))
            .thenReturn(List.of(link("PROPOSAL_SRS", 1, 10)));
        when(provider.complete(anyString()))
            .thenReturn("[{\"sourceId\":10,\"targetId\":20,\"score\":0.9}]");

        List<MappingSuggestion> result = service.generate("TEAM", "SRS_SDD", "session");

        assertEquals(1, result.size());
        assertEquals(10L, result.get(0).getSourceId());
    }

    @Test
    void stalePendingTargetsAreIgnored() {
        MappingSuggestion stale = pending(1L, 99L);
        when(suggestions.findByTeamCodeIgnoreCaseAndStageAndStatusOrderByScoreDesc(
            "TEAM", "PROPOSAL_SRS", MappingSuggestion.Status.PENDING))
            .thenReturn(List.of(stale));
        when(components.existsById(99L)).thenReturn(false);

        assertTrue(service.pending("TEAM", "PROPOSAL_SRS").isEmpty());
    }

    @Test
    void acceptEditAndRejectSetExpectedOriginsAndRememberRejection() {
        MappingSuggestion accepted = pending(1L, 10L);
        accepted.setId(1L);
        when(suggestions.findById(1L)).thenReturn(Optional.of(accepted));
        service.decide("TEAM", 1L, new MappingSuggestionService.Decision("ACCEPT", null),
            "teacher@example.com", false);
        verify(stagedService).addWithOrigin(eq("TEAM"), anyList(), eq("teacher@example.com"),
            eq(false), eq(StagedTraceMapping.Origin.AI_ACCEPTED));

        MappingSuggestion edited = pending(1L, 10L);
        edited.setId(2L);
        when(suggestions.findById(2L)).thenReturn(Optional.of(edited));
        service.decide("TEAM", 2L, new MappingSuggestionService.Decision("EDIT", List.of(10L, 11L)),
            "teacher@example.com", false);
        verify(stagedService).addWithOrigin(eq("TEAM"), argThat(rows -> rows.size() == 2),
            eq("teacher@example.com"), eq(false), eq(StagedTraceMapping.Origin.AI_EDITED));

        MappingSuggestion rejected = pending(1L, 10L);
        rejected.setId(3L);
        when(suggestions.findById(3L)).thenReturn(Optional.of(rejected));
        service.decide("TEAM", 3L, new MappingSuggestionService.Decision("REJECT", null),
            "teacher@example.com", false);
        assertEquals(MappingSuggestion.Status.REJECTED, rejected.getStatus());
    }

    @Test
    void crossTeamDecisionIsDeniedAndDiscardOnlyDeletesPending() {
        MappingSuggestion otherTeam = pending(1L, 10L);
        otherTeam.setId(4L);
        otherTeam.setTeamCode("OTHER");
        when(suggestions.findById(4L)).thenReturn(Optional.of(otherTeam));
        assertThrows(SyncTraceAccessGuard.AccessDeniedException.class,
            () -> service.decide("TEAM", 4L,
                new MappingSuggestionService.Decision("ACCEPT", null), "user", false));

        service.discard("TEAM", "PROPOSAL_SRS");
        verify(suggestions).deletePendingByTeamAndStage(
            "TEAM", "PROPOSAL_SRS", MappingSuggestion.Status.PENDING);
    }

    private static TraceComponentSummaryDTO summary(Long id, String name) {
        return new TraceComponentSummaryDTO(id, DocType.SRS, null, name, name, name,
            false, null, "MANUAL", "team:TEAM", null, false, null, null);
    }

    private static TraceComponentSummaryDTO summary(DocType type, Long id, String name) {
        return new TraceComponentSummaryDTO(id, type, null, name, name, name,
            false, null, "MANUAL", "team:TEAM", null, false, null, null);
    }

    private static MappingSuggestion pending(Long source, Long target) {
        MappingSuggestion suggestion = new MappingSuggestion();
        suggestion.setTeamCode("TEAM");
        suggestion.setStage("PROPOSAL_SRS");
        suggestion.setSourceId(source);
        suggestion.setTargetComponentId(target);
        suggestion.setStatus(MappingSuggestion.Status.PENDING);
        return suggestion;
    }

    private static MappingSuggestion rejected(Long source, Long target) {
        MappingSuggestion suggestion = pending(source, target);
        suggestion.setStatus(MappingSuggestion.Status.REJECTED);
        return suggestion;
    }

    private static StagedTraceMapping link(String stage, long source, long target) {
        StagedTraceMapping mapping = new StagedTraceMapping();
        mapping.setTeamCode("TEAM");
        mapping.setStage(stage);
        mapping.setSourceId(source);
        mapping.setTargetId(target);
        return mapping;
    }
}
