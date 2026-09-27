package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ContinuityAnalysisRun;
import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.ContinuityAnalysisRunRepository;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.DiagnosticRecommendationRepository;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.service.TraceabilityClusterService.GoalCluster;
import com.ieee.evaluator.synctrace.service.TraceabilityClusterService.TraceIssue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class ContinuityGapDetectionService {

    private final ContinuityFindingRepository findingRepository;
    private final DiagnosticRecommendationRepository recommendationRepository;
    private final ContinuityAnalysisRunRepository analysisRunRepository;
    private final SmartGoalRepository goalRepository;
    private final TraceabilityClusterService clusterService;

    public ContinuityGapDetectionService(
            ContinuityFindingRepository findingRepository,
            DiagnosticRecommendationRepository recommendationRepository,
            ContinuityAnalysisRunRepository analysisRunRepository,
            SmartGoalRepository goalRepository,
            TraceabilityClusterService clusterService) {
        this.findingRepository = findingRepository;
        this.recommendationRepository = recommendationRepository;
        this.analysisRunRepository = analysisRunRepository;
        this.goalRepository = goalRepository;
        this.clusterService = clusterService;
    }

    /**
     * Regenerates the goal-level continuity findings for a team (or a single goal cluster).
     * The result depends only on the current goals and mappings, so running it repeatedly
     * without changing the mapping yields the same findings every time.
     */
    @Transactional
    public List<ContinuityFinding> detectGaps(String rawTeamCode, Long goalId) {
        String teamCode = TeamCodeResolver.normalize(rawTeamCode);
        if (teamCode == null && goalId != null) {
            teamCode = goalRepository.findById(goalId)
                .map(SmartGoal::getTeamCode)
                .map(TeamCodeResolver::normalize)
                .orElse(null);
        }

        List<GoalCluster> teamClusters = teamCode != null
            ? clusterService.clustersForTeam(teamCode)
            : clusterService.buildClusters(goalRepository.findAllByOrderByCreatedAtAscIdAsc(), null);

        List<GoalCluster> targetClusters = teamClusters;
        if (goalId != null) {
            targetClusters = teamClusters.stream()
                .filter(cluster -> cluster.memberGoalIds().contains(goalId))
                .toList();
        }

        clearGoalFindings(teamCode, goalId, targetClusters);

        Set<DocType> established = clusterService.establishedDocTypes(teamClusters);
        LocalDateTime detectedAt = LocalDateTime.now();
        List<ContinuityFinding> findings = new ArrayList<>();
        for (GoalCluster cluster : targetClusters) {
            for (TraceIssue issue : clusterService.evaluate(cluster, established)) {
                findings.add(toFinding(teamCode, cluster.id(), issue, detectedAt));
            }
        }

        List<ContinuityFinding> savedFindings = findingRepository.saveAll(findings);
        if (teamCode != null) {
            final String runTeamCode = teamCode;
            ContinuityAnalysisRun run = analysisRunRepository
                .findByTeamCodeIgnoreCase(runTeamCode)
                .orElseGet(ContinuityAnalysisRun::new);
            if (run.getTeamCode() == null) run.setTeamCode(runTeamCode);
            run.setLastAnalyzedAt(detectedAt);
            analysisRunRepository.save(run);
        }
        return savedFindings;
    }

    /**
     * Removes the previous goal-level findings (and their recommendations) before regenerating.
     * Source-code alignment findings (goalId == null) are owned by SourceCodeAlignmentService
     * and are left alone, so running one analysis never silently wipes out the other.
     */
    private void clearGoalFindings(String teamCode, Long goalId, List<GoalCluster> targetClusters) {
        List<ContinuityFinding> stale;
        if (goalId != null) {
            Set<Long> memberIds = new HashSet<>();
            memberIds.add(goalId);
            targetClusters.forEach(cluster -> memberIds.addAll(cluster.memberGoalIds()));
            stale = new ArrayList<>();
            for (Long memberId : memberIds) {
                findingRepository.findByGoalId(memberId).stream()
                    .filter(f -> teamCode == null || teamCode.equalsIgnoreCase(Objects.toString(f.getTeamCode(), "")))
                    .forEach(stale::add);
            }
        } else if (teamCode != null) {
            stale = findingRepository.findByTeamCodeIgnoreCaseOrderByDetectedAtDescIdAsc(teamCode).stream()
                .filter(f -> f.getGoalId() != null)
                .toList();
        } else {
            return;
        }
        if (stale.isEmpty()) return;

        stale.stream()
            .map(ContinuityFinding::getId)
            .filter(Objects::nonNull)
            .forEach(recommendationRepository::deleteByFindingId);
        findingRepository.deleteAll(stale);
    }

    private ContinuityFinding toFinding(String teamCode, Long goalId, TraceIssue issue, LocalDateTime detectedAt) {
        ContinuityFinding finding = new ContinuityFinding();
        finding.setTeamCode(teamCode);
        finding.setGoalId(goalId);
        finding.setDocTypeFrom(issue.from());
        finding.setDocTypeTo(issue.to());
        finding.setSeverity(issue.severity());
        finding.setDescription(issue.description());
        finding.setDetectedAt(detectedAt);
        return finding;
    }
}
