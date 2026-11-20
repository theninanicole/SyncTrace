package com.ieee.evaluator.synctrace.validation.service;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.DiagnosticRecommendation;
import com.ieee.evaluator.synctrace.validation.model.AnalysisCoverage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Counts how many of a run's gaps received both a diagnostic explanation (root cause) and a
 * corrective recommendation (M7). Blank text does not count. Provisional gaps are counted
 * separately and left out of every other column, so coverage = withBoth / (detected - provisional).
 */
public final class CoverageCalculator {

    /**
     * "Provisional" findings are not defined in SyncTrace yet (plan decision D7), so none are
     * provisional. Swap this predicate once the rule is agreed; readiness is unaffected either way.
     */
    public static final Predicate<ContinuityFinding> NO_PROVISIONAL = finding -> false;

    private CoverageCalculator() {
    }

    public static AnalysisCoverage compute(String analysisRunId, String teamId, List<ContinuityFinding> findings,
            Map<Long, List<DiagnosticRecommendation>> recommendationsByFinding,
            Predicate<ContinuityFinding> provisional, String recommendationStatus, Instant now) {
        int detected = 0;
        int provisionalCount = 0;
        int withExplanation = 0;
        int withRecommendation = 0;
        int withBoth = 0;

        for (ContinuityFinding finding : findings) {
            detected++;
            if (provisional.test(finding)) {
                provisionalCount++;
                continue;
            }
            List<DiagnosticRecommendation> recs = recommendationsByFinding.getOrDefault(finding.getId(), List.of());
            boolean explained = recs.stream().anyMatch(rec -> hasText(rec.getRootCause()));
            boolean recommended = recs.stream().anyMatch(rec -> hasText(rec.getRecommendation()));
            if (explained) withExplanation++;
            if (recommended) withRecommendation++;
            if (explained && recommended) withBoth++;
        }

        AnalysisCoverage coverage = new AnalysisCoverage();
        coverage.setAnalysisRunId(analysisRunId);
        coverage.setTeamId(teamId);
        coverage.setGapsDetected(detected);
        coverage.setGapsProvisional(provisionalCount);
        coverage.setGapsWithExplanation(withExplanation);
        coverage.setGapsWithRecommendation(withRecommendation);
        coverage.setGapsWithBoth(withBoth);
        coverage.setGenerationFailures(detected - provisionalCount - withBoth);
        coverage.setRecommendationStatus(recommendationStatus);
        coverage.setCreatedAt(now);
        return coverage;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
