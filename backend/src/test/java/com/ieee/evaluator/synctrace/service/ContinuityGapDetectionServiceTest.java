package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.ContinuityAnalysisRun;
import com.ieee.evaluator.synctrace.repository.ContinuityAnalysisRunRepository;
import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (no Spring context) so these run without a database or Google credentials.
 */
@ExtendWith(MockitoExtension.class)
class ContinuityGapDetectionServiceTest {

    @Mock private GoalComponentMappingRepository mappingRepository;
    @Mock private TraceComponentRepository componentRepository;
    @Mock private ContinuityFindingRepository findingRepository;
    @Mock private ContinuityAnalysisRunRepository analysisRunRepository;
    @Mock private SmartGoalRepository goalRepository;
    @Mock private TeamComponentResolverService teamComponentResolver;

    private ContinuityGapDetectionService service;

    private static final String TEAM_CODE = "2026-SEM1-IT01-01";

    @BeforeEach
    void setUp() {
        service = new ContinuityGapDetectionService(
            mappingRepository, componentRepository, findingRepository, analysisRunRepository,
            goalRepository, teamComponentResolver);
    }

    @Test
    void detectGapsFlagsMissingSrsAndDownstreamGapForPartiallyMappedGoal() {
        long goalId = 1L;

        GoalComponentMapping sddMapping = new GoalComponentMapping();
        sddMapping.setGoalId(goalId);
        sddMapping.setComponentId(10L);

        TraceComponent sddComponent = new TraceComponent();
        sddComponent.setId(10L);
        sddComponent.setDocType(DocType.SDD);

        when(goalRepository.existsById(goalId)).thenReturn(true);
        when(findingRepository.findByGoalId(goalId)).thenReturn(List.of());
        when(mappingRepository.findByGoalId(goalId)).thenReturn(List.of(sddMapping));
        when(componentRepository.findAllById(anyList())).thenReturn(List.of(sddComponent));
        when(teamComponentResolver.belongsToTeam(any(), eq(TEAM_CODE))).thenReturn(true);
        when(findingRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ContinuityFinding> findings = service.detectGaps(TEAM_CODE, goalId);

        assertTrue(findings.size() >= 2);
        assertTrue(findings.stream().anyMatch(f ->
                f.getDocTypeFrom() == DocType.PROPOSAL && f.getDocTypeTo() == DocType.SRS
                        && f.getSeverity() == ContinuityFinding.Severity.HIGH));
        assertTrue(findings.stream().noneMatch(f ->
            f.getDocTypeFrom() == DocType.SDD && f.getDocTypeTo() == DocType.IMPLEMENTATION));
        findings.forEach(f -> assertEquals(TEAM_CODE, f.getTeamCode()));
        verify(analysisRunRepository).save(any(ContinuityAnalysisRun.class));
    }

    @Test
    void detectGapsStillFindsGapsWhenOnlyOneComponentPerDocTypeIsCovered() {
        long goalId = 2L;

        Map<DocType, Long> componentIdsByDocType = Map.of(
                DocType.SRS, 101L,
                DocType.SDD, 102L,
                DocType.SPMP, 103L,
                DocType.STD, 104L,
                DocType.IMPLEMENTATION, 105L
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

        when(goalRepository.existsById(goalId)).thenReturn(true);
        when(findingRepository.findByGoalId(goalId)).thenReturn(List.of());
        when(mappingRepository.findByGoalId(goalId)).thenReturn(mappings);
        when(componentRepository.findAllById(anyList())).thenReturn(components);
        when(findingRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        // teamCode is null here: coverage is resolved across all mapped components regardless of team.
        List<ContinuityFinding> findings = service.detectGaps(null, goalId);

        assertTrue(findings.stream().anyMatch(f -> f.getDescription().contains("required SRS")));
    }

    @Test
    void detectGapsClearsExistingFindingsForGoalBeforeRegenerating() {
        long goalId = 3L;
        ContinuityFinding staleFinding = new ContinuityFinding();
        staleFinding.setId(99L);
        staleFinding.setTeamCode(TEAM_CODE);
        staleFinding.setGoalId(goalId);

        when(findingRepository.findByGoalId(goalId)).thenReturn(List.of(staleFinding));
        when(goalRepository.existsById(goalId)).thenReturn(false);
        when(findingRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ContinuityFinding> findings = service.detectGaps(TEAM_CODE, goalId);

        verify(findingRepository).delete(staleFinding);
        assertTrue(findings.isEmpty());
    }

    @Test
    void teamGoalWithNoMappedComponentsFailsInsteadOfBeingSkipped() {
        SmartGoal general = new SmartGoal();
        general.setId(1L);
        general.setTeamCode(TEAM_CODE);
        SmartGoal specific = new SmartGoal();
        specific.setId(2L);
        specific.setTeamCode(TEAM_CODE);
        specific.setParentGoalId(1L);

        when(goalRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(TEAM_CODE))
            .thenReturn(List.of(general, specific));
        when(goalRepository.existsById(1L)).thenReturn(true);
        when(mappingRepository.findByGoalId(any())).thenReturn(List.of());
        when(findingRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ContinuityFinding> findings = service.detectGaps(TEAM_CODE, null);

        // One finding for the GENERAL goal's cluster; the SPECIFIC child isn't checked on its own.
        assertEquals(1, findings.size());
        assertEquals(1L, findings.get(0).getGoalId());
        assertEquals("SMART Goal has no mapped components.", findings.get(0).getDescription());
    }
}
