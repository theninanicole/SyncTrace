package com.ieee.evaluator.synctrace.validation.service;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.DiagnosticRecommendation;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.DiagnosticRecommendationRepository;
import com.ieee.evaluator.synctrace.validation.model.AnalysisCoverage;
import com.ieee.evaluator.synctrace.validation.repository.AnalysisCoverageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Snapshots M7 coverage right after a recommendations step finishes. Read-only on findings. */
@Service
public class CoverageService {

    private final ContinuityFindingRepository findingRepository;
    private final DiagnosticRecommendationRepository recommendationRepository;
    private final AnalysisCoverageRepository coverageRepository;

    public CoverageService(ContinuityFindingRepository findingRepository,
            DiagnosticRecommendationRepository recommendationRepository,
            AnalysisCoverageRepository coverageRepository) {
        this.findingRepository = findingRepository;
        this.recommendationRepository = recommendationRepository;
        this.coverageRepository = coverageRepository;
    }

    @Transactional
    public AnalysisCoverage record(String teamId, String analysisRunId, String recommendationStatus, Instant at) {
        List<ContinuityFinding> findings = findingRepository.findByTeamCodeIgnoreCaseOrderByDetectedAtDescIdAsc(teamId);
        List<Long> ids = findings.stream().map(ContinuityFinding::getId).filter(Objects::nonNull).toList();
        Map<Long, List<DiagnosticRecommendation>> byFinding = new HashMap<>();
        if (!ids.isEmpty()) {
            for (DiagnosticRecommendation rec : recommendationRepository.findByFindingIdIn(ids)) {
                byFinding.computeIfAbsent(rec.getFindingId(), id -> new java.util.ArrayList<>()).add(rec);
            }
        }
        AnalysisCoverage coverage = CoverageCalculator.compute(analysisRunId, teamId, findings, byFinding,
            CoverageCalculator.NO_PROVISIONAL, recommendationStatus, at);
        return coverageRepository.save(coverage);
    }
}
