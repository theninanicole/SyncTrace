package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.ContinuityAnalysisRun;
import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.ContinuityAnalysisRunRepository;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    @Mock private DiagnosticRecommendationRepository recommendationRepository;
    @Mock private ContinuityAnalysisRunRepository analysisRunRepository;
    @Mock private SmartGoalRepository goalRepository;
    @Mock private TeamComponentResolverService teamComponentResolver;

    private ContinuityGapDetectionService service;

    private static final String TEAM_CODE = "2026-SEM1-IT01-01";

    private final List<GoalComponentMapping> mappings = new ArrayList<>();
    private final List<TraceComponent> components = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TraceabilityClusterService clusterService = new TraceabilityClusterService(
            goalRepository, mappingRepository, componentRepository, teamComponentResolver);
        service = new ContinuityGapDetectionService(
            findingRepository, recommendationRepository, analysisRunRepository, goalRepository, clusterService);

        lenient().when(mappingRepository.findByGoalIdIn(anyCollection())).thenReturn(mappings);
        lenient().when(componentRepository.findAllById(anyIterable())).thenReturn(components);
        lenient().when(teamComponentResolver.countsForTeam(any(), anyString())).thenReturn(true);
        lenient().when(findingRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(analysisRunRepository.findByTeamCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void detectGapsFlagsMissingSrsForGoalWithoutSrs() {
        SmartGoal goal = goal(1L, SmartGoal.GoalKind.GENERAL, null);
        stubTeamGoals(goal);
        map(goal, component(10L, DocType.SDD, null, "Login design"));

        List<ContinuityFinding> findings = service.detectGaps(TEAM_CODE, null);

        assertTrue(findings.stream().anyMatch(f ->
            f.getDocTypeTo() == DocType.SRS && f.getDescription().contains("no mapped SRS")));
        // Missing SRS is reported once, not once per required SRS kind as well.
        assertEquals(1, findings.stream().filter(f -> f.getDocTypeTo() == DocType.SRS).count());
        findings.forEach(f -> assertEquals(TEAM_CODE, f.getTeamCode()));
        verify(analysisRunRepository).save(any(ContinuityAnalysisRun.class));
    }

    @Test
    void detectGapsReportsMissingCellForDocTypeTheTeamHasStartedMapping() {
        // Mirrors the screenshot: SPMP column is visible (another goal has SPMP) but this goal
        // has none, so the matrix shows "Missing" and the status must be Failed too.
        SmartGoal first = goal(1L, SmartGoal.GoalKind.GENERAL, null);
        SmartGoal second = goal(2L, SmartGoal.GoalKind.GENERAL, null);
        stubTeamGoals(first, second);
        mapFullSrs(first, 100L, "login");
        map(first, component(110L, DocType.SPMP, null, "login schedule"));
        mapFullSrs(second, 200L, "report");

        List<ContinuityFinding> findings = service.detectGaps(TEAM_CODE, null);

        assertTrue(findings.stream().anyMatch(f ->
            f.getGoalId().equals(2L) && f.getDocTypeTo() == DocType.SPMP));
        assertTrue(findings.stream().noneMatch(f -> f.getGoalId().equals(1L)));
        // SDD was never mapped by the team, so it is not yet a required column.
        assertTrue(findings.stream().noneMatch(f -> f.getDocTypeTo() == DocType.SDD));
    }

    @Test
    void detectGapsTreatsGeneralGoalAndSpecificChildrenAsOneRow() {
        SmartGoal general = goal(1L, SmartGoal.GoalKind.GENERAL, null);
        SmartGoal specific = goal(2L, SmartGoal.GoalKind.SPECIFIC, 1L);
        stubTeamGoals(general, specific);
        mapFullSrs(general, 100L, "login");

        List<ContinuityFinding> findings = service.detectGaps(TEAM_CODE, null);

        // The unmapped SPECIFIC child must not produce its own "no SRS" finding.
        assertTrue(findings.isEmpty(), () -> "unexpected findings: " + findings);
    }

    @Test
    void teamGoalWithNoMappedComponentsFailsInsteadOfBeingSkipped() {
        SmartGoal general = goal(1L, SmartGoal.GoalKind.GENERAL, null);
        SmartGoal specific = goal(2L, SmartGoal.GoalKind.SPECIFIC, 1L);
        stubTeamGoals(general, specific);

        List<ContinuityFinding> findings = service.detectGaps(TEAM_CODE, null);

        // One finding for the GENERAL goal's cluster; the SPECIFIC child isn't checked on its own.
        assertEquals(1, findings.size());
        assertEquals(1L, findings.get(0).getGoalId());
        assertEquals("SMART Goal has no mapped components.", findings.get(0).getDescription());
    }

    @Test
    void detectGapsIsDeterministicAcrossRuns() {
        SmartGoal first = goal(1L, SmartGoal.GoalKind.GENERAL, null);
        SmartGoal second = goal(2L, SmartGoal.GoalKind.GENERAL, null);
        stubTeamGoals(first, second);
        map(first, component(100L, DocType.SRS, ArtifactKind.USE_CASE, "login"));
        map(first, component(101L, DocType.SDD, null, "payment"));
        map(second, component(200L, DocType.SPMP, null, "schedule"));

        List<String> run1 = describe(service.detectGaps(TEAM_CODE, null));
        List<String> run2 = describe(service.detectGaps(TEAM_CODE, null));

        assertEquals(run1, run2);
        assertTrue(!run1.isEmpty());
    }

    @Test
    void detectGapsKeepsListedTeamCodeAndSourceCodeAlignmentFindings() {
        SmartGoal goal = goal(1L, SmartGoal.GoalKind.GENERAL, null);

        ContinuityFinding staleGoalFinding = new ContinuityFinding();
        staleGoalFinding.setId(90L);
        staleGoalFinding.setGoalId(1L);
        ContinuityFinding alignmentFinding = new ContinuityFinding();
        alignmentFinding.setId(91L);
        alignmentFinding.setGoalId(null);
        // Team code as listed in the class list (lower case here); stored findings from an
        // earlier run under a different case must still be found and replaced.
        String listedTeamCode = TEAM_CODE.toLowerCase();
        when(goalRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(listedTeamCode)).thenReturn(List.of(goal));
        when(findingRepository.findByTeamCodeIgnoreCaseOrderByDetectedAtDescIdAsc(listedTeamCode))
            .thenReturn(List.of(staleGoalFinding, alignmentFinding));

        List<ContinuityFinding> findings = service.detectGaps("  " + listedTeamCode + " ", null);

        verify(recommendationRepository).deleteByFindingId(90L);
        verify(recommendationRepository, never()).deleteByFindingId(91L);
        verify(findingRepository).deleteAll(List.of(staleGoalFinding));
        // Kept exactly as listed (only trimmed) — no forced upper-casing.
        assertTrue(!findings.isEmpty());
        findings.forEach(f -> assertEquals(listedTeamCode, f.getTeamCode()));
    }

    @Test
    void detectGapsForSingleGoalOnlyReplacesThatGoalsFindings() {
        SmartGoal goal = goal(3L, SmartGoal.GoalKind.GENERAL, null);
        stubTeamGoals(goal);
        ContinuityFinding staleFinding = new ContinuityFinding();
        staleFinding.setId(99L);
        staleFinding.setTeamCode(TEAM_CODE);
        staleFinding.setGoalId(3L);
        when(findingRepository.findByGoalId(3L)).thenReturn(List.of(staleFinding));

        List<ContinuityFinding> findings = service.detectGaps(TEAM_CODE, 3L);

        verify(findingRepository).deleteAll(List.of(staleFinding));
        assertEquals(1, findings.size());
        assertEquals(3L, findings.get(0).getGoalId());
    }

    @Test
    void useCaseNeedsItsOwnClassSequenceAndTestCaseCounterparts() {
        SmartGoal goal = goal(1L, SmartGoal.GoalKind.GENERAL, null);
        stubTeamGoals(goal);
        mapFullSrs(goal, 100L, "login");
        // A matching SDD component exists, but only as a UI design: before, this passed the
        // SRS -> SDD check; now the use case still needs a class and a sequence diagram.
        map(goal, component(110L, DocType.SDD, ArtifactKind.UI, "login wireframe screen"));
        map(goal, component(111L, DocType.SDD, ArtifactKind.CLASS, "login controller"));
        map(goal, component(120L, DocType.STD, ArtifactKind.TEST_CASE, "login test"));

        List<String> descriptions = service.detectGaps(TEAM_CODE, null).stream()
            .map(ContinuityFinding::getDescription)
            .toList();

        assertEquals(List.of("Use case 'login use case' has no corresponding sequence diagram (SDD)."), descriptions);
    }

    @Test
    void counterpartMustMatchTheSourceComponentNotJustExist() {
        SmartGoal goal = goal(1L, SmartGoal.GoalKind.GENERAL, null);
        stubTeamGoals(goal);
        TraceComponent useCase = component(100L, DocType.SRS, ArtifactKind.USE_CASE, "checkout order");
        useCase.setCodeName("UC-02");
        map(goal, useCase);
        map(goal, component(101L, DocType.SRS, ArtifactKind.ACTIVITY, "checkout activity"));
        map(goal, component(102L, DocType.SRS, ArtifactKind.WIREFRAME, "checkout wireframe"));
        map(goal, component(110L, DocType.SDD, ArtifactKind.CLASS, "profile settings"));
        map(goal, component(111L, DocType.SDD, ArtifactKind.SEQUENCE, "checkout sequence"));
        map(goal, component(112L, DocType.SDD, ArtifactKind.UI, "checkout screen"));

        List<ContinuityFinding> findings = service.detectGaps(TEAM_CODE, null);

        assertTrue(findings.stream().anyMatch(f -> f.getDocTypeFrom() == DocType.SRS
            && f.getDocTypeTo() == DocType.SDD
            && f.getDescription().equals("Use case 'checkout order' (UC-02) has no corresponding class diagram (SDD).")));
        // STD and IMPLEMENTATION are not being mapped yet, so those counterparts aren't required.
        assertTrue(findings.stream().noneMatch(f -> f.getDescription().contains("sequence diagram")));
        assertTrue(findings.stream().noneMatch(f -> f.getDocTypeTo() == DocType.STD));
    }

    @Test
    void classDiagramNeedsMatchingImplementationOnceCodeIsMapped() {
        SmartGoal goal = goal(1L, SmartGoal.GoalKind.GENERAL, null);
        stubTeamGoals(goal);
        mapFullSrs(goal, 100L, "login");
        map(goal, component(110L, DocType.SDD, ArtifactKind.CLASS, "login service"));
        map(goal, component(111L, DocType.SDD, ArtifactKind.SEQUENCE, "login sequence"));
        map(goal, component(112L, DocType.SDD, ArtifactKind.UI, "login wireframe screen"));
        map(goal, component(130L, DocType.IMPLEMENTATION, ArtifactKind.IMPL_OO, "ReportExporter.java"));

        List<ContinuityFinding> findings = service.detectGaps(TEAM_CODE, null);

        List<String> counterpartFindings = findings.stream()
            .map(ContinuityFinding::getDescription)
            .filter(d -> d.contains("has no corresponding "))
            .toList();
        assertEquals(List.of("Class diagram 'login service' has no corresponding OO implementation (IMPLEMENTATION)."),
            counterpartFindings);
    }

    private void stubTeamGoals(SmartGoal... goals) {
        when(goalRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(TEAM_CODE)).thenReturn(List.of(goals));
    }

    private SmartGoal goal(long id, SmartGoal.GoalKind kind, Long parentId) {
        SmartGoal goal = new SmartGoal();
        goal.setId(id);
        goal.setDescription("Goal " + id);
        goal.setGoalKind(kind);
        goal.setParentGoalId(parentId);
        goal.setTeamCode(TEAM_CODE);
        goal.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(id));
        return goal;
    }

    private TraceComponent component(long id, DocType docType, ArtifactKind kind, String name) {
        TraceComponent component = new TraceComponent();
        component.setId(id);
        component.setDocType(docType);
        component.setArtifactKind(kind);
        component.setName(name);
        return component;
    }

    private void map(SmartGoal goal, TraceComponent component) {
        GoalComponentMapping mapping = new GoalComponentMapping();
        mapping.setId((long) mappings.size() + 1);
        mapping.setGoalId(goal.getId());
        mapping.setComponentId(component.getId());
        mappings.add(mapping);
        components.add(component);
    }

    private void mapFullSrs(SmartGoal goal, long firstId, String topic) {
        map(goal, component(firstId, DocType.SRS, ArtifactKind.USE_CASE, topic + " use case"));
        map(goal, component(firstId + 1, DocType.SRS, ArtifactKind.ACTIVITY, topic + " activity"));
        map(goal, component(firstId + 2, DocType.SRS, ArtifactKind.WIREFRAME, topic + " wireframe"));
    }

    private static List<String> describe(List<ContinuityFinding> findings) {
        return findings.stream()
            .map(f -> f.getGoalId() + "|" + f.getDocTypeFrom() + "|" + f.getDocTypeTo() + "|" + f.getDescription())
            .toList();
    }
}
