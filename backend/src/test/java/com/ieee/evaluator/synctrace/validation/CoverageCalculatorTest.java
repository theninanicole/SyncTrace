package com.ieee.evaluator.synctrace.validation;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.DiagnosticRecommendation;
import com.ieee.evaluator.synctrace.validation.model.AnalysisCoverage;
import com.ieee.evaluator.synctrace.validation.service.CoverageCalculator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoverageCalculatorTest {

    private static ContinuityFinding finding(long id, Long goalId) {
        ContinuityFinding finding = new ContinuityFinding();
        finding.setId(id);
        finding.setGoalId(goalId);
        return finding;
    }

    private static DiagnosticRecommendation rec(long findingId, String rootCause, String recommendation) {
        DiagnosticRecommendation rec = new DiagnosticRecommendation();
        rec.setFindingId(findingId);
        rec.setRootCause(rootCause);
        rec.setRecommendation(recommendation);
        return rec;
    }

    private final List<ContinuityFinding> findings = List.of(
        finding(1, 100L), finding(2, 100L), finding(3, 101L), finding(4, null));

    private final Map<Long, List<DiagnosticRecommendation>> recs = Map.of(
        1L, List.of(rec(1, "This happened because the SRS has no use case.", "Add a use case.")),
        2L, List.of(rec(2, "This happened because no wireframe exists.", "")),   // explanation only
        3L, List.of(rec(3, "   ", "   ")));                                        // blank text: neither
    // finding 4 has no recommendation row at all

    @Test
    void countsOnlyNonBlankExplanationsAndRecommendations() {
        AnalysisCoverage coverage = CoverageCalculator.compute("run-1", "TEAM", findings, recs,
            CoverageCalculator.NO_PROVISIONAL, "success", Instant.EPOCH);

        assertEquals(4, coverage.getGapsDetected());
        assertEquals(0, coverage.getGapsProvisional());
        assertEquals(2, coverage.getGapsWithExplanation());
        assertEquals(1, coverage.getGapsWithRecommendation());
        assertEquals(1, coverage.getGapsWithBoth());
        assertEquals(3, coverage.getGenerationFailures());
        assertEquals(4, coverage.countedGaps());
    }

    @Test
    void provisionalGapsAreLeftOutOfTheDenominator() {
        AnalysisCoverage coverage = CoverageCalculator.compute("run-1", "TEAM", findings, recs,
            finding -> finding.getGoalId() == null, "success", Instant.EPOCH);

        assertEquals(4, coverage.getGapsDetected());
        assertEquals(1, coverage.getGapsProvisional());
        assertEquals(3, coverage.countedGaps());
        assertEquals(1, coverage.getGapsWithBoth());
        assertEquals(2, coverage.getGenerationFailures());
    }

    @Test
    void aFailedRecommendationStepLeavesEveryGapUncovered() {
        AnalysisCoverage coverage = CoverageCalculator.compute("run-2", "TEAM", findings, Map.of(),
            CoverageCalculator.NO_PROVISIONAL, "failed", Instant.EPOCH);

        assertEquals(0, coverage.getGapsWithBoth());
        assertEquals(4, coverage.getGenerationFailures());
        assertEquals("failed", coverage.getRecommendationStatus());
    }

    @Test
    void explanationAndRecommendationMayComeFromSeparateRows() {
        AnalysisCoverage coverage = CoverageCalculator.compute("run-3", "TEAM", List.of(finding(9, 1L)),
            Map.of(9L, List.of(rec(9, "Cause", null), rec(9, null, "Fix it"))),
            CoverageCalculator.NO_PROVISIONAL, "success", Instant.EPOCH);
        assertEquals(1, coverage.getGapsWithBoth());
    }

    @Test
    void noGapsMeansNothingToCover() {
        AnalysisCoverage coverage = CoverageCalculator.compute("run-4", "TEAM", List.of(), Map.of(),
            CoverageCalculator.NO_PROVISIONAL, "success", Instant.EPOCH);
        assertEquals(0, coverage.countedGaps());
        assertEquals(0, coverage.getGenerationFailures());
    }
}
