package com.ieee.evaluator.synctrace.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.ContinuityAnalysisRun;
import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.DiagnosticRecommendation;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.model.TraceabilityMappingActivity;
import com.ieee.evaluator.synctrace.repository.ContinuityAnalysisRunRepository;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.DiagnosticRecommendationRepository;
import com.ieee.evaluator.synctrace.repository.TraceabilityMappingActivityRepository;
import com.ieee.evaluator.synctrace.service.TraceabilityClusterService.GoalCluster;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (no Spring context). The exported matrix must show exactly what the matrix on
 * the Traceability Results page shows: only document columns mapped in some goal, the same chip
 * labels, the same Passed / Failed / Not analyzed status, and nothing from an analysis that the
 * mapping has since outdated.
 */
@ExtendWith(MockitoExtension.class)
class AuditExportServiceTest {

    private static final String TEAM = "2627-sem2-it411-08";
    private static final LocalDateTime ANALYZED_AT = LocalDateTime.of(2026, 9, 27, 10, 0);

    @Mock private TraceabilityClusterService clusterService;
    @Mock private ContinuityReadinessService readinessService;
    @Mock private ContinuityFindingRepository findingRepository;
    @Mock private DiagnosticRecommendationRepository recommendationRepository;
    @Mock private ContinuityAnalysisRunRepository analysisRunRepository;
    @Mock private TraceabilityMappingActivityRepository mappingActivityRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private AuditExportService service;

    @BeforeEach
    void setUp() {
        service = new AuditExportService(clusterService, readinessService, findingRepository,
            recommendationRepository, analysisRunRepository, mappingActivityRepository, objectMapper);
        lenient().when(readinessService.getTeamReadinessSummary(TEAM)).thenReturn(Map.of(
            "status", "AT_RISK", "readinessScore", 45, "averageCoveragePercent", 50,
            "severityCounts", Map.of("HIGH", 1)));
        lenient().when(mappingActivityRepository.findTopByTeamCodeIgnoreCaseOrderByPerformedAtDesc(TEAM))
            .thenReturn(Optional.empty());
    }

    @Test
    void jsonMatrixHasOnlyTheColumnsShownOnTheResultsPage() throws Exception {
        stubTwoGoalTeam(true, "SMART Goal is missing required SRS ACTIVITY component.");

        JsonNode report = objectMapper.readTree(service.exportAuditReport(TEAM, "json"));

        JsonNode matrix = report.get("traceabilityMatrix");
        // SPMP, STD and Implementation have nothing mapped in any goal, so the page hides them.
        assertEquals("[\"SRS\",\"SDD\"]", matrix.get("columns").toString());
        JsonNode rows = matrix.get("rows");
        assertEquals(2, rows.size());
        assertEquals("G1", rows.get(0).get("code").asText());
        assertEquals("Build the login module", rows.get(0).get("goal").asText());
        assertEquals("UC-01", rows.get(0).get("cells").get("SRS").get(0).get("label").asText());
        assertEquals("AD-01", rows.get(0).get("cells").get("SRS").get(1).get("label").asText());
        assertEquals(0, rows.get(0).get("cells").get("SDD").size());
        assertFalse(rows.get(0).get("cells").has("SPMP"));
        assertEquals("Failed", rows.get(0).get("status").asText());
        assertEquals("Passed", rows.get(1).get("status").asText());

        JsonNode finding = report.get("findings").get(0);
        assertEquals("G1", finding.get("goal").asText());
        assertEquals("Add the missing activity diagram.", finding.get("recommendation").get("recommendation").asText());
        assertEquals(3, report.get("mappedComponents").size());
    }

    @Test
    void goalsShowNotAnalyzedUntilAnalysisHasRun() throws Exception {
        stubTwoGoalTeam(false, null);

        JsonNode report = objectMapper.readTree(service.exportAuditReport(TEAM, "json"));

        assertEquals("Not analyzed", report.get("traceabilityMatrix").get("rows").get(0).get("status").asText());
        assertTrue(report.get("lastAnalyzedAt").isNull());
    }

    @Test
    void analysisOutdatedByALaterMappingChangeIsNotShown() throws Exception {
        stubTwoGoalTeam(true, "SMART Goal is missing required SRS ACTIVITY component.");
        TraceabilityMappingActivity activity = new TraceabilityMappingActivity();
        activity.setTeamCode(TEAM);
        activity.setPerformedAt(ANALYZED_AT.plusHours(2));
        when(mappingActivityRepository.findTopByTeamCodeIgnoreCaseOrderByPerformedAtDesc(TEAM))
            .thenReturn(Optional.of(activity));

        JsonNode report = objectMapper.readTree(service.exportAuditReport(TEAM, "json"));

        // Same as the Results page: statuses fall back to Not analyzed and findings are hidden.
        assertTrue(report.get("analysisOutdated").asBoolean());
        assertEquals("Not analyzed", report.get("traceabilityMatrix").get("rows").get(0).get("status").asText());
        assertEquals(0, report.get("findings").size());

        String csv = csvText(service.exportAuditReport(TEAM, "csv"));
        assertTrue(csv.contains("out of date"), csv);
        assertTrue(csv.contains("The mapping changed after the last AI Analysis"), csv);
    }

    @Test
    void csvMatrixMatchesTheResultsPage() throws Exception {
        stubTwoGoalTeam(true, "SMART Goal is missing required SRS ACTIVITY component.");

        byte[] bytes = service.exportAuditReport(TEAM, "csv");

        assertEquals((byte) 0xEF, bytes[0]);
        String csv = csvText(bytes);
        assertTrue(csv.contains("Readiness status,At Risk"), csv);
        assertTrue(csv.contains("Traceability Matrix\nGoal,SMART Goal,SRS,SDD,Status\n"), csv);
        assertTrue(csv.contains("G1,Build the login module,\"UC-01, AD-01\",Missing,Failed\n"), csv);
        assertTrue(csv.contains("G2,Generate reports,Missing,CL-01,Passed\n"), csv);
        assertFalse(csv.contains("SPMP,STD"), csv);
        assertTrue(csv.contains("G1,HIGH,Proposal,SRS,SMART Goal is missing required SRS ACTIVITY component."), csv);
        assertTrue(csv.contains("UC-01,UC-01 Login,SRS,Use Case,G1"), csv);
    }

    @Test
    void noMappedDocumentsShowsTheSameMessageAsThePage() throws Exception {
        SmartGoal goal = goal(1L, "Build the login module", SmartGoal.GoalKind.GENERAL, null);
        when(clusterService.clustersForTeam(TEAM)).thenReturn(List.of(new GoalCluster(goal, List.of(), Map.of())));
        when(analysisRunRepository.findByTeamCodeIgnoreCase(TEAM)).thenReturn(Optional.empty());

        String csv = csvText(service.exportAuditReport(TEAM, "csv"));
        JsonNode report = objectMapper.readTree(service.exportAuditReport(TEAM, "json"));

        assertTrue(csv.contains("Traceability Matrix\nNo mapped documents to display.\n"), csv);
        assertEquals("No mapped documents to display.", report.get("traceabilityMatrix").get("message").asText());
        assertEquals(0, report.get("traceabilityMatrix").get("rows").size());
    }

    @Test
    void pdfShowsTheMatrixWithOnlyVisibleColumns() throws Exception {
        stubTwoGoalTeam(true, "SMART Goal is missing required SRS ACTIVITY component.");

        String content = pdfText(service.exportAuditReport(TEAM, "pdf"));

        assertTrue(content.contains("Traceability Matrix"), content);
        assertTrue(content.contains("Build the login module"), content);
        assertTrue(content.contains("UC-01, AD-01"), content);
        assertTrue(content.contains("CL-01"), content);
        assertTrue(content.contains("Failed"), content);
        assertFalse(content.contains("SPMP"), content);
        assertFalse(content.contains("Implementation"), content);
        assertTrue(content.contains("Add the missing activity diagram."), content);
    }

    @Test
    void chipLabelsFollowTheFrontendRules() {
        assertEquals("UC-01", new AuditExportService.ComponentRef(1L, "UC-01", "Login", "SRS", "USE_CASE").label());
        // componentLabel() in the frontend gives "UC-3" here (no zero padding).
        assertEquals("UC-3", new AuditExportService.ComponentRef(1L, null, "uc 3 Register account", "SRS", "USE_CASE").label());
        assertEquals("Short name", new AuditExportService.ComponentRef(1L, "", "Short name", "SRS", "USE_CASE").label());
        // name.slice(0, 27) + '…', exactly as componentLabel() truncates.
        assertEquals("A very long component name …",
            new AuditExportService.ComponentRef(1L, null, "A very long component name that keeps going", "SRS", "OTHER_SRS").label());
    }

    @Test
    void jsonRoundTripsDescriptionsContainingControlCharacters() throws Exception {
        String tricky = "Missing SDD coverage\tfor \"Goal A\" — please review";
        stubTwoGoalTeam(true, tricky);

        JsonNode report = objectMapper.readTree(service.exportAuditReport(TEAM, "json"));

        assertEquals(tricky, report.get("findings").get(0).get("description").asText());
    }

    @Test
    void rejectsUnsupportedFormat() {
        assertThrows(IllegalArgumentException.class, () -> service.exportAuditReport(TEAM, "xml"));
    }

    @Test
    void humanizeKeepsAcronymsAndTitleCasesWords() {
        assertEquals("On Track", AuditExportService.humanize("ON_TRACK"));
        assertEquals("At Risk", AuditExportService.humanize("AT_RISK"));
        assertEquals("Use Case", AuditExportService.humanize("USE_CASE"));
        assertEquals("Other SRS", AuditExportService.humanize("OTHER_SRS"));
        assertEquals("UI", AuditExportService.humanize("UI"));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static String csvText(byte[] bytes) {
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }

    private static String pdfText(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        StringBuilder text = new StringBuilder();
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            text.append(extractor.getTextFromPage(page)).append('\n');
        }
        reader.close();
        return text.toString();
    }

    /** G1 (GENERAL + one SPECIFIC) with UC-01 and AD-01 in SRS; G2 with CL-01 in SDD. */
    private void stubTwoGoalTeam(boolean analyzed, String findingDescription) {
        SmartGoal login = goal(1L, "Build the login module", SmartGoal.GoalKind.GENERAL, null);
        SmartGoal validate = goal(2L, "Validate credentials", SmartGoal.GoalKind.SPECIFIC, 1L);
        SmartGoal reports = goal(3L, "Generate reports", SmartGoal.GoalKind.GENERAL, null);

        Map<DocType, List<TraceComponent>> g1 = new EnumMap<>(DocType.class);
        g1.put(DocType.SRS, List.of(
            component(10L, "UC-01", "UC-01 Login", DocType.SRS, ArtifactKind.USE_CASE),
            component(11L, "AD-01", "AD-01 Login flow", DocType.SRS, ArtifactKind.ACTIVITY)));
        Map<DocType, List<TraceComponent>> g2 = new EnumMap<>(DocType.class);
        g2.put(DocType.SDD, List.of(component(20L, "CL-01", "CL-01 Report classes", DocType.SDD, ArtifactKind.CLASS)));

        when(clusterService.clustersForTeam(TEAM)).thenReturn(List.of(
            new GoalCluster(login, List.of(validate), g1),
            new GoalCluster(reports, List.of(), g2)));

        if (analyzed) {
            ContinuityAnalysisRun run = new ContinuityAnalysisRun();
            run.setTeamCode(TEAM);
            run.setLastAnalyzedAt(ANALYZED_AT);
            when(analysisRunRepository.findByTeamCodeIgnoreCase(TEAM)).thenReturn(Optional.of(run));
        } else {
            when(analysisRunRepository.findByTeamCodeIgnoreCase(TEAM)).thenReturn(Optional.empty());
        }

        if (findingDescription == null) {
            lenient().when(findingRepository.findByTeamCodeIgnoreCaseOrderByDetectedAtDescIdAsc(TEAM)).thenReturn(List.of());
            return;
        }
        ContinuityFinding finding = new ContinuityFinding();
        finding.setId(40L);
        finding.setTeamCode(TEAM);
        finding.setGoalId(1L);
        finding.setSeverity(ContinuityFinding.Severity.HIGH);
        finding.setDocTypeFrom(DocType.PROPOSAL);
        finding.setDocTypeTo(DocType.SRS);
        finding.setDescription(findingDescription);
        lenient().when(findingRepository.findByTeamCodeIgnoreCaseOrderByDetectedAtDescIdAsc(TEAM)).thenReturn(List.of(finding));

        DiagnosticRecommendation rec = new DiagnosticRecommendation();
        rec.setId(50L);
        rec.setFindingId(40L);
        rec.setRootCause("This happened because no activity diagram is mapped.");
        rec.setRecommendation("Add the missing activity diagram.");
        rec.setPriority("HIGH");
        lenient().when(recommendationRepository.findByFindingIdIn(anyList())).thenReturn(List.of(rec));
    }

    private static SmartGoal goal(long id, String description, SmartGoal.GoalKind kind, Long parentId) {
        SmartGoal goal = new SmartGoal();
        goal.setId(id);
        goal.setDescription(description);
        goal.setGoalKind(kind);
        goal.setParentGoalId(parentId);
        goal.setTeamCode(TEAM);
        return goal;
    }

    private static TraceComponent component(long id, String code, String name, DocType docType, ArtifactKind kind) {
        TraceComponent component = new TraceComponent();
        component.setId(id);
        component.setCodeName(code);
        component.setName(name);
        component.setDocType(docType);
        component.setArtifactKind(kind);
        return component;
    }
}
