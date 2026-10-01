package com.ieee.evaluator.synctrace.service;

import tools.jackson.databind.ObjectMapper;
import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.ContinuityAnalysisRun;
import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.DiagnosticRecommendation;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.ContinuityAnalysisRunRepository;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.DiagnosticRecommendationRepository;
import com.ieee.evaluator.synctrace.service.TraceabilityClusterService.GoalCluster;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Builds the team's traceability audit report. Every format (JSON, CSV, PDF) is rendered from
 * one {@link AuditReport}, which is assembled from the same sources as the Traceability Results
 * page: goal clusters with their mapped components (the matrix rows), the stored continuity
 * findings and recommendations, the analysis run, and the readiness summary. The export
 * therefore shows exactly what the teacher sees in the app, including the matrix itself.
 */
@Service
@Slf4j
public class AuditExportService {

    static final String NOT_ANALYZED = "Not analyzed";
    static final String PASSED = "Passed";
    static final String FAILED = "Failed";

    private static final Set<String> ACRONYMS = Set.of("UI", "OO", "SRS", "SDD", "SPMP", "STD", "ERD", "DFD");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Map<DocType, String> DOC_TYPE_LABELS = new LinkedHashMap<>();
    static {
        DOC_TYPE_LABELS.put(DocType.SRS, "SRS");
        DOC_TYPE_LABELS.put(DocType.SDD, "SDD");
        DOC_TYPE_LABELS.put(DocType.SPMP, "SPMP");
        DOC_TYPE_LABELS.put(DocType.STD, "STD");
        DOC_TYPE_LABELS.put(DocType.IMPLEMENTATION, "Implementation");
    }

    private final TraceabilityClusterService clusterService;
    private final ContinuityReadinessService readinessService;
    private final ContinuityFindingRepository findingRepository;
    private final DiagnosticRecommendationRepository recommendationRepository;
    private final ContinuityAnalysisRunRepository analysisRunRepository;
    private final ObjectMapper objectMapper;

    public AuditExportService(
            TraceabilityClusterService clusterService,
            ContinuityReadinessService readinessService,
            ContinuityFindingRepository findingRepository,
            DiagnosticRecommendationRepository recommendationRepository,
            ContinuityAnalysisRunRepository analysisRunRepository,
            ObjectMapper objectMapper) {
        this.clusterService = clusterService;
        this.readinessService = readinessService;
        this.findingRepository = findingRepository;
        this.recommendationRepository = recommendationRepository;
        this.analysisRunRepository = analysisRunRepository;
        this.objectMapper = objectMapper;
    }

    public byte[] exportAuditReport(String teamCode, String format) throws Exception {
        if (teamCode == null || teamCode.isBlank()) {
            throw new IllegalArgumentException("teamCode is required");
        }
        String normalizedFormat = format == null ? "json" : format.toLowerCase(Locale.ROOT);
        if (!List.of("json", "csv", "pdf").contains(normalizedFormat)) {
            throw new IllegalArgumentException("Unsupported format: " + format);
        }
        AuditReport report = buildReport(teamCode.trim());
        return switch (normalizedFormat) {
            case "csv" -> exportCsv(report);
            case "pdf" -> exportPdf(report);
            default -> exportJson(report);
        };
    }

    // ── Report model ──────────────────────────────────────────────────────────

    record ComponentRef(Long id, String code, String name, String docType, String artifactKind) {
        String label() {
            return code != null && !code.isBlank() ? code : (name == null || name.isBlank() ? "Component" : name);
        }
    }

    record MatrixRow(
            String code, Long goalId, String goal, List<String> specificObjectives,
            Map<String, List<ComponentRef>> cells, String status, List<String> issues) {}

    record FindingRow(
            String goalCode, String severity, String from, String to, String description,
            String rootCause, String recommendation, String priority) {}

    record MappedComponent(ComponentRef component, List<String> goalCodes) {}

    record AuditReport(
            String teamCode,
            LocalDateTime generatedAt,
            LocalDateTime lastAnalyzedAt,
            Map<String, Object> readiness,
            Map<String, Integer> coverage,
            List<MatrixRow> matrix,
            List<FindingRow> findings,
            List<MappedComponent> components) {}

    AuditReport buildReport(String teamCode) {
        List<GoalCluster> clusters = clusterService.clustersForTeam(teamCode);
        List<ContinuityFinding> findings = findingRepository.findByTeamCodeIgnoreCaseOrderByDetectedAtDescIdAsc(teamCode);
        LocalDateTime lastAnalyzedAt = analysisRunRepository.findByTeamCodeIgnoreCase(teamCode)
            .map(ContinuityAnalysisRun::getLastAnalyzedAt)
            .orElse(null);

        Map<Long, List<DiagnosticRecommendation>> recommendationsByFinding = new HashMap<>();
        List<Long> findingIds = findings.stream().map(ContinuityFinding::getId).filter(Objects::nonNull).toList();
        if (!findingIds.isEmpty()) {
            for (DiagnosticRecommendation rec : recommendationRepository.findByFindingIdIn(findingIds)) {
                recommendationsByFinding.computeIfAbsent(rec.getFindingId(), ignored -> new ArrayList<>()).add(rec);
            }
        }

        // Matrix rows, numbered G1, G2, … exactly like the Results page.
        Map<Long, String> goalCodeByMemberId = new HashMap<>();
        List<MatrixRow> matrix = new ArrayList<>();
        Map<Long, MappedComponent> mappedComponents = new LinkedHashMap<>();
        int fullyCovered = 0, partial = 0, unmapped = 0, missingCells = 0;
        for (int i = 0; i < clusters.size(); i++) {
            GoalCluster cluster = clusters.get(i);
            String code = "G" + (i + 1);
            Set<Long> members = new HashSet<>(cluster.memberGoalIds());
            members.forEach(id -> goalCodeByMemberId.put(id, code));

            Map<String, List<ComponentRef>> cells = new LinkedHashMap<>();
            int coveredTypes = 0;
            for (Map.Entry<DocType, String> docType : DOC_TYPE_LABELS.entrySet()) {
                List<ComponentRef> refs = cluster.components(docType.getKey()).stream().map(this::toRef).toList();
                cells.put(docType.getValue(), refs);
                if (refs.isEmpty()) missingCells++; else coveredTypes++;
                for (ComponentRef ref : refs) {
                    MappedComponent entry = mappedComponents.computeIfAbsent(ref.id(), ignored -> new MappedComponent(ref, new ArrayList<>()));
                    if (!entry.goalCodes().contains(code)) entry.goalCodes().add(code);
                }
            }
            if (coveredTypes == 0) unmapped++;
            else if (coveredTypes < DOC_TYPE_LABELS.size()) partial++;
            else fullyCovered++;

            List<String> issues = findings.stream()
                .filter(f -> f.getGoalId() != null && members.contains(f.getGoalId()))
                .map(ContinuityFinding::getDescription)
                .toList();
            String status = lastAnalyzedAt == null ? NOT_ANALYZED : (issues.isEmpty() ? PASSED : FAILED);

            matrix.add(new MatrixRow(
                code, cluster.id(), cluster.primary().getDescription(),
                cluster.children().stream().map(SmartGoal::getDescription).toList(),
                cells, status, issues));
        }

        List<FindingRow> findingRows = new ArrayList<>();
        for (ContinuityFinding finding : findings) {
            List<DiagnosticRecommendation> recs = recommendationsByFinding.getOrDefault(finding.getId(), List.of());
            DiagnosticRecommendation rec = recs.isEmpty() ? null : recs.get(0);
            findingRows.add(new FindingRow(
                finding.getGoalId() == null ? "Source code" : goalCodeByMemberId.getOrDefault(finding.getGoalId(), "—"),
                finding.getSeverity() == null ? "" : finding.getSeverity().name(),
                label(finding.getDocTypeFrom()),
                label(finding.getDocTypeTo()),
                finding.getDescription(),
                rec == null ? null : rec.getRootCause(),
                rec == null ? null : rec.getRecommendation(),
                rec == null ? null : rec.getPriority()));
        }
        // Goal order first (G1, G2, …), source-code findings last, then severity.
        findingRows.sort(Comparator
            .comparingInt((FindingRow f) -> goalSortKey(f.goalCode()))
            .thenComparingInt(f -> severityRank(f.severity())));

        Map<String, Integer> coverage = new LinkedHashMap<>();
        coverage.put("goals", clusters.size());
        coverage.put("fullyCoveredGoals", fullyCovered);
        coverage.put("partialGoals", partial);
        coverage.put("unmappedGoals", unmapped);
        coverage.put("missingCells", missingCells);
        coverage.put("mappedComponents", mappedComponents.size());
        coverage.put("alignmentPercent", clusters.isEmpty()
            ? 0
            : (int) Math.round((1 - (double) missingCells / (clusters.size() * DOC_TYPE_LABELS.size())) * 100));

        Map<String, Object> readiness;
        try {
            readiness = readinessService.getTeamReadinessSummary(teamCode);
        } catch (Exception e) {
            log.warn("Readiness summary unavailable for team {}: {}", teamCode, e.getMessage());
            readiness = Map.of();
        }

        return new AuditReport(teamCode, LocalDateTime.now(), lastAnalyzedAt, readiness, coverage,
            matrix, findingRows, new ArrayList<>(mappedComponents.values()));
    }

    private ComponentRef toRef(TraceComponent c) {
        String code = ComponentCodeHelper.resolveDisplayCode(c.getCodeName(), c.getName(), c.getContent());
        ArtifactKind kind = c.getArtifactKind() == null ? ArtifactKind.UNSPECIFIED : c.getArtifactKind();
        return new ComponentRef(c.getId(), code, c.getName(), label(c.getDocType()), kind.name());
    }

    private static String label(DocType docType) {
        if (docType == null) return "";
        return DOC_TYPE_LABELS.getOrDefault(docType, docType == DocType.PROPOSAL ? "Proposal" : docType.name());
    }

    private static int goalSortKey(String goalCode) {
        if (goalCode != null && goalCode.matches("G\\d+")) return Integer.parseInt(goalCode.substring(1));
        return Integer.MAX_VALUE;
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "" : severity) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }

    private static String cellText(List<ComponentRef> refs) {
        if (refs == null || refs.isEmpty()) return "Missing";
        return String.join(", ", refs.stream().map(ComponentRef::label).toList());
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? NOT_ANALYZED : time.format(DISPLAY_TIME);
    }

    /** AT_RISK → "At Risk", USE_CASE → "Use Case" for the human-readable formats. */
    static String humanize(Object value) {
        if (value == null) return "—";
        String raw = value.toString();
        if (!raw.matches("[A-Z0-9_]+")) return raw;
        StringBuilder out = new StringBuilder();
        for (String word : raw.split("_")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(ACRONYMS.contains(word) ? word : word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static Object readinessValue(AuditReport report, String key) {
        Object value = report.readiness().get(key);
        return value == null ? "—" : value;
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    private byte[] exportJson(AuditReport report) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("teamCode", report.teamCode());
        root.put("generatedAt", report.generatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.put("lastAnalyzedAt", report.lastAnalyzedAt() == null
            ? null : report.lastAnalyzedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("readinessStatus", report.readiness().get("status"));
        summary.put("readinessScore", report.readiness().get("readinessScore"));
        summary.put("averageCoveragePercent", report.readiness().get("averageCoveragePercent"));
        summary.putAll(report.coverage());
        summary.put("totalFindings", report.findings().size());
        summary.put("severityCounts", report.readiness().get("severityCounts"));
        root.put("summary", summary);

        List<Map<String, Object>> matrix = new ArrayList<>();
        for (MatrixRow row : report.matrix()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", row.code());
            item.put("goal", row.goal());
            item.put("specificObjectives", row.specificObjectives());
            Map<String, Object> cells = new LinkedHashMap<>();
            row.cells().forEach((docType, refs) -> cells.put(docType, refs.stream().map(ref -> {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("code", ref.code());
                c.put("name", ref.name());
                c.put("artifactKind", ref.artifactKind());
                return c;
            }).toList()));
            item.put("cells", cells);
            item.put("status", row.status());
            item.put("issues", row.issues());
            matrix.add(item);
        }
        root.put("traceabilityMatrix", matrix);

        root.put("findings", report.findings().stream().map(f -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("goal", f.goalCode());
            item.put("severity", f.severity());
            item.put("from", f.from());
            item.put("to", f.to());
            item.put("description", f.description());
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("rootCause", f.rootCause());
            rec.put("recommendation", f.recommendation());
            rec.put("priority", f.priority());
            item.put("recommendation", f.recommendation() == null ? null : rec);
            return item;
        }).toList());

        root.put("mappedComponents", report.components().stream().map(m -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", m.component().code());
            item.put("name", m.component().name());
            item.put("docType", m.component().docType());
            item.put("artifactKind", m.component().artifactKind());
            item.put("goals", m.goalCodes());
            return item;
        }).toList());

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    // ── CSV ───────────────────────────────────────────────────────────────────

    private byte[] exportCsv(AuditReport report) {
        StringBuilder csv = new StringBuilder();
        csv.append("Traceability Audit Report\n");
        appendCsvRow(csv, "Team", report.teamCode());
        appendCsvRow(csv, "Generated", report.generatedAt().format(DISPLAY_TIME));
        appendCsvRow(csv, "Last analyzed", formatTime(report.lastAnalyzedAt()));
        csv.append("\n");

        csv.append("Readiness Summary\n");
        appendCsvRow(csv, "Readiness status", humanize(readinessValue(report, "status")));
        appendCsvRow(csv, "Readiness score (%)", readinessValue(report, "readinessScore"));
        appendCsvRow(csv, "Alignment (%)", report.coverage().get("alignmentPercent"));
        appendCsvRow(csv, "SMART goals", report.coverage().get("goals"));
        appendCsvRow(csv, "Fully covered goals", report.coverage().get("fullyCoveredGoals"));
        appendCsvRow(csv, "Partially covered goals", report.coverage().get("partialGoals"));
        appendCsvRow(csv, "Unmapped goals", report.coverage().get("unmappedGoals"));
        appendCsvRow(csv, "Missing cells", report.coverage().get("missingCells"));
        appendCsvRow(csv, "Mapped components", report.coverage().get("mappedComponents"));
        appendCsvRow(csv, "Findings", report.findings().size());
        csv.append("\n");

        csv.append("Traceability Matrix\n");
        List<Object> header = new ArrayList<>(List.of("Goal", "SMART Goal", "Specific Objectives"));
        header.addAll(DOC_TYPE_LABELS.values());
        header.addAll(List.of("Status", "Issues"));
        appendCsvRow(csv, header.toArray());
        for (MatrixRow row : report.matrix()) {
            List<Object> values = new ArrayList<>(List.of(row.code(), row.goal(), String.join("; ", row.specificObjectives())));
            DOC_TYPE_LABELS.values().forEach(docType -> values.add(cellText(row.cells().get(docType))));
            values.add(row.status());
            values.add(String.join("; ", row.issues()));
            appendCsvRow(csv, values.toArray());
        }
        csv.append("\n");

        csv.append("Continuity Findings and Recommendations\n");
        appendCsvRow(csv, "Goal", "Severity", "From", "To", "Finding", "Root Cause", "Recommendation", "Priority");
        for (FindingRow f : report.findings()) {
            appendCsvRow(csv, f.goalCode(), f.severity(), f.from(), f.to(), f.description(),
                f.rootCause(), f.recommendation(), f.priority());
        }
        csv.append("\n");

        csv.append("Mapped Components\n");
        appendCsvRow(csv, "Code", "Name", "Document", "Artifact Kind", "Goals");
        for (MappedComponent m : report.components()) {
            appendCsvRow(csv, m.component().code(), m.component().name(), m.component().docType(),
                humanize(m.component().artifactKind()), String.join(", ", m.goalCodes()));
        }

        // UTF-8 byte order mark so Excel shows non-ASCII characters (names, dashes) correctly.
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    private void appendCsvRow(StringBuilder csv, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) csv.append(",");
            csv.append(escapeCsv(values[i] == null ? "" : values[i].toString()));
        }
        csv.append("\n");
    }

    private String escapeCsv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ── PDF ───────────────────────────────────────────────────────────────────

    private byte[] exportPdf(AuditReport report) throws Exception {
        Document document = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, baos);
        document.open();

        Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD);
        Font headerFont = new Font(Font.HELVETICA, 13, Font.BOLD);
        Font textFont = new Font(Font.HELVETICA, 9);
        Font boldFont = new Font(Font.HELVETICA, 9, Font.BOLD);
        Font cellFont = new Font(Font.HELVETICA, 8);
        Font missingFont = new Font(Font.HELVETICA, 8, Font.ITALIC, new Color(0xB4, 0x23, 0x18));

        document.add(new Paragraph("Traceability Audit Report", titleFont));
        document.add(new Paragraph("Team: " + report.teamCode(), textFont));
        document.add(new Paragraph("Generated: " + report.generatedAt().format(DISPLAY_TIME)
            + "    Last analyzed: " + formatTime(report.lastAnalyzedAt()), textFont));
        document.add(spacer());

        // Summary
        document.add(new Paragraph("Readiness Summary", headerFont));
        PdfPTable summary = new PdfPTable(4);
        summary.setWidthPercentage(70);
        summary.setHorizontalAlignment(Element.ALIGN_LEFT);
        summary.setSpacingBefore(4);
        addSummaryCell(summary, "Readiness status", humanize(readinessValue(report, "status")), boldFont, textFont);
        addSummaryCell(summary, "Readiness score", readinessValue(report, "readinessScore") + "%", boldFont, textFont);
        addSummaryCell(summary, "Alignment", report.coverage().get("alignmentPercent") + "%", boldFont, textFont);
        addSummaryCell(summary, "SMART goals", report.coverage().get("goals"), boldFont, textFont);
        addSummaryCell(summary, "Fully covered goals", report.coverage().get("fullyCoveredGoals"), boldFont, textFont);
        addSummaryCell(summary, "Partially covered goals", report.coverage().get("partialGoals"), boldFont, textFont);
        addSummaryCell(summary, "Unmapped goals", report.coverage().get("unmappedGoals"), boldFont, textFont);
        addSummaryCell(summary, "Missing cells", report.coverage().get("missingCells"), boldFont, textFont);
        addSummaryCell(summary, "Mapped components", report.coverage().get("mappedComponents"), boldFont, textFont);
        addSummaryCell(summary, "Findings", report.findings().size(), boldFont, textFont);
        summary.completeRow();
        document.add(summary);
        document.add(spacer());

        // Matrix
        document.add(new Paragraph("Traceability Matrix", headerFont));
        if (report.matrix().isEmpty()) {
            document.add(new Paragraph("No SMART goals for this team yet.", textFont));
        } else {
            PdfPTable matrix = new PdfPTable(2 + DOC_TYPE_LABELS.size() + 1);
            matrix.setWidthPercentage(100);
            matrix.setSpacingBefore(4);
            matrix.setHeaderRows(1);
            matrix.setWidths(new float[] {0.6f, 4.2f, 1.6f, 1.6f, 1.6f, 1.6f, 1.8f, 1.1f});
            addHeaderCell(matrix, "Goal", boldFont);
            addHeaderCell(matrix, "SMART Goal", boldFont);
            DOC_TYPE_LABELS.values().forEach(label -> addHeaderCell(matrix, label, boldFont));
            addHeaderCell(matrix, "Status", boldFont);
            for (MatrixRow row : report.matrix()) {
                matrix.addCell(cell(new Phrase(row.code(), boldFont)));
                Phrase goal = new Phrase(row.goal(), cellFont);
                if (!row.specificObjectives().isEmpty()) {
                    goal.add(Chunk.NEWLINE);
                    for (String objective : row.specificObjectives()) {
                        goal.add(new Chunk("- " + objective + "\n", cellFont));
                    }
                }
                matrix.addCell(cell(goal));
                for (String docType : DOC_TYPE_LABELS.values()) {
                    List<ComponentRef> refs = row.cells().get(docType);
                    matrix.addCell(cell(new Phrase(cellText(refs), refs == null || refs.isEmpty() ? missingFont : cellFont)));
                }
                Font statusFont = new Font(Font.HELVETICA, 9, Font.BOLD,
                    FAILED.equals(row.status()) ? new Color(0xB4, 0x23, 0x18)
                        : PASSED.equals(row.status()) ? new Color(0x1E, 0x7B, 0x34) : Color.GRAY);
                matrix.addCell(cell(new Phrase(row.status(), statusFont)));
            }
            document.add(matrix);
        }
        document.add(spacer());

        // Findings with their recommendations
        document.add(new Paragraph("Continuity Findings and Recommendations", headerFont));
        if (report.findings().isEmpty()) {
            document.add(new Paragraph(report.lastAnalyzedAt() == null
                ? "AI Analysis has not been run for this team yet."
                : "No continuity gaps were found in the last analysis.", textFont));
        } else {
            PdfPTable findings = new PdfPTable(new float[] {0.8f, 0.9f, 1.3f, 4f, 4f});
            findings.setWidthPercentage(100);
            findings.setSpacingBefore(4);
            findings.setHeaderRows(1);
            addHeaderCell(findings, "Goal", boldFont);
            addHeaderCell(findings, "Severity", boldFont);
            addHeaderCell(findings, "Stage", boldFont);
            addHeaderCell(findings, "Finding", boldFont);
            addHeaderCell(findings, "Recommendation", boldFont);
            for (FindingRow f : report.findings()) {
                findings.addCell(cell(new Phrase(f.goalCode(), cellFont)));
                findings.addCell(cell(new Phrase(f.severity(), cellFont)));
                findings.addCell(cell(new Phrase(f.from() + " -> " + f.to(), cellFont)));
                findings.addCell(cell(new Phrase(f.description(), cellFont)));
                String rec = f.recommendation() == null ? "—"
                    : (f.rootCause() == null || f.rootCause().isBlank() ? "" : f.rootCause() + "\n") + f.recommendation();
                findings.addCell(cell(new Phrase(rec, cellFont)));
            }
            document.add(findings);
        }
        document.add(spacer());

        // Mapped components
        document.add(new Paragraph("Mapped Components", headerFont));
        if (report.components().isEmpty()) {
            document.add(new Paragraph("No components are mapped to this team's goals yet.", textFont));
        } else {
            PdfPTable components = new PdfPTable(new float[] {1f, 5f, 1.3f, 1.6f, 1.1f});
            components.setWidthPercentage(100);
            components.setSpacingBefore(4);
            components.setHeaderRows(1);
            addHeaderCell(components, "Code", boldFont);
            addHeaderCell(components, "Name", boldFont);
            addHeaderCell(components, "Document", boldFont);
            addHeaderCell(components, "Artifact Kind", boldFont);
            addHeaderCell(components, "Goals", boldFont);
            for (MappedComponent m : report.components()) {
                components.addCell(cell(new Phrase(m.component().label(), cellFont)));
                components.addCell(cell(new Phrase(Objects.toString(m.component().name(), ""), cellFont)));
                components.addCell(cell(new Phrase(m.component().docType(), cellFont)));
                components.addCell(cell(new Phrase(humanize(m.component().artifactKind()), cellFont)));
                components.addCell(cell(new Phrase(String.join(", ", m.goalCodes()), cellFont)));
            }
            document.add(components);
        }

        document.close();
        return baos.toByteArray();
    }

    private static PdfPCell cell(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setPadding(4);
        return cell;
    }

    private static Paragraph spacer() {
        return new Paragraph(" ");
    }

    private static void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell header = cell(new Phrase(text, font));
        header.setBackgroundColor(new Color(0xE8, 0xEE, 0xF6));
        table.addCell(header);
    }

    private static void addSummaryCell(PdfPTable table, String label, Object value, Font labelFont, Font valueFont) {
        PdfPCell labelCell = cell(new Phrase(label, labelFont));
        labelCell.setBackgroundColor(new Color(0xF4, 0xF6, 0xF9));
        table.addCell(labelCell);
        table.addCell(cell(new Phrase(String.valueOf(value), valueFont)));
    }
}
