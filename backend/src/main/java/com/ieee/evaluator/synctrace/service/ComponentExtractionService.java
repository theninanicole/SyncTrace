package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.service.DocumentType;
import com.ieee.evaluator.service.DocumentTypeDetectorService;
import com.ieee.evaluator.synctrace.model.Component;
import com.ieee.evaluator.synctrace.model.DocType;
import com.ieee.evaluator.synctrace.repository.ComponentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Populates the SyncTrace component library from the "Diagram Analysis" section
 * of a submission's already-stored evaluation report. The evaluation pipeline
 * already identifies and critiques every diagram in the document (see
 * PromptSharedRulesService's STEP 2 / output format), so this reuses that
 * output instead of re-analyzing the document with another AI call.
 */
@Service
@Slf4j
public class ComponentExtractionService {

    // Matches the "Diagram Analysis" heading line, optionally markdown-styled.
    private static final Pattern DIAGRAM_SECTION_HEADING =
        Pattern.compile("(?im)^[ \\t]*(?:#{1,3}[ \\t]*)?\\*{0,2}diagram analysis\\*{0,2}:?[ \\t]*$");

    // Matches the next top-level report heading, marking the end of the diagram section.
    private static final Pattern NEXT_SECTION_HEADING = Pattern.compile(
        "(?im)^[ \\t]*(?:#{1,3}[ \\t]*)?\\*{0,2}(?:missing sections|weaknesses|recommendations|strengths|" +
        "summary|conclusion|rubric evaluation|revision analysis)\\*{0,2}:?[ \\t]*$");

    // Matches a single diagram entry line, e.g. "* [IMG-3] - Class Diagram: <summary>".
    private static final Pattern DIAGRAM_ENTRY =
        Pattern.compile("(?i)^\\*?\\s*\\[IMG-(\\d+)]\\s*-\\s*(.+?):(.*)$");

    private static final Pattern DOC_TYPE_TOKEN =
        Pattern.compile("(?im)^[ \\t]*(?:type override|document type)\\s*:\\s*([A-Za-z]+)");

    private final EvaluationHistoryRepository historyRepository;
    private final DocumentTypeDetectorService typeDetector;
    private final ComponentRepository componentRepository;

    public ComponentExtractionService(
            EvaluationHistoryRepository historyRepository,
            DocumentTypeDetectorService typeDetector,
            ComponentRepository componentRepository) {
        this.historyRepository   = historyRepository;
        this.typeDetector        = typeDetector;
        this.componentRepository = componentRepository;
    }

    @Transactional
    public List<Component> extractFromHistory(Long historyId) {
        EvaluationHistory history = historyRepository.findById(historyId)
            .orElseThrow(() -> new IllegalArgumentException("Evaluation record not found: " + historyId));

        String report = history.getEvaluationResult();
        if (report == null || report.isBlank()) {
            throw new IllegalStateException("This submission has no stored evaluation report to extract from.");
        }

        DocType docType = detectDocType(history.getFileName(), report);
        if (docType == null) {
            throw new IllegalStateException(
                "This submission does not appear to be an SRS, SDD, SPMP, or STD document.");
        }

        List<String> images = history.getExtractedImages();
        List<Component> result = new ArrayList<>();
        Map<String, Integer> namesSeenThisRun = new HashMap<>();

        for (DiagramEntry entry : parseDiagramEntries(report)) {
            String baseName = entry.type.trim();
            if (baseName.isEmpty()) continue;

            // Disambiguate repeated diagram types within the same document
            // (e.g. two "Class Diagram" entries) so they don't collapse into one component.
            int occurrence = namesSeenThisRun.merge(baseName.toLowerCase(), 1, Integer::sum);
            String name = occurrence == 1 ? baseName : baseName + " (" + occurrence + ")";

            String imageData = (images != null && entry.imageIndex >= 0 && entry.imageIndex < images.size())
                ? images.get(entry.imageIndex)
                : null;

            Component component = componentRepository
                .findByDocTypeAndNameIgnoreCase(docType, name)
                .orElseGet(() -> {
                    Component c = new Component();
                    c.setDocType(docType);
                    c.setName(name);
                    c.setAiExtracted(true);
                    c.setSourceHistoryId(historyId);
                    c.setContent(entry.description());
                    c.setImageData(imageData);
                    c.setCreatedAt(LocalDateTime.now());
                    return componentRepository.save(c);
                });

            // Backfill the diagram image for components that were extracted before
            // image linking existed, without disturbing any manual edits since.
            if (component.getImageData() == null && imageData != null) {
                component.setImageData(imageData);
                component = componentRepository.save(component);
            }

            result.add(component);
        }

        return result;
    }

    private DocType detectDocType(String fileName, String report) {
        DocType mapped = toDocType(typeDetector.detect(fileName, report));
        if (mapped != null) return mapped;

        // The evaluation report itself states its detected type near the top
        // (e.g. "Document Type: SRS" or "Type Override: SDD") — fall back to that
        // since the report text won't contain the document's own cover-page title.
        Matcher matcher = DOC_TYPE_TOKEN.matcher(report);
        while (matcher.find()) {
            try {
                return DocType.valueOf(matcher.group(1).trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // keep scanning — token wasn't a recognized doc type
            }
        }
        return null;
    }

    private DocType toDocType(DocumentType type) {
        if (type == null) return null;
        return switch (type) {
            case SRS  -> DocType.SRS;
            case SDD  -> DocType.SDD;
            case SPMP -> DocType.SPMP;
            case STD  -> DocType.STD;
            default   -> null;
        };
    }

    private List<DiagramEntry> parseDiagramEntries(String report) {
        List<DiagramEntry> diagrams = new ArrayList<>();

        Matcher sectionStart = DIAGRAM_SECTION_HEADING.matcher(report);
        if (!sectionStart.find()) return diagrams;

        int bodyStart = sectionStart.end();
        Matcher sectionEnd = NEXT_SECTION_HEADING.matcher(report);
        int bodyEnd = sectionEnd.find(bodyStart) ? sectionEnd.start() : report.length();
        String body = report.substring(bodyStart, bodyEnd);

        DiagramEntry current = null;
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            Matcher header = DIAGRAM_ENTRY.matcher(trimmed);
            if (header.matches()) {
                if (current != null) diagrams.add(current);
                current = new DiagramEntry(
                    Integer.parseInt(header.group(1)) - 1,
                    header.group(2).trim(),
                    header.group(3).trim()
                );
            } else if (current != null) {
                current.details.add(trimmed.replaceFirst("^[-*]\\s*", ""));
            }
        }
        if (current != null) diagrams.add(current);

        return diagrams;
    }

    private static final class DiagramEntry {
        final int imageIndex;
        final String type;
        final String summary;
        final List<String> details = new ArrayList<>();

        DiagramEntry(int imageIndex, String type, String summary) {
            this.imageIndex = imageIndex;
            this.type       = type;
            this.summary    = summary;
        }

        String description() {
            StringBuilder sb = new StringBuilder();
            if (!summary.isBlank()) sb.append(summary).append("\n");
            for (String d : details) sb.append(d).append("\n");
            return sb.toString().trim();
        }
    }
}
