package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.DiagnosticRecommendation;
import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.DiagnosticRecommendationRepository;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
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

    private final SmartGoalRepository goalRepository;
    private final GoalComponentMappingRepository mappingRepository;
    private final TraceComponentRepository componentRepository;
    private final ContinuityFindingRepository findingRepository;
    private final DiagnosticRecommendationRepository recommendationRepository;
    private final TeamComponentResolverService teamComponentResolver;

    public ContinuityReadinessService(
            SmartGoalRepository goalRepository,
            GoalComponentMappingRepository mappingRepository,
            TraceComponentRepository componentRepository,
            ContinuityFindingRepository findingRepository,
            DiagnosticRecommendationRepository recommendationRepository,
            TeamComponentResolverService teamComponentResolver) {
        this.goalRepository = goalRepository;
        this.mappingRepository = mappingRepository;
        this.componentRepository = componentRepository;
        this.findingRepository = findingRepository;
        this.recommendationRepository = recommendationRepository;
        this.teamComponentResolver = teamComponentResolver;
    }

    public Map<String, Object> getTeamReadinessSummary(String teamCode) {
        if (teamCode == null || teamCode.isBlank()) {
            throw new IllegalArgumentException("teamCode is required");
        }

        List<SmartGoal> teamGoals = getTeamGoals(teamCode);
        List<ContinuityFinding> findings = findingRepository.findByTeamCodeOrderByDetectedAtDesc(teamCode);
        Map<Long, List<DiagnosticRecommendation>> recommendationsByFinding = getRecommendationsByFinding(findings);

        int totalGoals = teamGoals.size();
        int readyGoals = 0;
        int totalMappedComponents = 0;
        List<Map<String, Object>> goalSummaries = new ArrayList<>();

        for (SmartGoal goal : teamGoals) {
            Set<DocType> coveredTypes = getCoveredDocTypes(goal.getId(), teamCode);
            long goalFindingCount = findings.stream()
                .filter(finding -> Objects.equals(goal.getId(), finding.getGoalId()))
                .count();

            if (goalFindingCount == 0 && coveredTypes.containsAll(REQUIRED_DOC_TYPES)) {
                readyGoals++;
            }

            totalMappedComponents += coveredTypes.size();

            Map<String, Object> goalSummary = new LinkedHashMap<>();
            goalSummary.put("goalId", goal.getId());
            goalSummary.put("description", goal.getDescription());
            goalSummary.put("coveredDocTypes", coveredTypes.stream().map(Enum::name).toList());
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
            severityCounts.computeIfPresent(finding.getSeverity().name(), (ignored, count) -> count + 1);
            if (!recommendationsByFinding.getOrDefault(finding.getId(), List.of()).isEmpty()) {
                resolvedFindingCount++;
            }
        }

        int readinessScore = totalGoals == 0
            ? 0
            : (int) Math.round((readyGoals * 100.0) / totalGoals);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("teamCode", teamCode);
        summary.put("status", statusFor(readinessScore, totalFindings));
        summary.put("readinessScore", readinessScore);
        summary.put("totalGoals", totalGoals);
        summary.put("readyGoals", readyGoals);
        summary.put("totalFindings", totalFindings);
        summary.put("findingsWithRecommendations", resolvedFindingCount);
        summary.put("totalMappedComponents", totalMappedComponents);
        summary.put("severityCounts", severityCounts);
        summary.put("goalSummaries", goalSummaries);
        return summary;
    }

    private List<SmartGoal> getTeamGoals(String teamCode) {
        List<SmartGoal> teamGoals = new ArrayList<>();
        for (SmartGoal goal : goalRepository.findAll()) {
            if (!getCoveredDocTypes(goal.getId(), teamCode).isEmpty()) {
                teamGoals.add(goal);
            }
        }
        return teamGoals;
    }

    private Set<DocType> getCoveredDocTypes(Long goalId, String teamCode) {
        Set<DocType> coveredTypes = new HashSet<>();
        List<GoalComponentMapping> mappings = mappingRepository.findByGoalId(goalId);
        if (mappings.isEmpty()) {
            return coveredTypes;
        }

        List<Long> componentIds = mappings.stream()
            .map(GoalComponentMapping::getComponentId)
            .filter(Objects::nonNull)
            .toList();

        Map<Long, TraceComponent> componentsById = new HashMap<>();
        for (TraceComponent component : componentRepository.findAllById(componentIds)) {
            componentsById.put(component.getId(), component);
        }

        for (GoalComponentMapping mapping : mappings) {
            TraceComponent component = componentsById.get(mapping.getComponentId());
            if (component == null) {
                continue;
            }
            if (!teamComponentResolver.belongsToTeam(component, teamCode)) {
                continue;
            }
            coveredTypes.add(component.getDocType());
        }

        return coveredTypes;
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

    private String statusFor(int readinessScore, int totalFindings) {
        if (totalFindings == 0 && readinessScore == 100) {
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