package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.service.ComponentCodeHelper;
import com.ieee.evaluator.synctrace.service.TraceComponentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Optional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            if (payload.get("artifactKind") == null || String.valueOf(payload.get("artifactKind")).isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "artifactKind is required — pick the exact component type (use case, sequence diagram, test case, ...)"));
            }
            ArtifactKind artifactKind =
                ArtifactKind.valueOf(String.valueOf(payload.get("artifactKind")).trim().toUpperCase());
            if (artifactKind == ArtifactKind.UNSPECIFIED) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "artifactKind must be a concrete component type, not UNSPECIFIED"));
            }
            String codeName = payload.get("codeName") != null ? String.valueOf(payload.get("codeName")) : null;
            return ResponseEntity.ok(componentService.createComponent(docType, artifactKind, name, content, codeName));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid docType/artifactKind: " + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create component: " + e.getMessage()));
        }
    }

    @PutMapping("/{componentId}")
    public ResponseEntity<?> renameTraceComponent(@PathVariable Long componentId, @RequestBody Map<String, String> payload) {
        try {
            String name = payload.get("name");
            String codeName = payload.get("codeName");
            if ((name == null || name.isBlank()) && (codeName == null || codeName.isBlank())) {
                return ResponseEntity.badRequest().body(Map.of("error", "name or codeName is required"));
            }
            TraceComponent updated = null;
            if (name != null && !name.isBlank()) {
                updated = componentService.renameComponent(componentId, name);
            }
            if (codeName != null && !codeName.isBlank()) {
                updated = componentService.updateCodeName(componentId, codeName);
            }
            return ResponseEntity.ok(updated);
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

            List<TraceComponent> savedComponents = componentService.persistExtractedComponents(extractedComponents);

            return ResponseEntity.ok(Map.of(
                "components", savedComponents,
                "count", savedComponents.size()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Extraction failed: " + e.getMessage()));
        }
    }

    private List<TraceComponent> extractComponentsFromEvaluation(
            String evaluationResult, Long historyId, List<String> extractedImages) {
        
        List<TraceComponent> components = new ArrayList<>();
        Map<String, Integer> codeCounters = new HashMap<>();
        
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
            if (line.isBlank() || line.equalsIgnoreCase("None detected.")
                || line.toLowerCase().startsWith("diagram analysis")) {
                continue;
            }

            boolean startsWithImage = line.matches(".*\\[IMG-\\d+\\].*");
            boolean nestedField = line.matches(
                "(?i)^[-*]\\s*(notation observed|correctness|issues|alignment|elements)\\s*:.*");

            if (startsWithImage && !nestedField) {
                if (currentFinding.length() > 0) {
                    components.addAll(createComponentsFromFinding(
                        currentFinding.toString(), historyId, currentImageRef, extractedImages, codeCounters));
                }
                currentImageRef = extractImageRef(line);
                currentFinding = new StringBuilder(line);
            } else {
                if (currentFinding.length() == 0 && (line.startsWith("*") || line.startsWith("-"))) {
                    // Fallback: diagram finding without explicit [IMG-n] prefix
                    currentFinding = new StringBuilder(line);
                    currentImageRef = extractImageRef(line);
                } else if (currentFinding.length() > 0) {
                    currentFinding.append("\n").append(line);
                }
            }
        }

        // Don't forget the last finding
        if (currentFinding.length() > 0) {
            components.addAll(createComponentsFromFinding(
                currentFinding.toString(), historyId, currentImageRef, extractedImages, codeCounters));
        }

        return components;
    }

    private List<TraceComponent> createComponentsFromFinding(
            String findingText,
            Long historyId,
            String imageRef,
            List<String> extractedImages,
            Map<String, Integer> codeCounters) {

        ArtifactKind artifactKind = determineArtifactKindFromFinding(findingText);
        DocType docType = artifactKind.toDocType() != null
            ? artifactKind.toDocType()
            : DocType.SDD;

        String imageData = null;
        if (imageRef != null && imageRef.matches("\\[IMG-(\\d+)\\]")) {
            int imageIndex = Integer.parseInt(imageRef.replaceAll("[^0-9]", "")) - 1;
            if (extractedImages != null && imageIndex >= 0 && imageIndex < extractedImages.size()) {
                imageData = extractedImages.get(imageIndex);
            }
        }

        List<ComponentCodeHelper.CodedElement> elements =
            ComponentCodeHelper.extractElements(findingText, artifactKind);

        List<TraceComponent> created = new ArrayList<>();
        if (!elements.isEmpty()) {
            for (ComponentCodeHelper.CodedElement element : elements) {
                TraceComponent component = new TraceComponent();
                component.setDocType(docType);
                component.setArtifactKind(artifactKind);
                component.setCodeName(element.codeName());
                component.setName(truncateName(element.label()));
                component.setContent(findingText);
                component.setSourceHistoryId(historyId);
                component.setImageData(imageData);
                component.setAiExtracted(true);
                component.setCreatedAt(LocalDateTime.now());
                created.add(component);
            }
            return created;
        }

        String fallbackCode = ComponentCodeHelper.nextDiagramCode(artifactKind, codeCounters);
        String descriptiveName = extractComponentName(findingText);
        TraceComponent component = new TraceComponent();
        component.setDocType(docType);
        component.setArtifactKind(artifactKind);
        component.setCodeName(fallbackCode);
        component.setName(descriptiveName.startsWith(fallbackCode)
            ? descriptiveName
            : truncateName(fallbackCode + " — " + descriptiveName));
        component.setContent(findingText);
        component.setSourceHistoryId(historyId);
        component.setImageData(imageData);
        component.setAiExtracted(true);
        component.setCreatedAt(LocalDateTime.now());
        created.add(component);
        return created;
    }

    private String truncateName(String value) {
        if (value == null || value.isBlank()) return "Extracted Component";
        String cleaned = value.trim();
        if (cleaned.length() > 250) {
            return cleaned.substring(0, 249).trim() + "…";
        }
        return cleaned;
    }

    private ArtifactKind determineArtifactKindFromFinding(String findingText) {
        String lower = findingText.toLowerCase();

        if (lower.contains("source code") || lower.contains("repository") ||
            lower.contains("implementation evidence") || lower.contains("implemented in") ||
            lower.contains("codebase") || lower.contains("method implementation") ||
            lower.contains("class implementation")) {
            return lower.contains("class") || lower.contains("method") || lower.contains("object")
                ? ArtifactKind.IMPL_OO
                : ArtifactKind.IMPL_NON_OO;
        }

        if (lower.contains("use case")) return ArtifactKind.USE_CASE;
        if (lower.contains("activity")) return ArtifactKind.ACTIVITY;
        if (lower.contains("wireframe") || lower.contains("mockup")) return ArtifactKind.WIREFRAME;
        if (lower.contains("context diagram")) return ArtifactKind.CONTEXT_DIAGRAM;
        if (lower.contains("data flow") || lower.contains("dfd")) return ArtifactKind.DATA_FLOW;

        if (lower.contains("class diagram")) return ArtifactKind.CLASS;
        if (lower.contains("sequence")) return ArtifactKind.SEQUENCE;
        if (lower.contains("entity-relationship") || lower.contains("erd") || lower.contains("data model")) {
            return ArtifactKind.DATA_MODEL;
        }
        if (lower.contains("user interface") || lower.contains(" ui ")) return ArtifactKind.UI;

        if (lower.contains("milestone")) return ArtifactKind.MILESTONE;
        if (lower.contains("deliverable")) return ArtifactKind.DELIVERABLE;
        if (lower.contains("task") || lower.contains("work breakdown")) return ArtifactKind.TASK;

        if (lower.contains("test case")) return ArtifactKind.TEST_CASE;
        if (lower.contains("test log")) return ArtifactKind.TEST_LOG;
        if (lower.contains("test design") || lower.contains("test plan")) return ArtifactKind.TEST_DESIGN;

        return ArtifactKind.OTHER_SDD;
    }

    private String extractComponentName(String findingText) {
        String[] lines = findingText.split("\n");
        if (lines.length > 0) {
            String firstLine = lines[0].trim();
            // Remove bullet points and image refs
            String cleaned = firstLine.replaceAll("^[*\\-]\\s*", "")
                           .replaceAll("\\[IMG-\\d+\\]\\s*", "")
                           .trim();
            if (cleaned.length() > 250) {
                cleaned = cleaned.substring(0, 249).trim() + "…";
            }
            return cleaned.isBlank() ? "Extracted Component" : cleaned;
        }
        return "Extracted Component";
    }

    private String extractImageRef(String line) {
        if (line.matches(".*\\[IMG-\\d+\\].*")) {
            return line.replaceAll(".*\\[IMG-(\\d+)\\].*", "[IMG-$1]");
        }
        return null;
    }

    // Section headers in the evaluation output always appear alone on their own line,
    // e.g. "Diagram Analysis:". Matching must anchor to line start — a plain substring
    // search false-positives on ordinary words inside diagram descriptions themselves
    // (e.g. "...wireframe with summary cards..." was mistaken for the "Summary:" header
    // and truncated the diagram list mid-way).
    private int findHeaderLineStart(String text, String headerName, int fromIndex) {
        Pattern pattern = Pattern.compile(
            "(?im)^[ \\t]*" + Pattern.quote(headerName) + "[ \\t]*:");
        Matcher matcher = pattern.matcher(text);
        return matcher.find(fromIndex) ? matcher.start() : -1;
    }

    private int findSectionStart(String text, String sectionName) {
        return findHeaderLineStart(text, sectionName, 0);
    }

    private int findNextSectionStart(String text, int fromIndex) {
        String[] headers = {
            "Diagram Analysis", "Missing Sections", "Weaknesses",
            "Recommendations", "Strengths", "Summary", "Conclusion",
            "Rubric Evaluation", "Revision Analysis"
        };
        int earliest = -1;
        for (String header : headers) {
            int idx = findHeaderLineStart(text, header, fromIndex);
            if (idx != -1 && (earliest == -1 || idx < earliest)) earliest = idx;
        }
        return earliest;
    }
}
