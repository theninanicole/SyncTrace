package com.ieee.evaluator.synctrace.validation.service;

import com.ieee.evaluator.synctrace.validation.model.ValidationEvent;
import com.ieee.evaluator.synctrace.validation.repository.ValidationEventRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;

/**
 * Writes validation metrics off the request path. Every call only enqueues work onto one
 * dedicated background thread, so recording never adds a blocking database call to a user's
 * request; tasks run strictly one at a time, so stage-session updates never race each other.
 * Any failure while writing is logged and dropped: a metric must never break the user's action.
 */
@Slf4j
@Component
public class ValidationRecorder {

    private static final int QUEUE_CAPACITY = 1_000;

    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final ValidationEventRepository eventRepository;
    private final StageSessionService stageSessionService;
    private final CoverageService coverageService;
    private final ValidationSummaryService summaryService;
    private final Clock clock;

    @Autowired
    public ValidationRecorder(ValidationEventRepository eventRepository, StageSessionService stageSessionService,
            CoverageService coverageService, ValidationSummaryService summaryService) {
        this(newBackgroundExecutor(), eventRepository, stageSessionService, coverageService, summaryService,
            Clock.systemUTC());
    }

    /** @param executor where writes run; tests pass {@code Runnable::run} to run them inline */
    public ValidationRecorder(Executor executor, ValidationEventRepository eventRepository,
            StageSessionService stageSessionService, CoverageService coverageService,
            ValidationSummaryService summaryService, Clock clock) {
        this.executor = executor;
        this.ownedExecutor = executor instanceof ExecutorService service ? service : null;
        this.eventRepository = eventRepository;
        this.stageSessionService = stageSessionService;
        this.coverageService = coverageService;
        this.summaryService = summaryService;
        this.clock = clock;
    }

    private static ExecutorService newBackgroundExecutor() {
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "validation-metrics");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY), threads,
            (task, pool) -> log.warn("Validation metrics queue is full; a metric write was dropped."));
    }

    public Instant now() {
        return clock.instant();
    }

    public void recordEvent(ValidationEvent event) {
        submit("event " + event.getMetricCode(), () -> {
            eventRepository.save(event);
            summaryService.refreshTeam(event.getTeamId());
        });
    }

    /** M7: snapshot coverage after a recommendations step (success, failed or timeout). */
    public void recordCoverage(String teamId, String analysisRunId, String recommendationStatus) {
        if (teamId == null || teamId.isBlank()) return;
        Instant at = now();
        submit("coverage", () -> {
            coverageService.record(teamId.trim(), analysisRunId, recommendationStatus, at);
            summaryService.refreshTeam(teamId);
        });
    }

    /** M4 heartbeat; {@code at} is when the request arrived, not when the write runs. */
    public void heartbeat(String teamId, String stageCode, boolean visible) {
        if (teamId == null || stageCode == null) return;
        Instant at = now();
        submit("heartbeat", () -> stageSessionService.heartbeat(teamId.trim(), stageCode, visible, at));
    }

    /** M4: a link was added, removed, accepted or edited in a stage (reopens a finalized stage). */
    public void linkChanged(String teamId, String stageKey) {
        String stageCode = StageKeys.toCode(stageKey);
        if (teamId == null || stageCode == null) return;
        Instant at = now();
        submit("link change", () -> stageSessionService.linkChanged(teamId.trim(), stageCode, at));
    }

    /** M4: Save Mapping with a stage selected. */
    public void stageSaved(String teamId, String stageKey, String userId) {
        String stageCode = StageKeys.toCode(stageKey);
        if (teamId == null || stageCode == null) return;
        Instant at = now();
        submit("stage finalize", () -> {
            stageSessionService.finalizeStage(teamId.trim(), stageCode, userId, at);
            summaryService.refreshTeam(teamId);
        });
    }

    /** M5 data changed through the existing suggestion code; refresh the stored summary. */
    public void teamChanged(String teamId) {
        if (teamId == null || teamId.isBlank()) return;
        submit("summary refresh", () -> summaryService.refreshTeam(teamId));
    }

    /**
     * Waits until every write queued before this call has run (tests, and checks before an
     * export right after a session). Returns false if they did not finish within the timeout.
     */
    public boolean awaitIdle(Duration timeout) {
        CountDownLatch done = new CountDownLatch(1);
        try {
            executor.execute(done::countDown);
            return done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void submit(String what, Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("Validation metric write failed ({}): {}", what, e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Validation metric write could not be queued ({}): {}", what, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor == null) return;
        ownedExecutor.shutdown();
        try {
            if (!ownedExecutor.awaitTermination(5, TimeUnit.SECONDS)) ownedExecutor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ownedExecutor.shutdownNow();
        }
    }
}
