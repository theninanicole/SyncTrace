package com.ieee.evaluator.synctrace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.synctrace.model.*;
import com.ieee.evaluator.synctrace.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (no Spring context) so these run without a database or Google credentials.
 * TeamComponentResolverService is constructed for real (not mocked) so team-scoping resolution
 * logic is genuinely exercised rather than duplicated inline.
 */
@ExtendWith(MockitoExtension.class)
class AuditExportServiceTest {

    @Mock private SmartGoalRepository goalRepository;
    @Mock private TraceComponentRepository componentRepository;
    @Mock private ContinuityFindingRepository findingRepository;
    @Mock private DiagnosticRecommendationRepository recommendationRepository;
    @Mock private GoalComponentMappingRepository mappingRepository;
    @Mock private EvaluationHistoryRepository historyRepository;

    private TeamComponentResolverService teamComponentResolver;
    private AuditExportService auditExportService;
    private ObjectMapper objectMapper;

    private static final String TEAM_A = "2026-SEM1-IT01-01";
    private static final String TEAM_B = "2026-SEM1-IT01-02";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        teamComponentResolver = new TeamComponentResolverService(componentRepository, historyRepository);
        auditExportService = new AuditExportService(
                goalRepository, componentRepository, findingRepository,
                recommendationRepository, mappingRepository, teamComponentResolver, objectMapper);
    }

    @Test
    void testAuditExportDoesNotLeakDataBetweenTeams() throws Exception {
        // Evaluation history backs SDD-component team resolution (history-based scoping)
        EvaluationHistory historyA = new EvaluationHistory();
        historyA.setId(1L);
        historyA.setFileName("[SRS] G01 - " + TEAM_A + " | Student A");
        historyA.setEvaluatedAt(LocalDateTime.now());

        EvaluationHistory historyB = new EvaluationHistory();
        historyB.setId(2L);
        historyB.setFileName("[SRS] G01 - " + TEAM_B + " | Student B");
        historyB.setEvaluatedAt(LocalDateTime.now());

        when(historyRepository.findById(1L)).thenReturn(Optional.of(historyA));
        when(historyRepository.findById(2L)).thenReturn(Optional.of(historyB));

        // IMPLEMENTATION components use name-based team scoping
        TraceComponent implA = new TraceComponent();
        implA.setId(10L);
        implA.setName(TEAM_A + " - src/main/java/Example.java");
        implA.setDocType(TraceComponent.DocType.IMPLEMENTATION);
        implA.setContent("public class Example {}");
        implA.setAiExtracted(true);
        implA.setCreatedAt(LocalDateTime.now());

        TraceComponent implB = new TraceComponent();
        implB.setId(11L);
        implB.setName(TEAM_B + " - src/main/java/Example.java");
        implB.setDocType(TraceComponent.DocType.IMPLEMENTATION);
        implB.setContent("public class Example {}");
        implB.setAiExtracted(true);
        implB.setCreatedAt(LocalDateTime.now());

        TraceComponent sddA = new TraceComponent();
        sddA.setId(12L);
        sddA.setName("Design Document A");
        sddA.setDocType(TraceComponent.DocType.SDD);
        sddA.setContent("SDD content for team A");
        sddA.setAiExtracted(true);
        sddA.setSourceHistoryId(1L);
        sddA.setCreatedAt(LocalDateTime.now());

        TraceComponent sddB = new TraceComponent();
        sddB.setId(13L);
        sddB.setName("Design Document B");
        sddB.setDocType(TraceComponent.DocType.SDD);
        sddB.setContent("SDD content for team B");
        sddB.setAiExtracted(true);
        sddB.setSourceHistoryId(2L);
        sddB.setCreatedAt(LocalDateTime.now());

        when(componentRepository.findAll()).thenReturn(List.of(implA, implB, sddA, sddB));

        SmartGoal goalA = new SmartGoal();
        goalA.setId(100L);
        goalA.setDescription("Goal A");
        goalA.setCreatedAt(LocalDateTime.now());

        SmartGoal goalB = new SmartGoal();
        goalB.setId(101L);
        goalB.setDescription("Goal B");
        goalB.setCreatedAt(LocalDateTime.now());

        when(goalRepository.findAll()).thenReturn(List.of(goalA, goalB));

        GoalComponentMapping mappingA1 = new GoalComponentMapping();
        mappingA1.setGoalId(100L);
        mappingA1.setComponentId(10L);
        GoalComponentMapping mappingA2 = new GoalComponentMapping();
        mappingA2.setGoalId(100L);
        mappingA2.setComponentId(12L);
        when(mappingRepository.findByGoalId(100L)).thenReturn(List.of(mappingA1, mappingA2));

        GoalComponentMapping mappingB1 = new GoalComponentMapping();
        mappingB1.setGoalId(101L);
        mappingB1.setComponentId(11L);
        GoalComponentMapping mappingB2 = new GoalComponentMapping();
        mappingB2.setGoalId(101L);
        mappingB2.setComponentId(13L);
        when(mappingRepository.findByGoalId(101L)).thenReturn(List.of(mappingB1, mappingB2));

        when(findingRepository.findByTeamCode(TEAM_A)).thenReturn(List.of());
        when(findingRepository.findByTeamCode(TEAM_B)).thenReturn(List.of());

        // Export audit report for team A
        byte[] reportA = auditExportService.exportAuditReport(TEAM_A, "json");
        JsonNode reportAJson = objectMapper.readTree(reportA);

        // Verify team A's data is present
        assertGoalDescriptionPresent(reportAJson, "Goal A");
        assertTrue(new String(reportA).contains(TEAM_A), "Team A's component should be in export");

        // Verify team B's data is NOT present (data leakage check)
        assertGoalDescriptionAbsent(reportAJson, "Goal B");
        assertFalse(new String(reportA).contains(TEAM_B), "Team B's component should NOT be in export");

        // Export audit report for team B
        byte[] reportB = auditExportService.exportAuditReport(TEAM_B, "json");
        JsonNode reportBJson = objectMapper.readTree(reportB);

        // Verify team B's data is present
        assertGoalDescriptionPresent(reportBJson, "Goal B");
        assertTrue(new String(reportB).contains(TEAM_B), "Team B's component should be in export");

        // Verify team A's data is NOT present (data leakage check)
        assertGoalDescriptionAbsent(reportBJson, "Goal A");
        assertFalse(new String(reportB).contains(TEAM_A), "Team A's component should NOT be in export");
    }

    private void assertGoalDescriptionPresent(JsonNode report, String description) {
        boolean found = false;
        for (JsonNode goal : report.get("goals")) {
            if (description.equals(goal.get("description").asText())) found = true;
        }
        assertTrue(found, "Expected goal '" + description + "' to be present in export");
    }

    private void assertGoalDescriptionAbsent(JsonNode report, String description) {
        for (JsonNode goal : report.get("goals")) {
            assertFalse(description.equals(goal.get("description").asText()),
                    "Did not expect goal '" + description + "' in export");
        }
    }

    @Test
    void exportCsvIncludesGoalsComponentsFindingsAndRecommendations() throws Exception {
        stubSingleTeamReport(TEAM_A);

        byte[] csvBytes = auditExportService.exportAuditReport(TEAM_A, "csv");
        String csv = new String(csvBytes);

        assertTrue(csv.contains("SMART Goals"), "CSV should include a Goals section");
        assertTrue(csv.contains("Goal A"), "CSV should include the goal description");
        assertTrue(csv.contains("Components"), "CSV should include a Components section");
        assertTrue(csv.contains("Design Document"), "CSV should include the component name");
        assertTrue(csv.contains("Continuity Findings"), "CSV should include a Findings section");
        assertTrue(csv.contains("Missing SDD coverage"), "CSV should include the finding description");
        assertTrue(csv.contains("Recommendations"), "CSV should include a Recommendations section");
        assertTrue(csv.contains("Add an SDD component"), "CSV should include the recommendation text");
    }

    @Test
    void exportPdfProducesAValidPdfDocument() throws Exception {
        stubSingleTeamReport(TEAM_A);

        byte[] pdfBytes = auditExportService.exportAuditReport(TEAM_A, "pdf");

        assertTrue(pdfBytes.length > 0, "PDF export should not be empty");
        String header = new String(pdfBytes, 0, 4);
        assertEquals("%PDF", header, "Output should start with the PDF magic header");
    }

    @Test
    void exportJsonRoundTripsDescriptionsContainingControlCharacters() throws Exception {
        // Tabs/quotes in AI- or user-authored text must not corrupt the exported JSON.
        String trickyDescription = "Missing SDD coverage\tfor \"Goal A\" — please review";
        stubSingleTeamReport(TEAM_A, trickyDescription);

        byte[] jsonBytes = auditExportService.exportAuditReport(TEAM_A, "json");
        JsonNode report = objectMapper.readTree(jsonBytes);

        String actualDescription = report.get("findings").get(0).get("description").asText();
        assertEquals(trickyDescription, actualDescription);
    }

    /** Wires one goal, one component, one finding, and one recommendation for {@code teamCode}. */
    private void stubSingleTeamReport(String teamCode) {
        stubSingleTeamReport(teamCode, "Missing SDD coverage");
    }

    private void stubSingleTeamReport(String teamCode, String findingDescription) {
        EvaluationHistory history = new EvaluationHistory();
        history.setId(1L);
        history.setFileName("[SDD] G01 - " + teamCode + " | Student A");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(history));

        TraceComponent component = new TraceComponent();
        component.setId(30L);
        component.setName("Design Document");
        component.setDocType(TraceComponent.DocType.SDD);
        component.setAiExtracted(true);
        component.setSourceHistoryId(1L);
        component.setCreatedAt(LocalDateTime.now());
        when(componentRepository.findAll()).thenReturn(List.of(component));

        SmartGoal goal = new SmartGoal();
        goal.setId(200L);
        goal.setDescription("Goal A");
        goal.setTeamCode(teamCode);
        goal.setCreatedAt(LocalDateTime.now());
        when(goalRepository.findAll()).thenReturn(List.of(goal));

        GoalComponentMapping mapping = new GoalComponentMapping();
        mapping.setGoalId(200L);
        mapping.setComponentId(30L);
        when(mappingRepository.findByGoalId(200L)).thenReturn(List.of(mapping));

        ContinuityFinding finding = new ContinuityFinding();
        finding.setId(40L);
        finding.setTeamCode(teamCode);
        finding.setSeverity(ContinuityFinding.Severity.HIGH);
        finding.setDocTypeFrom(TraceComponent.DocType.SRS);
        finding.setDocTypeTo(TraceComponent.DocType.SDD);
        finding.setDescription(findingDescription);
        finding.setDetectedAt(LocalDateTime.now());
        when(findingRepository.findByTeamCode(teamCode)).thenReturn(List.of(finding));

        DiagnosticRecommendation recommendation = new DiagnosticRecommendation();
        recommendation.setId(50L);
        recommendation.setFindingId(40L);
        recommendation.setRootCause("No design doc mapped");
        recommendation.setRecommendation("Add an SDD component");
        recommendation.setPriority("HIGH");
        recommendation.setCreatedAt(LocalDateTime.now());
        when(recommendationRepository.findAllById(List.of(40L))).thenReturn(List.of(recommendation));
    }

    @Test
    void testTeamComponentResolverFiltersCorrectly() {
        EvaluationHistory historyA = new EvaluationHistory();
        historyA.setId(1L);
        historyA.setFileName("[SRS] G01 - " + TEAM_A + " | Student A");
        historyA.setEvaluatedAt(LocalDateTime.now());
        // The catch-all stub must come first: Mockito favors the most recently defined
        // matching stub, so the specific id=1L case has to be layered on top of it.
        when(historyRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(historyRepository.findById(1L)).thenReturn(Optional.of(historyA));

        // IMPLEMENTATION component for team A
        TraceComponent implA = new TraceComponent();
        implA.setId(20L);
        implA.setName(TEAM_A + " - src/main.java");
        implA.setDocType(TraceComponent.DocType.IMPLEMENTATION);
        implA.setAiExtracted(true);
        implA.setCreatedAt(LocalDateTime.now());

        // IMPLEMENTATION component for team B
        TraceComponent implB = new TraceComponent();
        implB.setId(21L);
        implB.setName(TEAM_B + " - src/main.java");
        implB.setDocType(TraceComponent.DocType.IMPLEMENTATION);
        implB.setAiExtracted(true);
        implB.setCreatedAt(LocalDateTime.now());

        // SDD component for team A
        TraceComponent sddA = new TraceComponent();
        sddA.setId(22L);
        sddA.setName("SDD A");
        sddA.setDocType(TraceComponent.DocType.SDD);
        sddA.setAiExtracted(true);
        sddA.setSourceHistoryId(1L);
        sddA.setCreatedAt(LocalDateTime.now());

        // SDD component with no sourceHistoryId - should not match any team
        TraceComponent sddB = new TraceComponent();
        sddB.setId(23L);
        sddB.setName("SDD B");
        sddB.setDocType(TraceComponent.DocType.SDD);
        sddB.setAiExtracted(true);
        sddB.setCreatedAt(LocalDateTime.now());

        // Component with no team resolution pattern (SRS)
        TraceComponent srs = new TraceComponent();
        srs.setId(24L);
        srs.setName("SRS Document");
        srs.setDocType(TraceComponent.DocType.SRS);
        srs.setAiExtracted(true);
        srs.setCreatedAt(LocalDateTime.now());

        List<TraceComponent> allComponents = List.of(implA, implB, sddA, sddB, srs);
        List<TraceComponent> teamAComponents = teamComponentResolver.filterByTeam(allComponents, TEAM_A);

        // Should only include implA and sddA
        assertEquals(2, teamAComponents.size());
        assertTrue(teamAComponents.contains(implA));
        assertTrue(teamAComponents.contains(sddA));
        assertFalse(teamAComponents.contains(implB));
        assertFalse(teamAComponents.contains(sddB));
        assertFalse(teamAComponents.contains(srs));
    }
}
