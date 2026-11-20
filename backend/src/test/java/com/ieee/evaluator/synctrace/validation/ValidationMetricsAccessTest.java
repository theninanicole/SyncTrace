package com.ieee.evaluator.synctrace.validation;

import com.ieee.evaluator.security.AuthenticatedUser;
import com.ieee.evaluator.service.SubmissionSyncService;
import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import com.ieee.evaluator.synctrace.validation.config.ValidationProperties;
import com.ieee.evaluator.synctrace.validation.controller.ValidationMetricsController;
import com.ieee.evaluator.synctrace.validation.repository.ValidationSessionRepository;
import com.ieee.evaluator.synctrace.validation.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The page's data is teacher-only, enforced by the server (a student gets 403 everywhere). */
class ValidationMetricsAccessTest {

    private ValidationSummaryService summaryService;
    private ValidationExportService exportService;
    private ValidationSessionRepository sessionRepository;
    private ValidationRecorder recorder;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        summaryService = mock(ValidationSummaryService.class);
        exportService = mock(ValidationExportService.class);
        sessionRepository = mock(ValidationSessionRepository.class);
        recorder = mock(ValidationRecorder.class);
        when(summaryService.summarize(any(ValidationFilter.class))).thenReturn(List.of());
        when(exportService.exportZip(any())).thenReturn(new byte[] {80, 75});
        when(exportService.exportExcel(any())).thenReturn(new byte[] {80, 75});
        when(sessionRepository.existsById(anyLong())).thenReturn(true);

        ValidationMetricsController controller = new ValidationMetricsController(new SyncTraceAccessGuard(),
            summaryService, exportService, sessionRepository, recorder, ValidationProperties.defaults(),
            mock(SubmissionSyncService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void clearUser() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String role, String groupCode) {
        AuthenticatedUser user = new AuthenticatedUser("someone@example.edu", role, groupCode, "uuid-" + role);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Test
    void studentsGet403OnEveryMetricsEndpoint() throws Exception {
        signIn("STUDENT", "TEAM-S");
        String base = "/api/synctrace/validation";

        mvc.perform(get(base + "/summary")).andExpect(status().isForbidden());
        mvc.perform(get(base + "/summary").param("teamCodes", "TEAM-S")).andExpect(status().isForbidden());
        mvc.perform(get(base + "/export").param("format", "zip")).andExpect(status().isForbidden());
        mvc.perform(get(base + "/export").param("format", "xlsx")).andExpect(status().isForbidden());
        mvc.perform(get(base + "/targets")).andExpect(status().isForbidden());
        mvc.perform(get(base + "/teams")).andExpect(status().isForbidden());
        mvc.perform(get(base + "/sessions")).andExpect(status().isForbidden());
        mvc.perform(post(base + "/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"startsAt\":\"2026-11-25T01:00:00Z\",\"endsAt\":\"2026-11-25T03:00:00Z\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete(base + "/sessions/1")).andExpect(status().isForbidden());

        verify(exportService, never()).exportZip(any());
        verify(exportService, never()).exportExcel(any());
        verify(summaryService, never()).summarize(any(ValidationFilter.class));
        verify(sessionRepository, never()).save(any());
        verify(sessionRepository, never()).deleteById(any());
    }

    @Test
    void teachersCanReadAndExport() throws Exception {
        signIn("TEACHER", null);
        mvc.perform(get("/api/synctrace/validation/summary").param("teamCodes", "TEAM-A,TEAM-B"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.targets.m1MaxMs").value(30000));
        mvc.perform(get("/api/synctrace/validation/export").param("format", "zip"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/zip"));
        mvc.perform(get("/api/synctrace/validation/export").param("format", "xlsx"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/synctrace/validation/export").param("format", "pdf"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void aStudentHeartbeatIsForcedOntoTheirOwnTeam() throws Exception {
        signIn("STUDENT", "TEAM-S");
        mvc.perform(post("/api/synctrace/validation/stage-heartbeat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamCode\":\"SOMEONE-ELSE\",\"stage\":\"SRS_SDD\",\"state\":\"visible\"}"))
            .andExpect(status().isNoContent());
        verify(recorder).heartbeat("TEAM-S", "srs_sdd", true);
    }

    @Test
    void anUnknownStageIsRejected() throws Exception {
        signIn("TEACHER", null);
        mvc.perform(post("/api/synctrace/validation/stage-heartbeat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamCode\":\"TEAM-A\",\"stage\":\"NOPE\"}"))
            .andExpect(status().isBadRequest());
        verify(recorder, never()).heartbeat(any(), any(), anyBoolean());
    }
}
