package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.model.TraceComponentSummaryDTO;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class TraceComponentService {

    private final TraceComponentRepository componentRepository;
    private final GoalComponentMappingRepository mappingRepository;

    public TraceComponentService(
            TraceComponentRepository componentRepository,
            GoalComponentMappingRepository mappingRepository) {
        this.componentRepository = componentRepository;
        this.mappingRepository = mappingRepository;
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
        component.setAiExtracted(false);
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
        // Document artifact codes (UC-01, TC-14, ...) are conventionally uppercase, but
        // implementation components are file names, which are case-sensitive and must
        // not be forced through the same uppercasing normalization.
        String normalized = component.getDocType() == DocType.IMPLEMENTATION
            ? codeName.trim()
            : ComponentCodeHelper.normalizeCode(codeName);
        component.setCodeName(normalized);
        return componentRepository.save(component);
    }

    @Transactional
    public void deleteComponent(Long componentId) {
        mappingRepository.deleteByComponentId(componentId);
        componentRepository.deleteById(componentId);
    }

    /**
     * Persists AI-extracted components into the library, deduping by (docType, codeName) or (docType, name).
     */
    @Transactional
    public List<TraceComponent> persistExtractedComponents(List<TraceComponent> extracted) {
        List<TraceComponent> saved = new ArrayList<>();
        // Distinct diagrams found within the SAME extraction call must never merge with
        // each other just because they coincidentally land on the same fallback code or
        // name — they are always separate components. Tracking IDs saved during this
        // batch lets us restrict merging to components that already existed beforehand
        // (e.g. a genuine re-extraction of the same document), so siblings from this
        // batch are never mistaken for duplicates of one another.
        Set<Long> createdThisBatch = new HashSet<>();
        for (TraceComponent candidate : extracted) {
            if (candidate.getName() == null || candidate.getName().isBlank() || candidate.getDocType() == null) {
                continue;
            }
            String safeName = truncate(candidate.getName().trim(), 250);
            candidate.setName(safeName);

            String code = ComponentCodeHelper.resolveDisplayCode(
                candidate.getCodeName(), candidate.getName(), candidate.getContent());
            candidate.setCodeName(code);

            // Auto-generated fallback codes (UC-01, AD-01, ...) restart from 1 on every
            // extraction, so they can coincidentally match a code already used by a
            // different evaluation's diagrams — multiple components can legitimately
            // share a (docType, codeName) pair as long as they come from different
            // evaluations. Only treat it as the same component when it's unowned
            // (manually created) or belongs to this same evaluation history — otherwise
            // this diagram would get silently merged into an unrelated document's
            // component instead of being extracted for this one.
            Optional<TraceComponent> existing = Optional.empty();
            if (code != null) {
                existing = componentRepository
                    .findAllByDocTypeAndCodeNameIgnoreCase(candidate.getDocType(), code)
                    .stream()
                    .filter(c -> !createdThisBatch.contains(c.getId()))
                    .filter(c -> belongsToSameSource(c, candidate))
                    .findFirst();
            }
            if (existing.isEmpty()) {
                existing = componentRepository.findByDocTypeAndNameIgnoreCase(
                    candidate.getDocType(), safeName)
                    .filter(c -> !createdThisBatch.contains(c.getId()))
                    .filter(c -> belongsToSameSource(c, candidate));
            }
            if (existing.isPresent()) {
                TraceComponent found = existing.get();
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
            if (candidate.getArtifactKind() == null) {
                candidate.setArtifactKind(ArtifactKind.defaultFor(candidate.getDocType()));
            }
            if (candidate.getCreatedAt() == null) {
                candidate.setCreatedAt(LocalDateTime.now());
            }
            candidate.setAiExtracted(true);
            TraceComponent persisted = componentRepository.save(candidate);
            createdThisBatch.add(persisted.getId());
            saved.add(persisted);
        }
        return saved;
    }

    public TraceComponentSummaryDTO toSummary(TraceComponent c) {
        ArtifactKind kind = c.getArtifactKind() != null ? c.getArtifactKind() : ArtifactKind.UNSPECIFIED;
        String code = ComponentCodeHelper.resolveDisplayCode(c.getCodeName(), c.getName(), c.getContent());
        return new TraceComponentSummaryDTO(
            c.getId(),
            c.getDocType(),
            kind,
            c.getName(),
            code,
            c.getAiExtracted(),
            c.getSourceHistoryId(),
            c.getCreatedAt()
        );
    }

    private static boolean belongsToSameSource(TraceComponent existing, TraceComponent candidate) {
        return existing.getSourceHistoryId() == null
            || existing.getSourceHistoryId().equals(candidate.getSourceHistoryId());
    }

    private static String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) return value;
        return value.substring(0, maxLen - 1).trim() + "…";
    }
}
