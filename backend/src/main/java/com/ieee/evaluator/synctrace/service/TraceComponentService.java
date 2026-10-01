package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.model.TraceComponentSummaryDTO;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.repository.StagedTraceMappingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class TraceComponentService {

    private static final Pattern NUMBERED_CODE = Pattern.compile("^([A-Z]+)-(\\d{1,3})$");

    private final TraceComponentRepository componentRepository;
    private final GoalComponentMappingRepository mappingRepository;
    private final StagedTraceMappingRepository stagedMappingRepository;
    private final SmartGoalRepository goalRepository;
    private final ContinuityAnalysisResetService analysisResetService;

    public TraceComponentService(
            TraceComponentRepository componentRepository,
            GoalComponentMappingRepository mappingRepository,
            StagedTraceMappingRepository stagedMappingRepository,
            SmartGoalRepository goalRepository,
            ContinuityAnalysisResetService analysisResetService) {
        this.componentRepository = componentRepository;
        this.mappingRepository = mappingRepository;
        this.stagedMappingRepository = stagedMappingRepository;
        this.goalRepository = goalRepository;
        this.analysisResetService = analysisResetService;
    }

    public List<TraceComponentSummaryDTO> getComponents(DocType docType, String search) {
        List<TraceComponent> components;

        if (docType != null && search != null && !search.isBlank()) {
            components = componentRepository.findByDocTypeAndNameContainingIgnoreCase(docType, search);
        } else if (docType != null) {
            components = componentRepository.findByDocTypeOrderByCreatedAtDesc(docType);
        } else if (search != null && !search.isBlank()) {
            components = componentRepository.findByNameContainingIgnoreCase(search);
        } else {
            components = componentRepository.findAll();
        }

        return components.stream().map(this::toSummary).toList();
    }

    public Optional<TraceComponent> getComponentById(Long id) {
        return componentRepository.findById(id);
    }

    @Transactional
    public TraceComponent createComponent(DocType docType, String name, String content) {
        return createComponent(docType, null, name, content, null);
    }

    @Transactional
    public TraceComponent createComponent(DocType docType, ArtifactKind artifactKind, String name, String content) {
        return createComponent(docType, artifactKind, name, content, null);
    }

    @Transactional
    public TraceComponent createComponent(
            DocType docType, ArtifactKind artifactKind, String name, String content, String codeName) {
        return createComponent(docType, artifactKind, name, content, codeName, null);
    }

    @Transactional
    public TraceComponent createComponent(
            DocType docType, ArtifactKind artifactKind, String name, String content, String codeName,
            String imageData) {
        Optional<TraceComponent> existing = componentRepository.findByDocTypeAndNameIgnoreCase(docType, name.trim());
        if (existing.isPresent()) {
            return existing.get();
        }

        ArtifactKind kind = artifactKind != null ? artifactKind : ArtifactKind.defaultFor(docType);
        if (kind != ArtifactKind.UNSPECIFIED) {
            DocType inferred = kind.toDocType();
            if (inferred != null && inferred != docType) {
                throw new IllegalArgumentException(
                    "artifactKind " + kind + " does not belong to docType " + docType);
            }
        }

        String resolvedCode = ComponentCodeHelper.resolveDisplayCode(codeName, name, content);
        if (resolvedCode == null) {
            resolvedCode = ComponentCodeHelper.normalizeCode(name.trim());
            if (resolvedCode != null && resolvedCode.length() > 40) {
                resolvedCode = null;
            }
        }

        TraceComponent component = new TraceComponent();
        component.setDocType(docType);
        component.setArtifactKind(kind);
        component.setName(name.trim());
        component.setCodeName(resolvedCode);
        component.setContent(content);
        component.setImageData(imageData);
        component.setAiExtracted(false);
        component.setSourceType("MANUAL");
        component.setSourceCapturedAt(LocalDateTime.now());
        component.setCreatedAt(LocalDateTime.now());
        return componentRepository.save(component);
    }

    @Transactional
    public TraceComponent renameComponent(Long componentId, String newName) {
        TraceComponent component = componentRepository.findById(componentId)
            .orElseThrow(() -> new RuntimeException("Component not found"));

        Optional<TraceComponent> existing = componentRepository.findByDocTypeAndNameIgnoreCase(
            component.getDocType(), newName.trim());
        if (existing.isPresent() && !existing.get().getId().equals(componentId)) {
            throw new RuntimeException(
                "Another " + component.getDocType() + " component already has that name."
            );
        }

        component.setName(newName.trim());
        String maybeCode = ComponentCodeHelper.extractPrimaryCode(newName);
        if (maybeCode != null) {
            component.setCodeName(maybeCode);
        }
        return componentRepository.save(component);
    }

    @Transactional
    public TraceComponent updateCodeName(Long componentId, String codeName) {
        TraceComponent component = componentRepository.findById(componentId)
            .orElseThrow(() -> new RuntimeException("Component not found"));
        // A user-chosen code is saved exactly as typed; only auto-extracted codes are normalized.
        String trimmed = codeName.trim();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("Component name must be 64 characters or fewer.");
        }
        component.setCodeName(trimmed);
        return componentRepository.save(component);
    }

    @Transactional
    public void deleteComponent(Long componentId) {
        // Removing a linked component changes those goals' links, so their teams' analysis is stale.
        Set<Long> linkedGoalIds = new HashSet<>();
        mappingRepository.findByComponentId(componentId).forEach(m -> linkedGoalIds.add(m.getGoalId()));
        Set<String> affectedTeams = new HashSet<>();
        goalRepository.findAllById(linkedGoalIds).forEach(goal -> {
            if (goal.getTeamCode() != null) affectedTeams.add(goal.getTeamCode());
        });

        mappingRepository.deleteByComponentId(componentId);
        stagedMappingRepository.deleteComponentReferences(componentId);
        componentRepository.deleteById(componentId);
        affectedTeams.forEach(analysisResetService::resetTeam);
    }

    /**
     * Persists AI-extracted components into the library. A diagram that was already extracted
     * from the same evaluation is recognised by its finding text rather than its code: codes
     * are auto-numbered per extraction and can be renamed, so after diagrams are added to the
     * evaluation a new diagram can land on a code an older one already holds.
     */
    @Transactional
    public List<TraceComponent> persistExtractedComponents(List<TraceComponent> extracted) {
        List<TraceComponent> saved = new ArrayList<>();
        // Components already matched or created in this batch; each can back only one candidate.
        Set<Long> claimed = new HashSet<>();
        Map<Long, List<TraceComponent>> historyComponents = new HashMap<>();
        for (TraceComponent candidate : extracted) {
            if (candidate.getName() == null || candidate.getName().isBlank() || candidate.getDocType() == null) {
                continue;
            }
            String safeName = truncate(candidate.getName().trim(), 250);
            candidate.setName(safeName);

            String code = ComponentCodeHelper.resolveDisplayCode(
                candidate.getCodeName(), candidate.getName(), candidate.getContent());
            candidate.setCodeName(code);

            List<TraceComponent> sameHistory = candidate.getSourceHistoryId() == null
                ? new ArrayList<>()
                : historyComponents.computeIfAbsent(candidate.getSourceHistoryId(),
                    id -> new ArrayList<>(componentRepository.findAllBySourceHistoryId(id)));

            // Only a previous extraction of this same evaluation counts as the same diagram.
            // Manually added components aren't tied to a team or evaluation, so merging into one
            // on a shared code made extracted diagrams silently disappear.
            Optional<TraceComponent> existing = matchPreviousExtraction(candidate, sameHistory, claimed);
            if (existing.isPresent()) {
                TraceComponent found = existing.get();
                claimed.add(found.getId());
                boolean dirty = false;
                if ((found.getCodeName() == null || found.getCodeName().isBlank()) && code != null) {
                    found.setCodeName(code);
                    dirty = true;
                }
                if (found.getImageData() == null && candidate.getImageData() != null) {
                    found.setImageData(candidate.getImageData());
                    dirty = true;
                }
                if (dirty) {
                    found = componentRepository.save(found);
                }
                saved.add(found);
                continue;
            }
            candidate.setCodeName(uniqueCode(code, candidate.getDocType(), sameHistory));
            if (candidate.getArtifactKind() == null) {
                candidate.setArtifactKind(ArtifactKind.defaultFor(candidate.getDocType()));
            }
            if (candidate.getCreatedAt() == null) {
                candidate.setCreatedAt(LocalDateTime.now());
            }
            candidate.setAiExtracted(true);
            TraceComponent persisted = componentRepository.save(candidate);
            claimed.add(persisted.getId());
            sameHistory.add(persisted);
            saved.add(persisted);
        }
        return saved;
    }

    private static Optional<TraceComponent> matchPreviousExtraction(
            TraceComponent candidate, List<TraceComponent> sameHistory, Set<Long> claimed) {
        String content = normalizeText(candidate.getContent());
        List<TraceComponent> sameDiagram = sameHistory.stream()
            .filter(c -> !claimed.contains(c.getId()))
            .filter(c -> c.getDocType() == candidate.getDocType())
            .filter(c -> normalizeText(c.getContent()).equals(content))
            .toList();
        if (sameDiagram.size() <= 1) return sameDiagram.stream().findFirst();
        // One diagram that lists several elements (UC-01, UC-02, ...) yields one component per element.
        return sameDiagram.stream()
            .filter(c -> c.getName() != null && c.getName().equalsIgnoreCase(candidate.getName()))
            .findFirst()
            .or(() -> sameDiagram.stream()
                .filter(c -> candidate.getCodeName() != null && candidate.getCodeName().equalsIgnoreCase(c.getCodeName()))
                .findFirst())
            .or(() -> Optional.of(sameDiagram.get(0)));
    }

    /** Moves an auto-numbered code (AD-01) past the numbers this evaluation's components already use. */
    private static String uniqueCode(String code, DocType docType, List<TraceComponent> sameHistory) {
        if (code == null) return null;
        Set<String> taken = new HashSet<>();
        for (TraceComponent c : sameHistory) {
            if (c.getDocType() == docType && c.getCodeName() != null) {
                taken.add(c.getCodeName().toUpperCase(Locale.ROOT));
            }
        }
        if (!taken.contains(code.toUpperCase(Locale.ROOT))) return code;
        Matcher m = NUMBERED_CODE.matcher(code);
        if (!m.matches()) return code;
        String prefix = m.group(1);
        int next = Integer.parseInt(m.group(2));
        String candidate;
        do {
            next++;
            candidate = prefix + "-" + String.format(Locale.ROOT, "%02d", next);
        } while (taken.contains(candidate));
        return candidate;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    public TraceComponentSummaryDTO toSummary(TraceComponent c) {
        ArtifactKind kind = c.getArtifactKind() != null ? c.getArtifactKind() : ArtifactKind.UNSPECIFIED;
        String code = ComponentCodeHelper.resolveDisplayCode(c.getCodeName(), c.getName(), c.getContent());
        return new TraceComponentSummaryDTO(
            c.getId(),
            c.getDocType(),
            kind,
            c.getName(),
            c.getContent(),
            code,
            c.getAiExtracted(),
            c.getSourceHistoryId(),
            c.getSourceType(),
            c.getSourceRef(),
            c.getSourceUrl(),
            c.getSourceCapturedAt(),
            c.getCreatedAt()
        );
    }

    private static String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) return value;
        return value.substring(0, maxLen - 1).trim() + "…";
    }
}
