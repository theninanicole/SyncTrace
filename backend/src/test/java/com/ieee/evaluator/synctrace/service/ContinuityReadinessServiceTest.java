package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.DiagnosticRecommendationRepository;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Edge-case coverage for team readiness summary computation. Pure unit tests (no Spring context).
 */
@ExtendWith(MockitoExtension.class)
class ContinuityReadinessServiceTest {

    @Mock private SmartGoalRepository goalRepository;
    @Mock private GoalComponentMappingRepository mappingRepository;
    @Mock private TraceComponentRepository componentRepository;
    @Mock private ContinuityFindingRepository findingRepository;
    @Mock private DiagnosticRecommendationRepository recommendationRepository;
    @Mock private TeamComponentResolverService teamComponentResolver;

    private ContinuityReadinessService service;

    private static final String TEAM_CODE = "2026-SEM1-IT01-01";

    @BeforeEach
    void setUp() {
        service = new ContinuityReadinessService(
                goalRepository, mappingRepository, componentRepository,
                findingRepository, recommendationRepository, teamComponentResolver);
    }

    @Test
    void getTeamReadinessSummaryThrowsForBlankTeamCode() {
        assertThrows(IllegalArgumentException.class, () -> service.getTeamReadinessSummary(" "));
    }

    @Test
    void getTeamReadinessSummaryReturnsZeroedSummaryWhenTeamHasNoMappedGoals() {
        when(goalRepository.findAll()).thenReturn(List.of());
        when(findingRepository.findByTeamCodeOrderByDetectedAtDesc(TEAM_CODE)).thenReturn(List.of());

        Map<String, Object> summary = service.getTeamReadinessSummary(TEAM_CODE);

        assertEquals(0, summary.get("totalGoals"));
        assertEquals(0, summary.get("readyGoals"));
        assertEquals(0, summary.get("readinessScore"));
    }

    @Test
    void getTeamReadinessSummaryMarksGoalReadyWhenAllDocTypesCoveredAndNoFindings() {
        long goalId = 1L;
        SmartGoal goal = new SmartGoal();
        goal.setId(goalId);
        goal.setDescription("Goal A");

        when(goalRepository.findAll()).thenReturn(List.of(goal));
        when(findingRepository.findByTeamCodeOrderByDetectedAtDesc(TEAM_CODE)).thenReturn(List.of());
        stubFullyCoveredGoal(goalId);

        Map<String, Object> summary = service.getTeamReadinessSummary(TEAM_CODE);

        assertEquals(1, summary.get("totalGoals"));
        assertEquals(1, summary.get("readyGoals"));
        assertEquals(100, summary.get("readinessScore"));
        assertEquals("READY", summary.get("status"));
    }

    @Test
    void getTeamReadinessSummaryFlagsMissingDocTypeForPartiallyCoveredGoal() {
        long goalId = 2L;
        SmartGoal goal = new SmartGoal();
        goal.setId(goalId);
        goal.setDescription("Goal B");

        GoalComponentMapping mapping = new GoalComponentMapping();
        mapping.setGoalId(goalId);
        mapping.setComponentId(50L);

        TraceComponent srsComponent = new TraceComponent();
        srsComponent.setId(50L);
        srsComponent.setDocType(DocType.SRS);

        when(goalRepository.findAll()).thenReturn(List.of(goal));
        when(findingRepository.findByTeamCodeOrderByDetectedAtDesc(TEAM_CODE)).thenReturn(List.of());
        when(mappingRepository.findByGoalId(goalId)).thenReturn(List.of(mapping));
        when(componentRepository.findAllById(anyList())).thenReturn(List.of(srsComponent));
        when(teamComponentResolver.belongsToTeam(any(), eq(TEAM_CODE))).thenReturn(true);

        Map<String, Object> summary = service.getTeamReadinessSummary(TEAM_CODE);

        assertEquals(1, summary.get("totalGoals"));
        assertEquals(0, summary.get("readyGoals"));
        assertEquals(0, summary.get("readinessScore"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> goalSummaries = (List<Map<String, Object>>) summary.get("goalSummaries");
        @SuppressWarnings("unchecked")
        List<String> missingDocTypes = (List<String>) goalSummaries.get(0).get("missingDocTypes");
        assertTrue(missingDocTypes.contains("IMPLEMENTATION"));
    }

    /** Wires one mapped component per required doc type, all resolved to {@link #TEAM_CODE}. */
    private void stubFullyCoveredGoal(long goalId) {
        Map<DocType, Long> componentIdsByDocType = Map.of(
                DocType.SRS, 10L,
                DocType.SDD, 11L,
                DocType.SPMP, 12L,
                DocType.STD, 13L,
                DocType.IMPLEMENTATION, 14L
        );

        List<GoalComponentMapping> mappings = componentIdsByDocType.values().stream()
                .map(componentId -> {
                    GoalComponentMapping mapping = new GoalComponentMapping();
                    mapping.setGoalId(goalId);
                    mapping.setComponentId(componentId);
                    return mapping;
                })
                .toList();

        List<TraceComponent> components = componentIdsByDocType.entrySet().stream()
                .map(entry -> {
                    TraceComponent component = new TraceComponent();
                    component.setId(entry.getValue());
                    component.setDocType(entry.getKey());
                    return component;
                })
                .toList();

        when(mappingRepository.findByGoalId(goalId)).thenReturn(mappings);
        when(componentRepository.findAllById(anyList())).thenReturn(components);
        when(teamComponentResolver.belongsToTeam(any(), eq(TEAM_CODE))).thenReturn(true);
    }
}
