package com.ieee.evaluator.synctrace.integration;

import com.ieee.evaluator.config.CorsConfig;
import com.ieee.evaluator.config.SecurityConfig;
import com.ieee.evaluator.model.StudentTrackerRecord;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.security.SupabaseJwtValidator;
import com.ieee.evaluator.service.AuthAllowlistService;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.service.SubmissionSyncService;
import com.ieee.evaluator.synctrace.controller.*;
import com.ieee.evaluator.synctrace.repository.*;
import com.ieee.evaluator.synctrace.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the SyncTrace HTTP layer.
 *
 * Unlike the existing controller tests (which call controller methods directly), these go through
 * the real servlet stack: SecurityConfig, JwtAuthenticationFilter, CORS, request mapping, JSON
 * (de)serialization, @ExceptionHandler advice and the real SyncTraceAccessGuard. Only the Supabase
 * token validator, the Sheets allowlist, and the services/repositories behind the controllers are
 * mocked, so no database, Drive, GitHub or AI call is made.
 *
 * Test IDs (IT-xx) map to the integration test case table in the report.
 */
@WebMvcTest(controllers = {
        AuditExportController.class, ContinuityController.class, GitHubController.class,
        MappingSuggestionController.class, ProposalAnalysisController.class, SmartGoalController.class,
        StagedMappingController.class, TraceComponentController.class})
@Import({SecurityConfig.class, CorsConfig.class, SyncTraceAccessGuard.class})
@TestPropertySource(properties = "app.cors.allowed-origins=https://synctrace.example.app")
class SyncTraceApiIntegrationTest {

    private static final String TEAM_A = "2627-SEM1-IT411-08";
    private static final String TEAM_B = "2627-SEM1-IT411-09";
    private static final String ORIGIN = "https://synctrace.example.app";

    @Autowired private MockMvc mvc;

    // --- Authentication collaborators (the only "outside world" pieces of the security chain)
    @MockitoBean private SupabaseJwtValidator jwtValidator;
    @MockitoBean private AuthAllowlistService allowlistService;

    // --- Service / repository layer behind the 8 controllers
    @MockitoBean private AuditExportService auditExportService;
    @MockitoBean private SourceCodeAlignmentService alignmentService;
    @MockitoBean private ContinuityGapDetectionService gapDetectionService;
    @MockitoBean private ContinuityReadinessService readinessService;
    @MockitoBean private DiagnosticRecommendationService recommendationService;
    @MockitoBean private ContinuityFindingRepository findingRepository;
    @MockitoBean private ContinuityAnalysisRunRepository analysisRunRepository;
    @MockitoBean private TeamAnalysisLock analysisLock;
    @MockitoBean private SubmissionSyncService submissionSyncService;
    @MockitoBean private GitHubIngestionService ingestionService;
    @MockitoBean private GitHubRepositoryLinkRepository repositoryLinkRepository;
    @MockitoBean private GitHubRepositoryLinkService repositoryLinkService;
    @MockitoBean private ProgressEmitter progressEmitter;
    @MockitoBean private MappingSuggestionService mappingSuggestionService;
    @MockitoBean private ProposalAnalysisService proposalAnalysisService;
    @MockitoBean private SmartGoalService smartGoalService;
    @MockitoBean private SmartGoalRepository smartGoalRepository;
    @MockitoBean private TraceComponentRepository traceComponentRepository;
    @MockitoBean private TeamComponentResolverService teamComponentResolver;
    @MockitoBean private TraceabilityMappingActivityRepository mappingActivityRepository;
    @MockitoBean private StagedMappingService stagedMappingService;
    @MockitoBean private TraceComponentService traceComponentService;
    @MockitoBean private EvaluationHistoryRepository evaluationHistoryRepository;

    @BeforeEach
    void signInKnownUsers() throws Exception {
        when(jwtValidator.validateToken("teacher-token")).thenReturn("teacher@cit.edu");
        when(allowlistService.verifyUser("teacher@cit.edu"))
                .thenReturn(new StudentTrackerRecord("Prof", "IT411", "N/A", "TEACHER"));

        when(jwtValidator.validateToken("student-token")).thenReturn("student@cit.edu");
        when(allowlistService.verifyUser("student@cit.edu"))
                .thenReturn(new StudentTrackerRecord("Student", "IT411", TEAM_A, "STUDENT"));

        when(jwtValidator.validateToken("no-team-token")).thenReturn("noteam@cit.edu");
        when(allowlistService.verifyUser("noteam@cit.edu"))
                .thenReturn(new StudentTrackerRecord("Loner", "IT411", "N/A", "STUDENT"));

        when(jwtValidator.validateToken("stranger-token")).thenReturn("stranger@gmail.com");
        when(allowlistService.verifyUser("stranger@gmail.com")).thenReturn(null);

        when(jwtValidator.validateToken("expired-token"))
                .thenThrow(new IllegalArgumentException("JWT expired"));
    }

    // ========================================================================
    // Authentication & authorization (Supabase JWT + Sheets allowlist)
    // ========================================================================

    /** IT-01: no Authorization header. The app returns 403 (not 401): no entry point is configured. */
    @Test
    void it01_noToken_isRejected() throws Exception {
        mvc.perform(get("/api/synctrace/components"))
                .andExpect(status().isForbidden());
    }

    /** IT-02: expired / invalid Supabase token behaves like no token. */
    @Test
    void it02_expiredToken_isRejected() throws Exception {
        mvc.perform(get("/api/synctrace/components").header("Authorization", "Bearer expired-token"))
                .andExpect(status().isForbidden());
    }

    /** IT-03: valid Supabase token, but the email is not on the Google Sheets allowlist. */
    @Test
    void it03_validTokenButNotOnAllowlist_isRejected() throws Exception {
        mvc.perform(get("/api/synctrace/components").header("Authorization", "Bearer stranger-token"))
                .andExpect(status().isForbidden());
    }

    /** IT-04: a malformed Authorization header (wrong scheme) is ignored, request stays anonymous. */
    @Test
    void it04_nonBearerScheme_isRejected() throws Exception {
        mvc.perform(get("/api/synctrace/components").header("Authorization", "Basic abc123"))
                .andExpect(status().isForbidden());
    }

    /** IT-05: every SyncTrace controller is protected, not just the components endpoint. */
    @Test
    void it05_allSyncTraceControllersRequireAuthentication() throws Exception {
        String[] getPaths = {
                "/api/synctrace/goals", "/api/synctrace/staged-mappings",
                "/api/synctrace/github/repositories", "/api/synctrace/github/teams",
                "/api/synctrace/continuity/summary/" + TEAM_A,
                "/api/synctrace/continuity/findings/" + TEAM_A,
                "/api/synctrace/audit/" + TEAM_A + "/export",
                "/api/synctrace/mapping-suggestions?teamCode=" + TEAM_A + "&stage=SRS_SDD"};
        for (String path : getPaths) {
            mvc.perform(get(path)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/synctrace/proposals/extract-goals")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    /** IT-06: a student with no team on file is refused with a JSON error, not a stack trace. */
    @Test
    void it06_studentWithoutTeam_getsForbiddenJson() throws Exception {
        mvc.perform(get("/api/synctrace/components").header("Authorization", "Bearer no-team-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No team is on file for this student."));
    }

    // ========================================================================
    // Cross-team isolation (student cannot read another team's data)
    // ========================================================================

    /** IT-07: student asks for TEAM_B's components; the controller still queries TEAM_A. */
    @Test
    void it07_studentComponents_areScopedToOwnTeam() throws Exception {
        when(traceComponentService.getComponents(any(), any(), eq(TEAM_A))).thenReturn(List.of());

        mvc.perform(get("/api/synctrace/components").param("teamCode", TEAM_B)
                        .header("Authorization", "Bearer student-token"))
                .andExpect(status().isOk());

        verify(traceComponentService).getComponents(null, null, TEAM_A);
    }

    /** IT-08: same rule for continuity findings. */
    @Test
    void it08_studentFindings_areScopedToOwnTeam() throws Exception {
        when(findingRepository.findByTeamCodeIgnoreCaseOrderByDetectedAtDescIdAsc(TEAM_A))
                .thenReturn(List.of());

        mvc.perform(get("/api/synctrace/continuity/findings/" + TEAM_B)
                        .header("Authorization", "Bearer student-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        verify(findingRepository).findByTeamCodeIgnoreCaseOrderByDetectedAtDescIdAsc(TEAM_A);
    }

    /** IT-09: same rule for the audit export, which carries the most data. */
    @Test
    void it09_studentAuditExport_isScopedToOwnTeam() throws Exception {
        when(auditExportService.exportAuditReport(TEAM_A, "json")).thenReturn("{}".getBytes());

        mvc.perform(get("/api/synctrace/audit/" + TEAM_B + "/export")
                        .header("Authorization", "Bearer student-token"))
                .andExpect(status().isOk());

        verify(auditExportService).exportAuditReport(TEAM_A, "json");
    }

    /** IT-10: a teacher is not limited to one team. */
    @Test
    void it10_teacherCanReadAnyTeam() throws Exception {
        when(traceComponentService.getComponents(any(), any(), eq(TEAM_B))).thenReturn(List.of());

        mvc.perform(get("/api/synctrace/components").param("teamCode", TEAM_B)
                        .header("Authorization", "Bearer teacher-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());

        verify(traceComponentService).getComponents(null, null, TEAM_B);
    }

    // ========================================================================
    // Request validation and HTTP status mapping
    // ========================================================================

    /** IT-11: SMART goals list returns the JSON array the frontend expects. */
    @Test
    void it11_goalsList_returnsJsonArray() throws Exception {
        when(smartGoalService.getAllGoalsWithCategoryStatus(TEAM_A))
                .thenReturn(List.of(Map.of("id", 1, "title", "Goal 1")));

        mvc.perform(get("/api/synctrace/goals").header("Authorization", "Bearer student-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].title").value("Goal 1"));
    }

    /** IT-12: goal extraction without a Drive file id is a 400, and no AI call is made. */
    @Test
    void it12_extractGoals_missingFileId_is400() throws Exception {
        mvc.perform(post("/api/synctrace/proposals/extract-goals")
                        .header("Authorization", "Bearer teacher-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamCode\":\"" + TEAM_A + "\",\"fileName\":\"Proposal.pdf\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Missing fileId or fileName"));
    }

    /** IT-13: goal extraction success returns goals + count. */
    @Test
    void it13_extractGoals_success() throws Exception {
        when(proposalAnalysisService.extractSmartGoals(eq("file-1"), eq("Proposal.pdf"), any(), any(), eq(TEAM_A)))
                .thenReturn(List.of(Map.of("title", "Goal 1")));

        mvc.perform(post("/api/synctrace/proposals/extract-goals")
                        .header("Authorization", "Bearer teacher-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamCode\":\"" + TEAM_A + "\",\"fileId\":\"file-1\",\"fileName\":\"Proposal.pdf\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.goals[0].title").value("Goal 1"));
    }

    /** IT-14: saving an empty reviewed goal list is a 400. */
    @Test
    void it14_saveGoals_empty_is400() throws Exception {
        mvc.perform(post("/api/synctrace/proposals/save-goals")
                        .header("Authorization", "Bearer teacher-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamCode\":\"" + TEAM_A + "\",\"goals\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("There are no SMART goals to save."));
    }

    /** IT-15: a team with no staged mappings gets an empty array, not an error. */
    @Test
    void it15_stagedMappings_emptyTeam_returnsEmptyArray() throws Exception {
        when(stagedMappingService.list(TEAM_A)).thenReturn(List.of());

        mvc.perform(get("/api/synctrace/staged-mappings").header("Authorization", "Bearer student-token"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    /** IT-16: adding staged mappings with no "mappings" array is a 400 with an error body. */
    @Test
    void it16_stagedMappings_add_withoutMappings_is400() throws Exception {
        mvc.perform(post("/api/synctrace/staged-mappings")
                        .header("Authorization", "Bearer teacher-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamCode\":\"" + TEAM_A + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("mappings is required"));
    }

    /** IT-17: AI provider outage during suggestion generation maps to 503 + AI_UNAVAILABLE. */
    @Test
    void it17_mappingSuggestions_aiDown_is503() throws Exception {
        when(mappingSuggestionService.generate(any(), any(), any()))
                .thenThrow(new MappingSuggestionService.AiUnavailableException("AI provider timed out"));

        mvc.perform(post("/api/synctrace/mapping-suggestions/generate")
                        .header("Authorization", "Bearer teacher-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamCode\":\"" + TEAM_A + "\",\"stage\":\"SRS_SDD\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
    }

    /** IT-18: pending suggestions list for a stage. */
    @Test
    void it18_mappingSuggestions_pendingList_is200() throws Exception {
        when(mappingSuggestionService.pending(TEAM_A, "SRS_SDD")).thenReturn(List.of());

        mvc.perform(get("/api/synctrace/mapping-suggestions")
                        .param("teamCode", TEAM_A).param("stage", "SRS_SDD")
                        .header("Authorization", "Bearer teacher-token"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    /** IT-19: linking a GitHub repo with no URL is a 400. */
    @Test
    void it19_github_link_missingUrl_is400() throws Exception {
        mvc.perform(post("/api/synctrace/github/repositories")
                        .header("Authorization", "Bearer teacher-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamCode\":\"" + TEAM_A + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("githubUrl is required."));
    }

    /** IT-20: linking a private / wrong repo surfaces as 400 with the service's message. */
    @Test
    void it20_github_link_invalidRepo_is400() throws Exception {
        when(repositoryLinkService.link(any(), eq("https://github.com/x/private"), any()))
                .thenThrow(new GitHubRepositoryLinkService.InvalidRepositoryException("Repository not accessible."));

        mvc.perform(post("/api/synctrace/github/repositories")
                        .header("Authorization", "Bearer teacher-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamCode\":\"" + TEAM_A + "\",\"githubUrl\":\"https://github.com/x/private\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Repository not accessible."));
    }

    /** IT-21: linking the same repo twice is a 409. */
    @Test
    void it21_github_link_duplicate_is409() throws Exception {
        when(repositoryLinkService.link(any(), eq("https://github.com/x/repo"), any()))
                .thenThrow(new GitHubRepositoryLinkService.DuplicateRepositoryException("Already linked."));

        mvc.perform(post("/api/synctrace/github/repositories")
                        .header("Authorization", "Bearer teacher-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamCode\":\"" + TEAM_A + "\",\"githubUrl\":\"https://github.com/x/repo\"}"))
                .andExpect(status().isConflict());
    }

    /** IT-22: continuity summary returns the readiness map as JSON. */
    @Test
    void it22_continuitySummary_is200() throws Exception {
        when(readinessService.getTeamReadinessSummary(TEAM_A)).thenReturn(Map.of("ready", false));

        mvc.perform(get("/api/synctrace/continuity/summary/" + TEAM_A)
                        .header("Authorization", "Bearer student-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false));
    }

    /** IT-23: continuity prerequisites not met (e.g. no mappings) surfaces as 409, not 500. */
    @Test
    void it23_continuityAlign_prerequisiteMissing_is409() throws Exception {
        when(alignmentService.analyzeAlignment(eq(TEAM_A), any(), any()))
                .thenThrow(new IllegalStateException("No staged mappings found for this team."));

        mvc.perform(post("/api/synctrace/continuity/align")
                        .header("Authorization", "Bearer student-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamCode\":\"" + TEAM_A + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("No staged mappings found for this team."));
    }

    // ========================================================================
    // Audit export formats
    // ========================================================================

    /** IT-24: JSON export has the right content type and attachment filename. */
    @Test
    void it24_auditExport_json() throws Exception {
        when(auditExportService.exportAuditReport(TEAM_A, "json")).thenReturn("{\"team\":\"x\"}".getBytes());

        mvc.perform(get("/api/synctrace/audit/" + TEAM_A + "/export")
                        .header("Authorization", "Bearer teacher-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("attachment"),
                                org.hamcrest.Matchers.containsString("audit-report-" + TEAM_A),
                                org.hamcrest.Matchers.endsWith(".json\""))));
    }

    /** IT-25: CSV export. */
    @Test
    void it25_auditExport_csv() throws Exception {
        when(auditExportService.exportAuditReport(TEAM_A, "csv")).thenReturn("a,b\n1,2".getBytes());

        mvc.perform(get("/api/synctrace/audit/" + TEAM_A + "/export").param("format", "CSV")
                        .header("Authorization", "Bearer teacher-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string("a,b\n1,2"));
    }

    /** IT-26: PDF export. */
    @Test
    void it26_auditExport_pdf() throws Exception {
        when(auditExportService.exportAuditReport(TEAM_A, "pdf")).thenReturn("%PDF-1.4".getBytes());

        mvc.perform(get("/api/synctrace/audit/" + TEAM_A + "/export").param("format", "pdf")
                        .header("Authorization", "Bearer teacher-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    /** IT-27: an unsupported format is a 400 with an error body. */
    @Test
    void it27_auditExport_unsupportedFormat_is400() throws Exception {
        when(auditExportService.exportAuditReport(TEAM_A, "xml"))
                .thenThrow(new IllegalArgumentException("Unsupported format: xml"));

        mvc.perform(get("/api/synctrace/audit/" + TEAM_A + "/export").param("format", "xml")
                        .header("Authorization", "Bearer teacher-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unsupported format: xml"));
    }

    // ========================================================================
    // CORS (Vercel frontend -> Render backend)
    // ========================================================================

    /** IT-28: preflight from the configured frontend origin is allowed. */
    @Test
    void it28_cors_allowedOrigin_preflightPasses() throws Exception {
        mvc.perform(options("/api/synctrace/components")
                        .header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
    }

    /** IT-29: preflight from any other origin is rejected. */
    @Test
    void it29_cors_unknownOrigin_preflightRejected() throws Exception {
        mvc.perform(options("/api/synctrace/components")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
