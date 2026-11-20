package com.ieee.evaluator.synctrace.validation.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Active-time accounting for M4 from client heartbeats. While a stage is open and the tab is
 * visible the client sends a heartbeat about every 30 s. The time between two consecutive
 * heartbeats counts as active when the gap is at most the idle threshold (2 min); a longer
 * gap is idle and adds nothing. A "hidden" heartbeat closes the interval, so time spent with
 * the tab hidden is never counted even when the next heartbeat arrives within 2 minutes.
 */
public final class ActiveTime {

    /** @param addMs active time to add; @param newLast the interval marker to keep (null = closed) */
    public record Step(long addMs, Instant newLast) {}

    /** One heartbeat: when it arrived and whether the tab was still visible. */
    public record Beat(Instant at, boolean visible) {}

    private ActiveTime() {
    }

    public static Step accumulate(Instant last, Instant now, long idleGapMs, boolean stillVisible) {
        long add = 0;
        if (last != null && now.isAfter(last)) {
            long gap = Duration.between(last, now).toMillis();
            if (gap <= idleGapMs) add = gap;
        }
        Instant newLast;
        if (!stillVisible) {
            newLast = null;
        } else if (last != null && last.isAfter(now)) {
            newLast = last; // out-of-order arrival never moves the marker backwards
        } else {
            newLast = now;
        }
        return new Step(add, newLast);
    }

    /** Total active time of a heartbeat sequence (the same rule the server applies one beat at a time). */
    public static long total(List<Beat> beats, long idleGapMs) {
        Instant last = null;
        long total = 0;
        for (Beat beat : beats) {
            Step step = accumulate(last, beat.at(), idleGapMs, beat.visible());
            total += step.addMs();
            last = step.newLast();
        }
        return total;
    }
}
