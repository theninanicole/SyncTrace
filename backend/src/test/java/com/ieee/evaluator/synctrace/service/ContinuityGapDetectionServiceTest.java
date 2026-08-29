package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
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
    @Mock private SmartGoalRepository goalRepository;
    @Mock private TeamComponentResolverService teamComponentResolver;

    private ContinuityGapDetectionService service;

    private static final String TEAM_CODE = "2026-SEM1-IT01-01";

    @BeforeEach
    void setUp() {
        service = new ContinuityGapDetectionService(
                mappingRepository, componentRepository, findingRepository, goalRepository, teamComponentResolver);
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

        assertEquals(2, findings.size());
        assertTrue(findings.stream().anyMatch(f ->
                f.getDocTypeFrom() == DocType.PROPOSAL && f.getDocTypeTo() == DocType.SRS
                        && f.getSeverity() == ContinuityFinding.Severity.HIGH));
        assertTrue(findings.stream().anyMatch(f ->
                f.getDocTypeFrom() == DocType.SDD && f.getDocTypeTo() == DocType.IMPLEMENTATION
                        && f.getSeverity() == ContinuityFinding.Severity.HIGH));
        findings.forEach(f -> assertEquals(TEAM_CODE, f.getTeamCode()));
    }

    @Test
    void detectGapsReturnsNoFindingsWhenAllDocTypesAreCovered() {
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

        assertTrue(findings.isEmpty());
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
}
