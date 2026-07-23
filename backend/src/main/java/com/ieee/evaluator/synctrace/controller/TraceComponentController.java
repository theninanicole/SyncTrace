package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.service.TraceComponentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Optional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/synctrace/components")
public class TraceComponentController {

    private final TraceComponentService componentService;
    private final EvaluationHistoryRepository historyRepository;

    public TraceComponentController(
            TraceComponentService componentService,
            EvaluationHistoryRepository historyRepository) {
        this.componentService = componentService;
        this.historyRepository = historyRepository;
    }

    @GetMapping
    public ResponseEntity<?> getTraceComponents(
            @RequestParam(required = false) DocType docType,
            @RequestParam(required = false) String search) {
        try {
            return ResponseEntity.ok(componentService.getComponents(docType, search));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch components: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getTraceComponent(@PathVariable Long id) {
        try {
            Optional<TraceComponent> componentOpt = componentService.getComponentById(id);
            if (componentOpt.isPresent()) {
                return ResponseEntity.ok(componentOpt.get());
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Component not found"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch component: " + e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> createTraceComponent(@RequestBody Map<String, Object> payload) {
        try {
            String docTypeStr = (String) payload.get("docType");
            String name = (String) payload.get("name");
            String content = (String) payload.get("content");

            if (docTypeStr == null || docTypeStr.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "docType is required"));
            }
            if (name == null || name.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
            }

            DocType docType = DocType.valueOf(docTypeStr.toUpperCase());
            return ResponseEntity.ok(componentService.createComponent(docType, name, content));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid docType: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create component: " + e.getMessage()));
        }
    }

    @PutMapping("/{componentId}")
    public ResponseEntity<?> renameTraceComponent(@PathVariable Long componentId, @RequestBody Map<String, String> payload) {
        try {
            String name = payload.get("name");
            if (name == null || name.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
            }
            return ResponseEntity.ok(componentService.renameComponent(componentId, name));
        } catch (RuntimeException e) {
            if (e.getMessage().contains("already has that name")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to rename component: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to rename component: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{componentId}")
    public ResponseEntity<?> deleteTraceComponent(@PathVariable Long componentId) {
        try {
            componentService.deleteComponent(componentId);
            return ResponseEntity.ok(Map.of("message", "Component deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete component: " + e.getMessage()));
        }
    }

    @PostMapping("/extract")
    public ResponseEntity<?> extractTraceComponents(@RequestBody Map<String, Long> payload) {
        try {
            Long historyId = payload.get("historyId");
            if (historyId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "historyId is required"));
            }

            EvaluationHistory history = historyRepository.findById(historyId)
                .orElseThrow(() -> new RuntimeException("Evaluation record not found"));

            String evaluationResult = history.getEvaluationResult();
            if (evaluationResult == null || evaluationResult.isBlank()) {
                return ResponseEntity.ok(Map.of("components", List.of(), "count", 0));
            }

            List<TraceComponent> extractedComponents = extractComponentsFromEvaluation(
                evaluationResult, historyId, history.getExtractedImages());

            return ResponseEntity.ok(Map.of(
                "components", extractedComponents,
                "count", extractedComponents.size()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Extraction failed: " + e.getMessage()));
        }
    }

    private List<TraceComponent> extractComponentsFromEvaluation(
            String evaluationResult, Long historyId, List<String> extractedImages) {
        
        List<TraceComponent> components = new ArrayList<>();
        
        // Look for "Diagram Analysis" section
        int diagramSectionStart = findSectionStart(evaluationResult, "Diagram Analysis");
        if (diagramSectionStart == -1) {
            return components;
        }

        int diagramSectionEnd = findNextSectionStart(evaluationResult, diagramSectionStart + 14);
        String diagramSection = diagramSectionEnd == -1 
            ? evaluationResult.substring(diagramSectionStart).trim()
            : evaluationResult.substring(diagramSectionStart, diagramSectionEnd).trim();

        // Parse individual diagram findings
        String[] lines = diagramSection.split("\n");
        StringBuilder currentFinding = new StringBuilder();
        String currentImageRef = null;
        
        for (String line : lines) {
            line = line.trim();
            
            // Check for image reference like [IMG-1]
            if (line.matches("\\[IMG-\\d+\\].*")) {
                if (currentFinding.length() > 0) {
                    TraceComponent component = createComponentFromFinding(
                        currentFinding.toString(), historyId, currentImageRef, extractedImages);
                    if (component != null) {
                        components.add(component);
                    }
                }
                currentImageRef = extractImageRef(line);
                currentFinding = new StringBuilder(line);
            } else if (line.startsWith("*") || line.startsWith("-")) {
                // New finding starting
                if (currentFinding.length() > 0) {
                    TraceComponent component = createComponentFromFinding(
                        currentFinding.toString(), historyId, currentImageRef, extractedImages);
                    if (component != null) {
                        components.add(component);
                    }
                }
                currentFinding = new StringBuilder(line);
                currentImageRef = null;
            } else {
                currentFinding.append("\n").append(line);
            }
        }

        // Don't forget the last finding
        if (currentFinding.length() > 0) {
            TraceComponent component = createComponentFromFinding(
                currentFinding.toString(), historyId, currentImageRef, extractedImages);
            if (component != null) {
                components.add(component);
            }
        }

        return components;
    }

    private TraceComponent createComponentFromFinding(String findingText, Long historyId, String imageRef, List<String> extractedImages) {
        // Determine docType based on content
        DocType docType = determineDocTypeFromFinding(findingText);
        
        String imageData = null;
        if (imageRef != null && imageRef.matches("\\[IMG-(\\d+)\\]")) {
            int imageIndex = Integer.parseInt(imageRef.replaceAll("[^0-9]", "")) - 1;
            // Resolve the actual base64 image data from extractedImages with bounds checking
            if (extractedImages != null && imageIndex >= 0 && imageIndex < extractedImages.size()) {
                imageData = extractedImages.get(imageIndex);
            }
        }

        TraceComponent component = new TraceComponent();
        component.setDocType(docType);
        component.setName(extractComponentName(findingText));
        component.setContent(findingText);
        component.setSourceHistoryId(historyId);
        component.setImageData(imageData);
        component.setAiExtracted(true);
        component.setCreatedAt(LocalDateTime.now());
        
        return component;
    }

    private DocType determineDocTypeFromFinding(String findingText) {
        String lower = findingText.toLowerCase();
        
        // Check for implementation-related keywords
        if (lower.contains("source code") || lower.contains("repository") || 
            lower.contains("implementation evidence") || lower.contains("implemented in") ||
            lower.contains("codebase") || lower.contains("method implementation") || 
            lower.contains("class implementation")) {
            return DocType.IMPLEMENTATION;
        }
        
        // Check for SDD diagram types
        if (lower.contains("class diagram") || lower.contains("entity-relationship") || lower.contains("erd")) {
            return DocType.SDD;
        }
        if (lower.contains("use case") || lower.contains("context diagram")) {
            return DocType.SDD;
        }
        if (lower.contains("data flow") || lower.contains("dfd")) {
            return DocType.SDD;
        }
        
        // Default to SDD for diagram findings
        return DocType.SDD;
    }

    private String extractComponentName(String findingText) {
        String[] lines = findingText.split("\n");
        if (lines.length > 0) {
            String firstLine = lines[0].trim();
            // Remove bullet points and image refs
            return firstLine.replaceAll("^[*\\-]\\s*", "")
                           .replaceAll("\\[IMG-\\d+\\]\\s*", "")
                           .trim();
        }
        return "Extracted Component";
    }

    private String extractImageRef(String line) {
        if (line.matches(".*\\[IMG-\\d+\\].*")) {
            return line.replaceAll(".*\\[IMG-(\\d+)\\].*", "[IMG-$1]");
        }
        return null;
    }

    private int findSectionStart(String text, String sectionName) {
        String lower = text.toLowerCase();
        String target = sectionName.toLowerCase();
        int idx = lower.indexOf(target);
        if (idx == -1) return -1;
        while (idx > 0 && text.charAt(idx - 1) != '\n') idx--;
        return idx;
    }

    private int findNextSectionStart(String text, int fromIndex) {
        String[] headers = {
            "Diagram Analysis", "Missing Sections", "Weaknesses",
            "Recommendations", "Strengths", "Summary", "Conclusion",
            "Rubric Evaluation", "Revision Analysis"
        };
        int earliest = -1;
        String lower = text.toLowerCase();
        for (String header : headers) {
            int idx = lower.indexOf(header.toLowerCase(), fromIndex);
            if (idx != -1 && (earliest == -1 || idx < earliest)) earliest = idx;
        }
        return earliest;
    }
}
