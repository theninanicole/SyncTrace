package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.ContinuityFinding.Severity;
import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.SmartGoal.GoalKind;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
public class ContinuityGapDetectionService {

    private final GoalComponentMappingRepository mappingRepository;
    private final TraceComponentRepository componentRepository;
    private final ContinuityFindingRepository findingRepository;
    private final SmartGoalRepository goalRepository;

    public ContinuityGapDetectionService(
            GoalComponentMappingRepository mappingRepository,
            TraceComponentRepository componentRepository,
            ContinuityFindingRepository findingRepository,
            SmartGoalRepository goalRepository) {
        this.mappingRepository = mappingRepository;
        this.componentRepository = componentRepository;
        this.findingRepository = findingRepository;
        this.goalRepository = goalRepository;
    }

    @Transactional
    public List<ContinuityFinding> detectGaps(String teamCode, Long goalId) {
        if (goalId != null) {
            findingRepository.findByGoalId(goalId).forEach(f -> {
                if (teamCode == null || teamCode.equals(f.getTeamCode())) {
                    findingRepository.delete(f);
                }
            });
        } else if (teamCode != null) {
            findingRepository.deleteByTeamCode(teamCode);
        }

        List<ContinuityFinding> findings = new ArrayList<>();
        List<Long> goalIds = goalId != null ? List.of(goalId) : getAllGoalIds();

        for (Long currentGoalId : goalIds) {
            Optional<SmartGoal> goalOpt = goalRepository.findById(currentGoalId);
            if (goalOpt.isEmpty()) continue;
            SmartGoal goal = goalOpt.get();

            List<GoalComponentMapping> mappings = mappingRepository.findByGoalId(currentGoalId);
            Map<DocType, List<TraceComponent>> byDocType = new HashMap<>();
            List<TraceComponent> mappedComponents = new ArrayList<>();

            for (GoalComponentMapping mapping : mappings) {
                componentRepository.findById(mapping.getComponentId()).ifPresent(component -> {
                    mappedComponents.add(component);
                    if (component.getDocType() != null) {
                        byDocType.computeIfAbsent(component.getDocType(), k -> new ArrayList<>()).add(component);
                    }
                });
            }

            checkMissingSrs(findings, teamCode, currentGoalId, byDocType);
            checkGap(findings, teamCode, currentGoalId, DocType.SRS, DocType.SDD, byDocType);
            checkGap(findings, teamCode, currentGoalId, DocType.SRS, DocType.SPMP, byDocType);
            checkGap(findings, teamCode, currentGoalId, DocType.SRS, DocType.STD, byDocType);
            checkGap(findings, teamCode, currentGoalId, DocType.SDD, DocType.IMPLEMENTATION, byDocType);
            checkGoalKindAlignment(findings, teamCode, goal, mappedComponents);
        }

        return findingRepository.saveAll(findings);
    }

    private void checkMissingSrs(
            List<ContinuityFinding> findings, String teamCode, Long goalId,
            Map<DocType, List<TraceComponent>> byDocType) {
        boolean hasSrs = byDocType.containsKey(DocType.SRS) && !byDocType.get(DocType.SRS).isEmpty();
        if (!hasSrs) {
            ContinuityFinding finding = new ContinuityFinding();
            finding.setTeamCode(teamCode);
            finding.setGoalId(goalId);
            finding.setDocTypeFrom(DocType.PROPOSAL);
            finding.setDocTypeTo(DocType.SRS);
            finding.setSeverity(Severity.HIGH);
            finding.setDescription("Goal has no mapped SRS component. This represents a continuity gap.");
            finding.setDetectedAt(LocalDateTime.now());
            findings.add(finding);
        }
    }

    private void checkGap(
            List<ContinuityFinding> findings,
            String teamCode,
            Long goalId,
            DocType fromType,
            DocType toType,
            Map<DocType, List<TraceComponent>> byDocType) {

        boolean hasFrom = byDocType.containsKey(fromType) && !byDocType.get(fromType).isEmpty();
        boolean hasTo = byDocType.containsKey(toType) && !byDocType.get(toType).isEmpty();

        if (hasFrom && !hasTo) {
            ContinuityFinding finding = new ContinuityFinding();
            finding.setTeamCode(teamCode);
            finding.setGoalId(goalId);
            finding.setDocTypeFrom(fromType);
            finding.setDocTypeTo(toType);
            finding.setSeverity(Severity.HIGH);
            finding.setDescription(String.format(
                "Goal has components in %s but no mapped components in %s. This represents a continuity gap.",
                fromType, toType));
            finding.setDetectedAt(LocalDateTime.now());
            findings.add(finding);
        }
    }

    private void checkGoalKindAlignment(
            List<ContinuityFinding> findings,
            String teamCode,
            SmartGoal goal,
            List<TraceComponent> mappedComponents) {

        if (mappedComponents.isEmpty()) return;

        GoalKind kind = goal.getGoalKind() != null ? goal.getGoalKind() : GoalKind.SPECIFIC;
        boolean hasPreferred = mappedComponents.stream()
            .map(TraceComponent::getArtifactKind)
            .filter(Objects::nonNull)
            .anyMatch(ak -> GoalArtifactAlignment.isPreferred(kind, ak));

        if (hasPreferred) return;

        ContinuityFinding finding = new ContinuityFinding();
        finding.setTeamCode(teamCode);
        finding.setGoalId(goal.getId());
        finding.setDocTypeFrom(DocType.PROPOSAL);
        finding.setDocTypeTo(DocType.SRS);
        finding.setSeverity(Severity.MEDIUM);
        finding.setDescription(kind == GoalKind.GENERAL
            ? "GENERAL objective is mapped, but none of the linked artifacts look module-level. "
              + GoalArtifactAlignment.preferredHint(kind)
            : "SPECIFIC objective is mapped, but none of the linked artifacts look like functions/transactions. "
              + GoalArtifactAlignment.preferredHint(kind));
        finding.setDetectedAt(LocalDateTime.now());
        findings.add(finding);
    }

    private List<Long> getAllGoalIds() {
        return goalRepository.findAll().stream()
            .map(SmartGoal::getId)
            .toList();
    }
}
