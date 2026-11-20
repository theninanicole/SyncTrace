package com.ieee.evaluator.synctrace.validation.service;

import com.ieee.evaluator.synctrace.model.MappingSuggestion;
import com.ieee.evaluator.synctrace.repository.MappingSuggestionRepository;
import com.ieee.evaluator.synctrace.validation.config.ValidationProperties;
import com.ieee.evaluator.synctrace.validation.model.*;
import com.ieee.evaluator.synctrace.validation.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Every expected value below is worked out by hand in the comment next to it. */
class ValidationSummaryServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-25T01:00:00Z");
    private ValidationSummaryService service;
    private long ids;

    @BeforeEach
    void setUp() {
        service = new ValidationSummaryService(mock(ValidationEventRepository.class),
            mock(StageSessionRepository.class), mock(MappingSuggestionRepository.class),
            mock(AnalysisCoverageRepository.class), mock(TeamSummaryRowRepository.class),
            ValidationProperties.defaults(), ZoneOffset.UTC);
    }

    private ValidationEvent event(String team, ValidationOperation op, long startSec, long durationMs,
            String status, String runId) {
        ValidationEvent e = new ValidationEvent();
        e.setId(++ids);
        e.setTeamId(team);
        e.setMetricCode(op.metric().name());
        e.setOperation(op.name());
        e.setStartedAt(T0.plusSeconds(startSec));
        e.setEndedAt(T0.plusSeconds(startSec).plusMillis(durationMs));
        e.setDurationMs(durationMs);
        e.setStatus(status);
        e.setAnalysisRunId(runId);
        return e;
    }

    private static StageSession session(String stage, Long activeMs, Long wallMs) {
        StageSession s = new StageSession();
        s.setTeamId("TEAM-A");
        s.setStage(stage);
        s.setStartedAt(T0);
        if (activeMs != null) {
            s.setActiveMs(activeMs);
            s.setWallClockMs(wallMs);
            s.setFinalizedAt(T0.plusMillis(wallMs));
        }
        return s;
    }

    private static MappingSuggestion suggestion(MappingSuggestion.Status status, boolean system, boolean withdrawn) {
        MappingSuggestion s = new MappingSuggestion();
        s.setTeamCode("TEAM-A");
        s.setStage("PROPOSAL_SRS");
        s.setStatus(status);
        s.setSystemRejected(system);
        if (withdrawn) s.setWithdrawnAt(LocalDateTime.of(2026, 11, 25, 1, 30));
        return s;
    }

    private static AnalysisCoverage coverage(int detected, int provisional, int both) {
        AnalysisCoverage c = new AnalysisCoverage();
        c.setTeamId("TEAM-A");
        c.setGapsDetected(detected);
        c.setGapsProvisional(provisional);
        c.setGapsWithBoth(both);
        c.setGenerationFailures(detected - provisional - both);
        return c;
    }

    private TeamValidationSummary teamA() {
        String ok = ValidationEvent.SUCCESS;
        List<ValidationEvent> events = new ArrayList<>(List.of(
            // M1: 10 s, 20 s, 40 s succeeded; 5 s failed
            event("TEAM-A", ValidationOperation.GOAL_EXTRACTION, 0, 10_000, ok, null),
            event("TEAM-A", ValidationOperation.GOAL_EXTRACTION, 100, 20_000, ok, null),
            event("TEAM-A", ValidationOperation.GOAL_EXTRACTION, 200, 40_000, ok, null),
            event("team-a", ValidationOperation.GOAL_EXTRACTION, 300, 5_000, ValidationEvent.FAILED, null),
            // M2: 1 s, 2 s, 3 s
            event("TEAM-A", ValidationOperation.COMPONENT_RETRIEVAL, 0, 1_000, ok, null),
            event("TEAM-A", ValidationOperation.COMPONENT_RETRIEVAL, 10, 3_000, ok, null),
            event("TEAM-A", ValidationOperation.COMPONENT_RETRIEVAL, 20, 2_000, ok, null),
            // M8: one 12 s export that timed out
            event("TEAM-A", ValidationOperation.AUDIT_EXPORT, 0, 12_000, ValidationEvent.TIMEOUT, null),
            // M6 run A: detect 0–2 s, recommendations 2.5–40 s → 40 s, success
            event("TEAM-A", ValidationOperation.GAP_DETECTION, 0, 2_000, ok, "A"),
            event("TEAM-A", ValidationOperation.RECOMMENDATIONS, 0, 0, ok, "A"),
            // M6 run B: 0–70 s → over the 60 s target
            event("TEAM-A", ValidationOperation.GAP_DETECTION, 1000, 1_000, ok, "B"),
            event("TEAM-A", ValidationOperation.RECOMMENDATIONS, 1001, 69_000, ok, "B"),
            // M6 run C: detection only (never finished) → not a run
            event("TEAM-A", ValidationOperation.GAP_DETECTION, 2000, 1_000, ok, "C"),
            // M6 run D: detection failed after 1 s → a failed run
            event("TEAM-A", ValidationOperation.GAP_DETECTION, 3000, 1_000, ValidationEvent.FAILED, "D"),
            // Alignment is recorded under M6 but never counted
            event("TEAM-A", ValidationOperation.ALIGNMENT, 4000, 90_000, ok, "E")));
        // fix run A's recommendation step to 2.5 s → 40 s
        ValidationEvent recA = events.get(9);
        recA.setStartedAt(T0.plusMillis(2_500));
        recA.setDurationMs(37_500);
        recA.setEndedAt(T0.plusMillis(40_000));

        List<StageSession> sessions = List.of(
            session("proposal_srs", 600_000L, 900_000L),   // 10 min active, 15 min wall
            session("srs_sdd", 1_200_000L, 1_500_000L),    // 20 min active
            session("srs_std", null, null));               // still open

        List<MappingSuggestion> suggestions = new ArrayList<>();
        for (int i = 0; i < 5; i++) suggestions.add(suggestion(MappingSuggestion.Status.ACCEPTED, false, false));
        for (int i = 0; i < 2; i++) suggestions.add(suggestion(MappingSuggestion.Status.EDITED, false, false));
        for (int i = 0; i < 3; i++) suggestions.add(suggestion(MappingSuggestion.Status.REJECTED, false, false));
        suggestions.add(suggestion(MappingSuggestion.Status.REJECTED, true, false));   // stale target
        suggestions.add(suggestion(MappingSuggestion.Status.PENDING, false, false));
        suggestions.add(suggestion(MappingSuggestion.Status.PENDING, false, true));    // discarded

        List<AnalysisCoverage> coverage = List.of(
            coverage(4, 0, 4),   // 4 of 4
            coverage(5, 1, 3),   // 3 of 4 counted
            coverage(0, 0, 0));  // no gaps: excluded

        List<TeamValidationSummary> result = service.summarize(ValidationFilter.allTime(List.of()),
            new ValidationDataset(events, sessions, suggestions, coverage));
        assertEquals(1, result.size(), "team codes are grouped ignoring case");
        return result.get(0);
    }

    @Test
    void timedEventMetrics() {
        TeamValidationSummary team = teamA();

        MetricSummary m1 = team.metric("M1");
        assertEquals(4, m1.getCount());
        assertEquals(15_000L, m1.getMedianMs());        // sorted 5,10,20,40 → (10+20)/2
        assertEquals(40_000L, m1.getMaxMs());
        assertEquals(5_000L, m1.getMinMs());
        assertEquals(50.0, m1.getPctWithinTarget());    // 10 s and 20 s of 4 runs
        assertFalse(m1.getTargetMet());                 // needs 100 %
        assertEquals(1, m1.getFailed());
        assertEquals(30_000.0, m1.getTarget());

        MetricSummary m2 = team.metric("M2");
        assertEquals(2_000L, m2.getMedianMs());
        assertEquals(100.0, m2.getPctWithinTarget());
        assertTrue(m2.getTargetMet());

        MetricSummary m3 = team.metric("M3");
        assertEquals(0, m3.getCount());
        assertNull(m3.getMedianMs());
        assertNull(m3.getTargetMet(), "no runs: neither met nor not met");

        MetricSummary m8 = team.metric("M8");
        assertEquals(0.0, m8.getPctWithinTarget());     // 12 s > 10 s and timed out
        assertEquals(1, m8.getTimedOut());
        assertFalse(m8.getTargetMet());
    }

    @Test
    void continuityRunsSpanBothStepsAndIgnoreAlignment() {
        MetricSummary m6 = teamA().metric("M6");
        assertEquals(3, m6.getCount());                 // A, B, D (C unfinished, E alignment)
        assertEquals(40_000L, m6.getMedianMs());        // sorted 1, 40, 70 s
        assertEquals(70_000L, m6.getMaxMs());
        assertEquals(1_000L, m6.getMinMs());
        assertEquals(33.3, m6.getPctWithinTarget());    // only A
        assertEquals(1, m6.getFailed());
    }

    @Test
    void stageSessionsUseActiveTime() {
        TeamValidationSummary team = teamA();
        MetricSummary m4 = team.metric("M4");
        assertEquals(2, m4.getCount());
        assertEquals(900_000L, m4.getMedianMs());       // (10 + 20 min) / 2
        assertEquals(1_200_000L, m4.getMedianWallClockMs()); // (15 + 25 min) / 2
        assertEquals(50.0, m4.getPctWithinTarget());    // 10 min ≤ 15, 20 min > 15
        assertEquals(1, m4.getOpenSessions());

        MetricSummary proposal = team.m4Stages().stream()
            .filter(s -> "proposal_srs".equals(s.getStage())).findFirst().orElseThrow();
        assertEquals(1, proposal.getCount());
        assertTrue(proposal.getTargetMet());
        assertEquals(5, team.m4Stages().size());
    }

    @Test
    void suggestionAcceptanceRate() {
        MetricSummary m5 = teamA().metric("M5");
        assertEquals(13, m5.getCount());
        assertEquals(5, m5.getAccepted());
        assertEquals(2, m5.getEdited());
        assertEquals(3, m5.getRejected());
        assertEquals(1, m5.getSystemRejected());
        assertEquals(2, m5.getPending());
        assertEquals(1, m5.getWithdrawn());
        assertEquals(70.0, m5.getAcceptanceRate());     // (5 + 2) / (5 + 2 + 3)
        assertTrue(m5.getTargetMet());                  // ≥ 70 %
    }

    @Test
    void gapCoverage() {
        MetricSummary m7 = teamA().metric("M7");
        assertEquals(3, m7.getCount());
        assertEquals(8, m7.getGapsCounted());           // 4 + 4 (run with 0 gaps excluded)
        assertEquals(7, m7.getGapsWithBoth());          // 4 + 3
        assertEquals(87.5, m7.getCoveragePct());
        assertFalse(m7.getTargetMet());                 // needs 100 %
    }

    @Test
    void selectedTeamsAppearEvenWithoutData() {
        List<TeamValidationSummary> result = service.summarize(
            new ValidationFilter(Set.of("TEAM-Z"), null, null, null),
            new ValidationDataset(List.of(), List.of(), List.of(), List.of()));
        assertEquals(1, result.size());
        assertEquals("TEAM-Z", result.get(0).teamId());
        assertEquals(0, result.get(0).metric("M1").getCount());
        assertNull(result.get(0).metric("M5").getAcceptanceRate());
    }

    @Test
    void medianOfOddAndEvenCounts() {
        assertEquals(3L, ValidationSummaryService.median(List.of(5L, 1L, 3L)));
        assertEquals(3L, ValidationSummaryService.median(List.of(4L, 1L, 2L, 9L)));  // (2 + 4) / 2
        assertEquals(2L, ValidationSummaryService.median(List.of(1L, 2L)));        // 1.5 rounds to 2
        assertNull(ValidationSummaryService.median(List.of()));
    }
}
