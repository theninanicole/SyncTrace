package com.ieee.evaluator.synctrace.validation.service;

import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import com.ieee.evaluator.synctrace.validation.model.ValidationEvent;
import com.ieee.evaluator.synctrace.validation.model.ValidationOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.function.ToIntFunction;

/**
 * withValidationTiming: wraps one server operation and records it as a validation event.
 *
 * <p>Timing is server-side and monotonic ({@link System#nanoTime()}); {@code started_at} /
 * {@code ended_at} are wall-clock timestamps for filtering. Successful, failed and timed-out
 * runs are all recorded. The original result or exception passes through untouched, and a
 * failure to record a metric is logged and swallowed: it never changes the user's action.
 */
@Slf4j
@Component
public class ValidationTiming {

    private static final int MAX_ERROR_LENGTH = 2_000;
    private static final ValidationTiming PASS_THROUGH = new ValidationTiming(null, null, Clock.systemUTC());

    private final ValidationRecorder recorder;
    private final SyncTraceAccessGuard accessGuard;
    private final Clock clock;

    @Autowired
    public ValidationTiming(ValidationRecorder recorder, SyncTraceAccessGuard accessGuard) {
        this(recorder, accessGuard, Clock.systemUTC());
    }

    ValidationTiming(ValidationRecorder recorder, SyncTraceAccessGuard accessGuard, Clock clock) {
        this.recorder = recorder;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    /** Runs the work without recording anything (for code constructed outside Spring, e.g. tests). */
    public static ValidationTiming passThrough() {
        return PASS_THROUGH;
    }

    public <T> T time(ValidationOperation operation, ValidationContext context, Callable<T> work) throws Exception {
        return time(operation, context, work, null);
    }

    public <T> T time(ValidationOperation operation, ValidationContext context, Callable<T> work,
            ToIntFunction<T> itemCount) throws Exception {
        if (recorder == null) {
            return work.call();
        }
        ValidationContext ctx = context == null ? new ValidationContext() : context;
        // The caller's identity lives in the request thread; read it before any hand-off.
        if (ctx.userId() == null) ctx.userId(safeUserId());

        Instant startedAt = clock.instant();
        long startNanos = System.nanoTime();
        T result = null;
        Throwable failure = null;
        try {
            result = work.call();
            return result;
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            try {
                String status = statusOf(failure, ctx);
                if (status != null) {
                    recorder.recordEvent(buildEvent(operation, ctx, startedAt, durationMs, status,
                        failure, failure == null ? countItems(result, itemCount) : null));
                }
            } catch (RuntimeException recordError) {
                log.warn("Validation metric {} could not be recorded: {}", operation, recordError.getMessage());
            }
        }
    }

    /**
     * @return success | failed | timeout, or null when the error means the request never ran
     *         (access denied, a duplicate already in progress, or a context-specific rejection)
     */
    public static String statusOf(Throwable failure, ValidationContext context) {
        if (failure == null) return ValidationEvent.SUCCESS;
        if (failure instanceof SyncTraceAccessGuard.AccessDeniedException) return null;
        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("already in progress")) return null;
        if (context != null && context.isNotARun(failure)) return null;
        return isTimeout(failure) ? ValidationEvent.TIMEOUT : ValidationEvent.FAILED;
    }

    static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException
                    || cause instanceof TimeoutException) {
                return true;
            }
            String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("time limit exceeded") || message.contains("timed out")
                    || message.contains("read timeout") || message.contains("deadline")) {
                return true;
            }
        }
        return false;
    }

    private ValidationEvent buildEvent(ValidationOperation operation, ValidationContext ctx, Instant startedAt,
            long durationMs, String status, Throwable failure, Integer itemCount) {
        ValidationEvent event = new ValidationEvent();
        event.setTeamId(ctx.teamId());
        event.setUserId(ctx.userId());
        event.setMetricCode(operation.metric().name());
        event.setOperation(operation.name());
        event.setRepositoryId(ctx.repositoryId());
        event.setExportFormat(ctx.exportFormat());
        event.setAnalysisRunId(ctx.analysisRunId());
        event.setStartedAt(startedAt);
        event.setEndedAt(startedAt.plusMillis(durationMs));
        event.setDurationMs(durationMs);
        event.setStatus(status);
        event.setErrorMessage(errorText(failure));
        event.setItemCount(itemCount);
        event.setCreatedAt(clock.instant());
        return event;
    }

    private static <T> Integer countItems(T result, ToIntFunction<T> itemCount) {
        if (itemCount == null || result == null) return null;
        try {
            return itemCount.applyAsInt(result);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String errorText(Throwable failure) {
        if (failure == null) return null;
        String text = failure.getClass().getSimpleName()
            + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }

    private String safeUserId() {
        if (accessGuard == null) return null;
        try {
            return accessGuard.currentUserId();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
