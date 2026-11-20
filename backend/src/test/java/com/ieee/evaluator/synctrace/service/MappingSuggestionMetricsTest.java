package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.service.AiProvider;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.synctrace.model.*;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** M5: every AI suggestion stays countable, including rejected and never-decided ones. */
class MappingSuggestionMetricsTest {
    private MappingSuggestionRepository suggestions;
    private StagedMappingService stagedService;
    private TraceComponentRepository components;
    private AiProvider provider;
    private MappingSuggestionService service;

    @BeforeEach
    void setUp() {
        suggestions = mock(MappingSuggestionRepository.class);
        stagedService = mock(StagedMappingService.class);
        components = mock(TraceComponentRepository.class);
        SmartGoalRepository goals = mock(SmartGoalRepository.class);
        StagedTraceMappingRepository staged = mock(StagedTraceMappingRepository.class);
        TeamComponentResolverService resolver = mock(TeamComponentResolverService.class);
        provider = mock(AiProvider.class);
        when(provider.getProviderName()).thenReturn("openai");
        service = new MappingSuggestionService(suggestions, mock(MappingGenerationRunRepository.class), staged,
            goals, components, resolver, stagedService, new MappingSuggestionPromptService(),
            mock(ProgressEmitter.class), List.of(provider));

        SmartGoal goal = new SmartGoal();
        goal.setId(1L);
        goal.setTeamCode("TEAM");
        goal.setGoalKind(SmartGoal.GoalKind.GENERAL);
        goal.setDescription("Manage accounts");
        when(goals.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc("TEAM")).thenReturn(List.of(goal));
        when(staged.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc("TEAM")).thenReturn(List.of());
        when(suggestions.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(resolver.findTeamSummaries("TEAM", DocType.SRS, "")).thenReturn(List.of(
            new TraceComponentSummaryDTO(10L, DocType.SRS, null, "UC", "UC", "UC",
                false, null, "MANUAL", "team:TEAM", null, false, null, null)));
        when(components.existsById(anyLong())).thenReturn(true);
    }

    private MappingSuggestion pending(long id) {
        MappingSuggestion suggestion = new MappingSuggestion();
        suggestion.setId(id);
        suggestion.setTeamCode("TEAM");
        suggestion.setStage("PROPOSAL_SRS");
        suggestion.setSourceId(1L);
        suggestion.setTargetComponentId(10L);
        suggestion.setStatus(MappingSuggestion.Status.PENDING);
        when(suggestions.findById(id)).thenReturn(Optional.of(suggestion));
        return suggestion;
    }

    private static StagedTraceMapping row(long id) {
        StagedTraceMapping row = new StagedTraceMapping();
        row.setId(id);
        row.setStage("PROPOSAL_SRS");
        return row;
    }

    @Test
    void rejectedSuggestionsAreKeptWithTheirDecider() {
        MappingSuggestion suggestion = pending(1);

        service.decide("TEAM", 1L, new MappingSuggestionService.Decision("REJECT", null),
            "student@example.edu", "uuid-student", true, MappingSuggestion.METHOD_SINGLE);

        assertEquals(MappingSuggestion.Status.REJECTED, suggestion.getStatus());
        assertEquals("uuid-student", suggestion.getDecidedByUserId());
        assertEquals("single", suggestion.getDecisionMethod());
        assertNotNull(suggestion.getDecidedAt());
        assertFalse(suggestion.isSystemRejected());
        verify(suggestions).save(suggestion);
        verify(suggestions, never()).delete(any());
    }

    @Test
    void acceptAndEditRecordTheResultingLinks() {
        MappingSuggestion accepted = pending(1);
        when(stagedService.addWithOrigin(eq("TEAM"), anyList(), any(), anyBoolean(),
            eq(StagedTraceMapping.Origin.AI_ACCEPTED))).thenReturn(List.of(row(77)));
        service.decide("TEAM", 1L, new MappingSuggestionService.Decision("ACCEPT", null),
            "t@example.edu", "uuid-t", false, MappingSuggestion.METHOD_SINGLE);
        assertEquals(MappingSuggestion.Status.ACCEPTED, accepted.getStatus());
        assertEquals(77L, accepted.getResultingLinkId());
        assertEquals(1, accepted.getResultingLinkCount());

        MappingSuggestion edited = pending(2);
        when(stagedService.addWithOrigin(eq("TEAM"), anyList(), any(), anyBoolean(),
            eq(StagedTraceMapping.Origin.AI_EDITED))).thenReturn(List.of(row(88), row(89)));
        service.decide("TEAM", 2L, new MappingSuggestionService.Decision("EDIT", List.of(10L, 11L)),
            "t@example.edu", "uuid-t", false, MappingSuggestion.METHOD_SINGLE);
        assertEquals(MappingSuggestion.Status.EDITED, edited.getStatus());
        assertEquals(88L, edited.getResultingLinkId());
        assertEquals(2, edited.getResultingLinkCount());
    }

    @Test
    void discardAndRegenerateWithdrawPendingInsteadOfDeleting() throws Exception {
        service.discard("TEAM", "PROPOSAL_SRS");
        verify(suggestions).markPendingWithdrawn(eq("TEAM"), eq("PROPOSAL_SRS"),
            eq(MappingSuggestion.WITHDRAWN_DISCARDED), any(LocalDateTime.class));

        when(provider.complete(anyString())).thenReturn("[{\"sourceId\":1,\"targetId\":10,\"score\":0.9}]");
        service.generate("TEAM", "PROPOSAL_SRS", "session");
        verify(suggestions).markPendingWithdrawn(eq("TEAM"), eq("PROPOSAL_SRS"),
            eq(MappingSuggestion.WITHDRAWN_SUPERSEDED), any(LocalDateTime.class));
        verify(suggestions, never()).delete(any());
        verify(suggestions, never()).deleteAll(any());
    }

    @Test
    void withdrawnSuggestionsStayPendingButLeaveTheReviewList() {
        MappingSuggestion visible = pending(1);
        MappingSuggestion withdrawn = pending(2);
        withdrawn.setWithdrawnAt(LocalDateTime.now());
        withdrawn.setWithdrawnReason(MappingSuggestion.WITHDRAWN_DISCARDED);
        when(suggestions.findByTeamCodeIgnoreCaseAndStageAndStatusOrderByScoreDesc(
            "TEAM", "PROPOSAL_SRS", MappingSuggestion.Status.PENDING)).thenReturn(List.of(visible, withdrawn));

        assertEquals(List.of(visible), service.pending("TEAM", "PROPOSAL_SRS"));
        assertEquals(MappingSuggestion.Status.PENDING, withdrawn.getStatus());
    }

    @Test
    void aWithdrawnSuggestionCannotBeDecidedLikeADeletedOne() {
        MappingSuggestion withdrawn = pending(1);
        withdrawn.setWithdrawnAt(LocalDateTime.now());
        assertThrows(IllegalArgumentException.class, () -> service.decide("TEAM", 1L,
            new MappingSuggestionService.Decision("ACCEPT", null), "u", "uuid", false, "single"));
        assertEquals(MappingSuggestion.Status.PENDING, withdrawn.getStatus());
        verifyNoInteractions(stagedService);
    }

    @Test
    void staleTargetsAreSystemRejectionsNotUserRejections() {
        MappingSuggestion stale = pending(1);
        when(components.existsById(10L)).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.decide("TEAM", 1L,
            new MappingSuggestionService.Decision("ACCEPT", null), "u", "uuid", false, "single"));
        assertEquals(MappingSuggestion.Status.REJECTED, stale.getStatus());
        assertTrue(stale.isSystemRejected());
    }

    @Test
    void acceptAllMarksItsDecisionsAndSkipsWithdrawn() {
        MappingSuggestion open = pending(1);
        MappingSuggestion withdrawn = pending(2);
        withdrawn.setWithdrawnAt(LocalDateTime.now());
        when(suggestions.findByTeamCodeIgnoreCaseAndStageAndStatus("TEAM", "PROPOSAL_SRS",
            MappingSuggestion.Status.PENDING)).thenReturn(List.of(open, withdrawn));
        when(stagedService.addWithOrigin(any(), anyList(), any(), anyBoolean(), any())).thenReturn(List.of(row(5)));

        assertEquals(1, service.acceptAll("TEAM", "PROPOSAL_SRS", "u", "uuid", false));
        assertEquals(MappingSuggestion.Status.ACCEPTED, open.getStatus());
        assertEquals("accept_all", open.getDecisionMethod());
        assertEquals(MappingSuggestion.Status.PENDING, withdrawn.getStatus());
    }
}
