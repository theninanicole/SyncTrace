package com.ieee.evaluator.synctrace.validation;

import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import com.ieee.evaluator.synctrace.validation.model.ValidationEvent;
import com.ieee.evaluator.synctrace.validation.model.ValidationOperation;
import com.ieee.evaluator.synctrace.validation.service.ValidationContext;
import com.ieee.evaluator.synctrace.validation.service.ValidationRecorder;
import com.ieee.evaluator.synctrace.validation.service.ValidationTiming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ValidationTimingTest {

    private ValidationRecorder recorder;
    private ValidationTiming timing;

    @BeforeEach
    void setUp() {
        recorder = mock(ValidationRecorder.class);
        SyncTraceAccessGuard guard = mock(SyncTraceAccessGuard.class);
        when(guard.currentUserId()).thenReturn("user-1");
        timing = new ValidationTiming(recorder, guard);
    }

    private ValidationEvent recorded() {
        ArgumentCaptor<ValidationEvent> event = ArgumentCaptor.forClass(ValidationEvent.class);
        verify(recorder).recordEvent(event.capture());
        return event.getValue();
    }

    @Test
    void successRecordsMonotonicDurationAndItemCount() throws Exception {
        List<Integer> result = timing.time(ValidationOperation.GOAL_EXTRACTION, ValidationContext.forTeam(" TEAM-1 "),
            () -> {
                Thread.sleep(25);
                return List.of(1, 2, 3);
            }, List::size);

        assertEquals(List.of(1, 2, 3), result);
        ValidationEvent event = recorded();
        assertEquals("M1", event.getMetricCode());
        assertEquals("GOAL_EXTRACTION", event.getOperation());
        assertEquals("TEAM-1", event.getTeamId());
        assertEquals("user-1", event.getUserId());
        assertEquals(ValidationEvent.SUCCESS, event.getStatus());
        assertTrue(event.getDurationMs() >= 25, "duration " + event.getDurationMs());
        assertEquals(event.getStartedAt().plusMillis(event.getDurationMs()), event.getEndedAt());
        assertEquals(3, event.getItemCount());
        assertNull(event.getErrorMessage());
    }

    @Test
    void failureIsRecordedAndTheSameExceptionIsRethrown() {
        IllegalStateException boom = new IllegalStateException("AI returned garbage");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> timing.time(ValidationOperation.RECOMMENDATIONS, ValidationContext.forTeam("T"), () -> {
                throw boom;
            }));

        assertSame(boom, thrown);
        ValidationEvent event = recorded();
        assertEquals("M6", event.getMetricCode());
        assertEquals(ValidationEvent.FAILED, event.getStatus());
        assertTrue(event.getErrorMessage().contains("AI returned garbage"));
        assertNull(event.getItemCount());
    }

    @Test
    void checkedExceptionsPassThroughUnchanged() {
        IOException io = new IOException("disk");
        IOException thrown = assertThrows(IOException.class,
            () -> timing.time(ValidationOperation.AUDIT_EXPORT, new ValidationContext(), () -> {
                throw io;
            }));
        assertSame(io, thrown);
        assertEquals(ValidationEvent.FAILED, recorded().getStatus());
    }

    @Test
    void socketTimeoutAnywhereInTheCauseChainIsATimeout() {
        assertThrows(RuntimeException.class, () -> timing.time(ValidationOperation.REPO_INGESTION,
            new ValidationContext(), () -> {
                throw new RuntimeException("GitHub call failed", new SocketTimeoutException("Read timed out"));
            }));
        assertEquals(ValidationEvent.TIMEOUT, recorded().getStatus());
    }

    @Test
    void retryBudgetExhaustionIsATimeout() {
        assertThrows(IllegalStateException.class, () -> timing.time(ValidationOperation.GOAL_EXTRACTION,
            new ValidationContext(), () -> {
                throw new IllegalStateException("PROPOSAL ANALYSIS ERROR: Retry time limit exceeded. Please try again.");
            }));
        assertEquals(ValidationEvent.TIMEOUT, recorded().getStatus());
    }

    @Test
    void requestsRejectedBeforeAnyWorkAreNotRuns() {
        assertThrows(SyncTraceAccessGuard.AccessDeniedException.class, () -> timing.time(
            ValidationOperation.GOAL_EXTRACTION, new ValidationContext(), () -> {
                throw new SyncTraceAccessGuard.AccessDeniedException("no");
            }));
        assertThrows(IllegalStateException.class, () -> timing.time(
            ValidationOperation.GOAL_EXTRACTION, new ValidationContext(), () -> {
                throw new IllegalStateException("An analysis is already in progress for this file and provider.");
            }));
        assertThrows(IllegalArgumentException.class, () -> timing.time(ValidationOperation.AUDIT_EXPORT,
            new ValidationContext().notARunWhen(e -> e instanceof IllegalArgumentException), () -> {
                throw new IllegalArgumentException("Unsupported format: doc");
            }));
        verify(recorder, never()).recordEvent(any());
    }

    @Test
    void aBrokenRecorderNeverBreaksTheUsersAction() throws Exception {
        doThrow(new RuntimeException("database down")).when(recorder).recordEvent(any());

        String result = timing.time(ValidationOperation.AUDIT_EXPORT, new ValidationContext(), () -> "report");

        assertEquals("report", result);
    }

    @Test
    void aBrokenItemCounterOnlyLosesTheCount() throws Exception {
        String result = timing.time(ValidationOperation.AUDIT_EXPORT, new ValidationContext(), () -> "report",
            value -> { throw new IllegalStateException("bad counter"); });
        assertEquals("report", result);
        assertNull(recorded().getItemCount());
    }

    @Test
    void passThroughRecordsNothing() throws Exception {
        assertEquals(7, ValidationTiming.passThrough().time(ValidationOperation.GOAL_EXTRACTION,
            new ValidationContext(), () -> 7));
    }
}
