package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.StagedTraceMapping;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.repository.StagedTraceMappingRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StagedMappingServiceTest {

    private static StagedTraceMapping link(String stage, long source, long target) {
        StagedTraceMapping m = new StagedTraceMapping();
        m.setTeamCode("TEAM");
        m.setStage(stage);
        m.setSourceId(source);
        m.setTargetId(target);
        return m;
    }

    @Test
    void derivesGoalLinksThroughTheWholeChain() {
        // goal 1 → UC 10 → class 20 → file 30
        List<StagedTraceMapping> chain = List.of(
            link("PROPOSAL_SRS", 1, 10),
            link("SRS_SDD", 10, 20),
            link("SDD_IMPLEMENTATION", 20, 30));

        Set<List<Long>> links = StagedMappingService.deriveGoalLinks(chain, Set.of(1L));

        assertEquals(Set.of(List.of(1L, 10L), List.of(1L, 20L), List.of(1L, 30L)), links);
    }

    @Test
    void ignoresBranchesNotRootedInTheTeamsGoals() {
        List<StagedTraceMapping> chain = List.of(
            link("PROPOSAL_SRS", 99, 10),   // goal from another team
            link("SRS_SDD", 11, 20));       // UC 11 was never mapped from a goal

        assertTrue(StagedMappingService.deriveGoalLinks(chain, Set.of(1L)).isEmpty());
    }

    @Test
    void saveRemovesEveryLinkTheWorkspaceNoLongerHas() {
        StagedTraceMappingRepository stagedRepository = mock(StagedTraceMappingRepository.class);
        GoalComponentMappingRepository mappingRepository = mock(GoalComponentMappingRepository.class);
        SmartGoalRepository goalRepository = mock(SmartGoalRepository.class);
        SmartGoalService goalService = mock(SmartGoalService.class);
        ContinuityAnalysisResetService analysisReset = mock(ContinuityAnalysisResetService.class);
        StagedMappingService service = new StagedMappingService(stagedRepository, mappingRepository,
            goalRepository, mock(TraceComponentRepository.class), goalService, null, analysisReset);

        SmartGoal goal = new SmartGoal();
        goal.setId(1L);
        when(goalRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc("TEAM")).thenReturn(List.of(goal));
        // The workspace now maps goal 1 only to UC 10.
        when(stagedRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc("TEAM"))
            .thenReturn(List.of(link("PROPOSAL_SRS", 1, 10)));

        GoalComponentMapping kept = new GoalComponentMapping();
        kept.setGoalId(1L);
        kept.setComponentId(10L);
        GoalComponentMapping savedBeforeSharing = new GoalComponentMapping();
        savedBeforeSharing.setGoalId(1L);
        savedBeforeSharing.setComponentId(11L);
        when(mappingRepository.findByGoalIdIn(any())).thenReturn(List.of(kept, savedBeforeSharing));

        StagedMappingService.SyncResult result = service.sync("TEAM");

        verify(mappingRepository).delete(savedBeforeSharing);
        verify(mappingRepository, never()).delete(kept);
        verify(goalService, never()).addGoalComponents(any(), any());
        assertEquals(1, result.removed());
        assertEquals(0, result.added());
        verify(analysisReset).resetTeam("TEAM");
        assertTrue(result.analysisCleared());
    }
}
