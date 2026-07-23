package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.model.TraceComponentSummaryDTO;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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

        return components.stream()
            .map(c -> new TraceComponentSummaryDTO(
                c.getId(),
                c.getDocType(),
                c.getName(),
                c.getAiExtracted(),
                c.getSourceHistoryId(),
                c.getCreatedAt()
            ))
            .toList();
    }

    public Optional<TraceComponent> getComponentById(Long id) {
        return componentRepository.findById(id);
    }

    @Transactional
    public TraceComponent createComponent(DocType docType, String name, String content) {
        // Dedupe by (docType, name) case-insensitively
        Optional<TraceComponent> existing = componentRepository.findByDocTypeAndNameIgnoreCase(docType, name.trim());
        if (existing.isPresent()) {
            return existing.get();
        }

        TraceComponent component = new TraceComponent();
        component.setDocType(docType);
        component.setName(name.trim());
        component.setContent(content);
        component.setAiExtracted(false);
        component.setCreatedAt(LocalDateTime.now());
        return componentRepository.save(component);
    }

    @Transactional
    public TraceComponent renameComponent(Long componentId, String newName) {
        TraceComponent component = componentRepository.findById(componentId)
            .orElseThrow(() -> new RuntimeException("Component not found"));

        // Check if another component of the same docType already has that name
        Optional<TraceComponent> existing = componentRepository.findByDocTypeAndNameIgnoreCase(
            component.getDocType(), newName.trim());
        if (existing.isPresent() && !existing.get().getId().equals(componentId)) {
            throw new RuntimeException(
                "Another " + component.getDocType() + " component already has that name."
            );
        }

        component.setName(newName.trim());
        return componentRepository.save(component);
    }

    @Transactional
    public void deleteComponent(Long componentId) {
        // Cascade delete mappings
        mappingRepository.deleteByComponentId(componentId);
        componentRepository.deleteById(componentId);
    }
}
