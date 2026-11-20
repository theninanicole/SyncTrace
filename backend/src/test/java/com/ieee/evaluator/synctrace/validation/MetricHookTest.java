package com.ieee.evaluator.synctrace.validation;

import com.ieee.evaluator.synctrace.controller.AuditExportController;
import com.ieee.evaluator.synctrace.service.AuditExportService;
import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import com.ieee.evaluator.synctrace.validation.model.ValidationEvent;
import com.ieee.evaluator.synctrace.validation.service.ValidationRecorder;
import com.ieee.evaluator.synctrace.validation.service.ValidationTiming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A hooked endpoint records one event per call and responds exactly as before. */
class MetricHookTest {

    private AuditExportService auditExportService;
    private ValidationRecorder recorder;
    private AuditExportController controller;

    @BeforeEach
    void setUp() throws Exception {
        auditExportService = mock(AuditExportService.class);
        SyncTraceAccessGuard guard = mock(SyncTraceAccessGuard.class);
        when(guard.resolveEffectiveTeamCode("TEAM-A")).thenReturn("TEAM-A");
        when(guard.currentUserId()).thenReturn("uuid-teacher");
        recorder = mock(ValidationRecorder.class);
        controller = new AuditExportController(auditExportService, guard);
        Method setter = AuditExportController.class.getDeclaredMethod("setValidationTiming", ValidationTiming.class);
        setter.setAccessible(true);
        setter.invoke(controller, new ValidationTiming(recorder, guard));
    }

    @Test
    void exportRecordsAnM8EventWithItsFormat() throws Exception {
        when(auditExportService.exportAuditReport("TEAM-A", "pdf")).thenReturn(new byte[1234]);

        ResponseEntity<?> response = controller.exportAuditReport("TEAM-A", "PDF");

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<ValidationEvent> event = ArgumentCaptor.forClass(ValidationEvent.class);
        verify(recorder).recordEvent(event.capture());
        assertEquals("M8", event.getValue().getMetricCode());
        assertEquals("pdf", event.getValue().getExportFormat());
        assertEquals("TEAM-A", event.getValue().getTeamId());
        assertEquals("uuid-teacher", event.getValue().getUserId());
        assertEquals(1234, event.getValue().getItemCount());
        assertEquals(ValidationEvent.SUCCESS, event.getValue().getStatus());
    }

    @Test
    void aFailingRecorderLeavesTheResponseUnchanged() throws Exception {
        byte[] report = {1, 2, 3};
        when(auditExportService.exportAuditReport("TEAM-A", "csv")).thenReturn(report);
        doThrow(new RuntimeException("metrics DB down")).when(recorder).recordEvent(any());

        ResponseEntity<?> response = controller.exportAuditReport("TEAM-A", "csv");

        assertEquals(200, response.getStatusCode().value());
        assertSame(report, response.getBody());
    }

    @Test
    void aFailedExportIsRecordedAndStillReturns500() throws Exception {
        when(auditExportService.exportAuditReport("TEAM-A", "json")).thenThrow(new RuntimeException("render failed"));

        ResponseEntity<?> response = controller.exportAuditReport("TEAM-A", "json");

        assertEquals(500, response.getStatusCode().value());
        ArgumentCaptor<ValidationEvent> event = ArgumentCaptor.forClass(ValidationEvent.class);
        verify(recorder).recordEvent(event.capture());
        assertEquals(ValidationEvent.FAILED, event.getValue().getStatus());
    }

    @Test
    void anUnsupportedFormatIsABadRequestNotARun() throws Exception {
        when(auditExportService.exportAuditReport("TEAM-A", "doc"))
            .thenThrow(new IllegalArgumentException("Unsupported format: doc"));

        ResponseEntity<?> response = controller.exportAuditReport("TEAM-A", "doc");

        assertEquals(400, response.getStatusCode().value());
        verify(recorder, never()).recordEvent(any());
    }
}
