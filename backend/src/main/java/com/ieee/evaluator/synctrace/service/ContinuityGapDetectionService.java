package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.ContinuityFinding.Severity;
import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.model.ArtifactKind;
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
    private final TeamComponentResolverService teamComponentResolver;

    public ContinuityGapDetectionService(
            GoalComponentMappingRepository mappingRepository,
            TraceComponentRepository componentRepository,
            ContinuityFindingRepository findingRepository,
            SmartGoalRepository goalRepository,
            TeamComponentResolverService teamComponentResolver) {
        this.mappingRepository = mappingRepository;
        this.componentRepository = componentRepository;
        this.findingRepository = findingRepository;
        this.goalRepository = goalRepository;
        this.teamComponentResolver = teamComponentResolver;
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
            if (!goalRepository.existsById(currentGoalId)) continue;

            List<GoalComponentMapping> mappings = mappingRepository.findByGoalId(currentGoalId);
            Map<DocType, List<com.ieee.evaluator.synctrace.model.TraceComponent>> byDocType = mapTeamScopedComponentsByDocType(mappings, teamCode);

            if (teamCode != null && !teamCode.isBlank() && byDocType.isEmpty()) {
                continue;
            }

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
            Map<DocType, List<com.ieee.evaluator.synctrace.model.TraceComponent>> componentsByDocType) {
        Set<ArtifactKind> requiredKinds = Set.of(ArtifactKind.USE_CASE, ArtifactKind.ACTIVITY, ArtifactKind.WIREFRAME);
        Set<ArtifactKind> presentKinds = componentsByDocType.getOrDefault(DocType.SRS, List.of()).stream()
                .map(com.ieee.evaluator.synctrace.model.TraceComponent::getArtifactKind)
                .collect(java.util.stream.Collectors.toSet());
        for (ArtifactKind requiredKind : requiredKinds) {
            if (!presentKinds.contains(requiredKind)) {
                addFinding(findings, teamCode, goalId, DocType.PROPOSAL, DocType.SRS, Severity.HIGH,
                        "SMART Goal is missing required SRS " + requiredKind + " component.");
            }
        }
        if (!componentsByDocType.containsKey(DocType.SRS)) {
            ContinuityFinding finding = new ContinuityFinding();
            finding.setTeamCode(teamCode);
            finding.setGoalId(goalId);
            finding.setDocTypeFrom(DocType.PROPOSAL);
            finding.setDocTypeTo(DocType.SRS);
            finding.setSeverity(Severity.HIGH);
            finding.setDescription("SMART Goal has no mapped SRS component.");
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
            Map<DocType, List<com.ieee.evaluator.synctrace.model.TraceComponent>> componentsByDocType) {
        List<com.ieee.evaluator.synctrace.model.TraceComponent> sources = componentsByDocType.getOrDefault(fromType, List.of());
        List<com.ieee.evaluator.synctrace.model.TraceComponent> targets = componentsByDocType.getOrDefault(toType, List.of());
        // A missing target means this downstream stage has not been established yet;
        // do not report it as an issue until the stage contains mapped components.
        if (sources.isEmpty() || targets.isEmpty()) return;
        for (com.ieee.evaluator.synctrace.model.TraceComponent source : sources) {
            boolean matched = targets.stream().anyMatch(target -> semanticallyCorresponds(source, target));
            if (!matched) {
                addFinding(findings, teamCode, goalId, fromType, toType, Severity.HIGH,
                        "Component '" + source.getName() + "' has no semantically corresponding " + toType + " component.");
            }
        }
    }

    private Map<DocType, List<com.ieee.evaluator.synctrace.model.TraceComponent>> mapTeamScopedComponentsByDocType(
            List<GoalComponentMapping> mappings,
            String teamCode) {
        Map<DocType, List<com.ieee.evaluator.synctrace.model.TraceComponent>> byDocType = new HashMap<>();

        if (mappings.isEmpty()) {
            return byDocType;
        }

        List<Long> componentIds = mappings.stream()
            .map(GoalComponentMapping::getComponentId)
            .filter(Objects::nonNull)
            .toList();

        Map<Long, com.ieee.evaluator.synctrace.model.TraceComponent> componentsById = componentRepository.findAllById(componentIds)
            .stream()
            .collect(HashMap::new, (acc, component) -> acc.put(component.getId(), component), HashMap::putAll);

        for (GoalComponentMapping mapping : mappings) {
            com.ieee.evaluator.synctrace.model.TraceComponent component = componentsById.get(mapping.getComponentId());
            if (component == null) {
                continue;
            }
            if (teamCode != null && !teamCode.isBlank() && !teamComponentResolver.belongsToTeam(component, teamCode)) {
                continue;
            }

            byDocType.computeIfAbsent(component.getDocType(), ignored -> new ArrayList<>()).add(component);
        }

        return byDocType;
    }

    private boolean semanticallyCorresponds(
            com.ieee.evaluator.synctrace.model.TraceComponent source,
            com.ieee.evaluator.synctrace.model.TraceComponent target) {
        Set<String> sourceTokens = conceptTokens(source);
        Set<String> targetTokens = conceptTokens(target);
        sourceTokens.retainAll(targetTokens);
        return !sourceTokens.isEmpty();
    }

    private Set<String> conceptTokens(com.ieee.evaluator.synctrace.model.TraceComponent component) {
        String text = String.join(" ",
                Objects.toString(component.getName(), ""),
                Objects.toString(component.getCodeName(), ""),
                Objects.toString(component.getContent(), ""))
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ");
        Set<String> tokens = new HashSet<>();
        for (String token : text.split(" ")) {
            if (token.length() >= 4) tokens.add(token);
        }
        return tokens;
    }

    private void addFinding(List<ContinuityFinding> findings, String teamCode, Long goalId,
            DocType fromType, DocType toType, Severity severity, String description) {
        ContinuityFinding finding = new ContinuityFinding();
        finding.setTeamCode(teamCode);
        finding.setGoalId(goalId);
        finding.setDocTypeFrom(fromType);
        finding.setDocTypeTo(toType);
        finding.setSeverity(severity);
        finding.setDescription(description);
        finding.setDetectedAt(LocalDateTime.now());
        findings.add(finding);
    }

    private List<Long> getAllGoalIds() {
        List<Long> goalIds = new ArrayList<>();
        goalRepository.findAll().forEach(goal -> goalIds.add(goal.getId()));
        return goalIds;
    }
}
