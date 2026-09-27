package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.DiagnosticRecommendation;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.DiagnosticRecommendationRepository;
import com.ieee.evaluator.synctrace.service.TraceabilityClusterService.GoalCluster;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ContinuityReadinessService {

    private static final List<DocType> REQUIRED_DOC_TYPES = List.of(
        DocType.SRS,
        DocType.SDD,
        DocType.SPMP,
        DocType.STD,
        DocType.IMPLEMENTATION
    );

    // Per-finding score deduction by severity, and the overall cap on how much
    // open findings can drag the score down (coverage still dominates the score).
    private static final int PENALTY_CRITICAL = 8;
    private static final int PENALTY_HIGH = 5;
    private static final int PENALTY_MEDIUM = 3;
    private static final int PENALTY_LOW = 1;
    private static final int MAX_FINDINGS_PENALTY = 40;

    private final ContinuityFindingRepository findingRepository;
    private final DiagnosticRecommendationRepository recommendationRepository;
    private final TraceabilityClusterService clusterService;

    public ContinuityReadinessService(
            ContinuityFindingRepository findingRepository,
            DiagnosticRecommendationRepository recommendationRepository,
            TraceabilityClusterService clusterService) {
        this.findingRepository = findingRepository;
        this.recommendationRepository = recommendationRepository;
        this.clusterService = clusterService;
    }

    public Map<String, Object> getTeamReadinessSummary(String teamCode) {
        if (teamCode == null || teamCode.isBlank()) {
            throw new IllegalArgumentException("teamCode is required");
        }

        // Scored per goal cluster (one matrix row), not per individual goal: SPECIFIC
        // objectives carry no mappings of their own, so scoring them separately dragged
        // every team's readiness down regardless of what was actually mapped.
        List<GoalCluster> clusters = clusterService.clustersForTeam(teamCode);
        Set<DocType> established = clusterService.establishedDocTypes(clusters);
        List<ContinuityFinding> findings = findingRepository.findByTeamCodeIgnoreCaseOrderByDetectedAtDescIdAsc(teamCode.trim());
        Map<Long, List<DiagnosticRecommendation>> recommendationsByFinding = getRecommendationsByFinding(findings);

        int totalGoals = clusters.size();
        int readyGoals = 0;
        int totalMappedComponents = 0;
        int strictReadyGoals = 0;
        double totalCoverageFraction = 0;
        List<Map<String, Object>> goalSummaries = new ArrayList<>();

        for (GoalCluster cluster : clusters) {
            Set<DocType> coveredTypes = cluster.coveredDocTypes();
            coveredTypes.retainAll(REQUIRED_DOC_TYPES);
            Set<Long> memberIds = new HashSet<>(cluster.memberGoalIds());
            long goalFindingCount = findings.stream()
                .filter(finding -> finding.getGoalId() != null && memberIds.contains(finding.getGoalId()))
                .count();

            boolean fullCoverage = coveredTypes.containsAll(REQUIRED_DOC_TYPES);
            boolean strictCoverage = fullCoverage && clusterService.evaluate(cluster, established).isEmpty();
            if (goalFindingCount == 0 && fullCoverage) readyGoals++;
            if (goalFindingCount == 0 && strictCoverage) strictReadyGoals++;

            totalMappedComponents += coveredTypes.size();
            totalCoverageFraction += (double) coveredTypes.size() / REQUIRED_DOC_TYPES.size();

            Map<String, Object> goalSummary = new LinkedHashMap<>();
            goalSummary.put("goalId", cluster.id());
            goalSummary.put("memberGoalIds", cluster.memberGoalIds());
            goalSummary.put("description", cluster.primary().getDescription());
            goalSummary.put("coveredDocTypes", REQUIRED_DOC_TYPES.stream()
                .filter(coveredTypes::contains)
                .map(Enum::name)
                .toList());
            goalSummary.put("missingDocTypes", REQUIRED_DOC_TYPES.stream()
                .filter(docType -> !coveredTypes.contains(docType))
                .map(Enum::name)
                .toList());
            goalSummary.put("findingCount", goalFindingCount);
            goalSummaries.add(goalSummary);
        }

        int totalFindings = findings.size();
        int resolvedFindingCount = 0;
        Map<String, Integer> severityCounts = new LinkedHashMap<>();
        for (ContinuityFinding.Severity severity : ContinuityFinding.Severity.values()) {
            severityCounts.put(severity.name(), 0);
        }

        for (ContinuityFinding finding : findings) {
            if (finding.getSeverity() != null) {
                severityCounts.computeIfPresent(finding.getSeverity().name(), (ignored, count) -> count + 1);
            }
            if (!recommendationsByFinding.getOrDefault(finding.getId(), List.of()).isEmpty()) {
                resolvedFindingCount++;
            }
        }

        // Coverage is averaged per-goal so partial progress (e.g. 3 of 5 doc types mapped)
        // is reflected in the score, instead of only counting goals that are fully done.
        int averageCoveragePercent = totalGoals == 0
            ? 0
            : (int) Math.round((totalCoverageFraction / totalGoals) * 100);
        int findingsPenalty = Math.min(MAX_FINDINGS_PENALTY, computeFindingsPenalty(severityCounts));
        int readinessScore = totalGoals == 0
            ? 0
            : Math.max(0, Math.min(100, averageCoveragePercent - findingsPenalty));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", statusFor(totalGoals, totalMappedComponents, readinessScore, totalFindings, strictReadyGoals));
        summary.put("readinessScore", readinessScore);
        summary.put("averageCoveragePercent", averageCoveragePercent);
        summary.put("totalGoals", totalGoals);
        summary.put("readyGoals", readyGoals);
        summary.put("strictReadyGoals", strictReadyGoals);
        summary.put("totalFindings", totalFindings);
        summary.put("findingsWithRecommendations", resolvedFindingCount);
        summary.put("totalMappedComponents", totalMappedComponents);
        summary.put("severityCounts", severityCounts);
        summary.put("goalSummaries", goalSummaries);
        return summary;
    }

    private Map<Long, List<DiagnosticRecommendation>> getRecommendationsByFinding(List<ContinuityFinding> findings) {
        List<Long> findingIds = findings.stream()
            .map(ContinuityFinding::getId)
            .filter(Objects::nonNull)
            .toList();

        Map<Long, List<DiagnosticRecommendation>> recommendationsByFinding = new HashMap<>();
        if (findingIds.isEmpty()) {
            return recommendationsByFinding;
        }

        for (DiagnosticRecommendation recommendation : recommendationRepository.findByFindingIdIn(findingIds)) {
            recommendationsByFinding
                .computeIfAbsent(recommendation.getFindingId(), ignored -> new ArrayList<>())
                .add(recommendation);
        }

        return recommendationsByFinding;
    }

    private int computeFindingsPenalty(Map<String, Integer> severityCounts) {
        return severityCounts.getOrDefault("CRITICAL", 0) * PENALTY_CRITICAL
            + severityCounts.getOrDefault("HIGH", 0) * PENALTY_HIGH
            + severityCounts.getOrDefault("MEDIUM", 0) * PENALTY_MEDIUM
            + severityCounts.getOrDefault("LOW", 0) * PENALTY_LOW;
    }

        private String statusFor(int totalGoals, int totalMappedComponents, int readinessScore,
            int totalFindings, int strictReadyGoals) {
        if (totalGoals == 0 || totalMappedComponents == 0) {
            return "NOT_STARTED";
        }
        if (totalFindings == 0 && readinessScore == 100 && strictReadyGoals == totalGoals) {
            return "READY";
        }
        if (readinessScore >= 75) {
            return "ON_TRACK";
        }
        if (readinessScore >= 40) {
            return "AT_RISK";
        }
        return "BLOCKED";
    }
}