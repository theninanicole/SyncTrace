package com.ieee.evaluator.synctrace.validation;

import com.ieee.evaluator.synctrace.validation.service.ActiveTime;
import com.ieee.evaluator.synctrace.validation.service.ActiveTime.Beat;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActiveTimeTest {

    private static final long IDLE = 120_000;
    private static final Instant T0 = Instant.parse("2026-11-25T01:00:00Z");

    private static Beat visible(long seconds) {
        return new Beat(T0.plusSeconds(seconds), true);
    }

    private static Beat hidden(long seconds) {
        return new Beat(T0.plusSeconds(seconds), false);
    }

    @Test
    void steadyHeartbeatsCountTheWholeSpan() {
        assertEquals(60_000, ActiveTime.total(List.of(visible(0), visible(30), visible(60)), IDLE));
    }

    @Test
    void aGapLongerThanTwoMinutesIsIdle() {
        // 0→30 active, 30→330 (5 min) idle, 330→360 active
        assertEquals(60_000, ActiveTime.total(
            List.of(visible(0), visible(30), visible(330), visible(360)), IDLE));
    }

    @Test
    void exactlyTwoMinutesStillCountsButOneSecondMoreDoesNot() {
        assertEquals(120_000, ActiveTime.total(List.of(visible(0), visible(120)), IDLE));
        assertEquals(0, ActiveTime.total(List.of(visible(0), visible(121)), IDLE));
    }

    @Test
    void hidingTheTabClosesTheInterval() {
        // 0→30 active, 30→40 active (hide arrives at 40), 40→70 hidden: not counted, 70→100 active
        assertEquals(70_000, ActiveTime.total(
            List.of(visible(0), visible(30), hidden(40), visible(70), visible(100)), IDLE));
    }

    @Test
    void interleavedTeammatesAreNotCountedTwice() {
        // Two people on the same stage, heartbeats 15 s apart: team time is the 45 s span.
        assertEquals(45_000, ActiveTime.total(
            List.of(visible(0), visible(15), visible(30), visible(45)), IDLE));
    }

    @Test
    void anOutOfOrderBeatAddsNothingAndKeepsTheMarker() {
        ActiveTime.Step step = ActiveTime.accumulate(T0.plusSeconds(60), T0.plusSeconds(50), IDLE, true);
        assertEquals(0, step.addMs());
        assertEquals(T0.plusSeconds(60), step.newLast());
    }

    @Test
    void theFirstBeatOnlyOpensTheInterval() {
        ActiveTime.Step step = ActiveTime.accumulate(null, T0, IDLE, true);
        assertEquals(0, step.addMs());
        assertEquals(T0, step.newLast());
    }
}
