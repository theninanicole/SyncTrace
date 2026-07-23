package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.ContinuityFinding.Severity;
import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
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
        // Clear existing findings for this team/goal
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

        // Get all goals or specific goal
        List<Long> goalIds = goalId != null ? List.of(goalId) : getAllGoalIds();

        for (Long currentGoalId : goalIds) {
            // Get all mappings for this goal
            List<GoalComponentMapping> mappings = mappingRepository.findByGoalId(currentGoalId);
            
            // Group by docType
            Map<DocType, List<GoalComponentMapping>> byDocType = new HashMap<>();
            for (GoalComponentMapping mapping : mappings) {
                byDocType.computeIfAbsent(getDocTypeForComponent(mapping.getComponentId()), k -> new ArrayList<>())
                    .add(mapping);
            }

            // Check for gaps in the 5 sequential pairs
            checkMissingSrs(findings, teamCode, currentGoalId, byDocType);
            checkGap(findings, teamCode, currentGoalId, DocType.SRS, DocType.SDD, byDocType);
            checkGap(findings, teamCode, currentGoalId, DocType.SRS, DocType.SPMP, byDocType);
            checkGap(findings, teamCode, currentGoalId, DocType.SRS, DocType.STD, byDocType);
            checkGap(findings, teamCode, currentGoalId, DocType.SDD, DocType.IMPLEMENTATION, byDocType);
        }

        return findingRepository.saveAll(findings);
    }

    private void checkMissingSrs(
            List<ContinuityFinding> findings, String teamCode, Long goalId,
            Map<DocType, List<GoalComponentMapping>> byDocType) {
        boolean hasSrs = byDocType.containsKey(DocType.SRS) && !byDocType.get(DocType.SRS).isEmpty();
        if (!hasSrs) {
            ContinuityFinding finding = new ContinuityFinding();
            finding.setTeamCode(teamCode);
            finding.setGoalId(goalId);
            finding.setDocTypeFrom(DocType.PROPOSAL); // symbolic label only — never a real component
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
            Map<DocType, List<GoalComponentMapping>> byDocType) {
        
        boolean hasFrom = byDocType.containsKey(fromType) && !byDocType.get(fromType).isEmpty();
        boolean hasTo = byDocType.containsKey(toType) && !byDocType.get(toType).isEmpty();

        if (hasFrom && !hasTo) {
            // Gap: goal has component in 'from' but missing in 'to'
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

    private DocType getDocTypeForComponent(Long componentId) {
        return componentRepository.findById(componentId)
            .map(com.ieee.evaluator.synctrace.model.TraceComponent::getDocType)
            .orElse(null);
    }

    private List<Long> getAllGoalIds() {
        return goalRepository.findAll().stream()
            .map(com.ieee.evaluator.synctrace.model.SmartGoal::getId)
            .toList();
    }
}
