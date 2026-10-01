package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.repository.ContinuityAnalysisRunRepository;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.DiagnosticRecommendationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Discards a team's AI continuity analysis (findings, recommendations and last-run time) once
 * its goal links change, since the analysis described mappings that no longer exist. The
 * results then read "Not analyzed" until the analysis is run again.
 */
@Service
public class ContinuityAnalysisResetService {

    private final ContinuityFindingRepository findingRepository;
    private final DiagnosticRecommendationRepository recommendationRepository;
    private final ContinuityAnalysisRunRepository analysisRunRepository;

    public ContinuityAnalysisResetService(
            ContinuityFindingRepository findingRepository,
            DiagnosticRecommendationRepository recommendationRepository,
            ContinuityAnalysisRunRepository analysisRunRepository) {
        this.findingRepository = findingRepository;
        this.recommendationRepository = recommendationRepository;
        this.analysisRunRepository = analysisRunRepository;
    }

    @Transactional
    public void resetTeam(String teamCode) {
        if (teamCode == null || teamCode.isBlank()) return;
        List<ContinuityFinding> findings = findingRepository.findByTeamCodeIgnoreCase(teamCode);
        for (ContinuityFinding finding : findings) {
            recommendationRepository.deleteByFindingId(finding.getId());
        }
        findingRepository.deleteAll(findings);
        analysisRunRepository.findByTeamCodeIgnoreCase(teamCode).ifPresent(analysisRunRepository::delete);
    }
}
