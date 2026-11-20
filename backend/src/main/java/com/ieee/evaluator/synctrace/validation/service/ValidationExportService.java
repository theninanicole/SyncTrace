package com.ieee.evaluator.synctrace.validation.service;

import com.ieee.evaluator.synctrace.model.MappingSuggestion;
import com.ieee.evaluator.synctrace.validation.model.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One-click export of every validation record. Each table is built once and rendered either
 * as CSV files in a ZIP (summary.csv + one raw CSV per table) or as one Excel workbook
 * (Summary sheet + one sheet per metric). Rows carry team codes and user ids only, never
 * names or emails.
 */
@Service
public class ValidationExportService {

    public record Table(String name, List<String> headers, List<List<Object>> rows) {}

    private static final List<String> TIMED_SUMMARY_METRICS = List.of("M1", "M2", "M3", "M4", "M6", "M8");

    private static final List<String> EVENT_HEADERS = List.of("id", "team_id", "user_id", "metric_code",
        "operation", "repository_id", "export_format", "analysis_run_id", "started_at", "ended_at",
        "duration_ms", "status", "error_message", "item_count", "created_at");
    private static final List<String> SESSION_HEADERS = List.of("id", "team_id", "stage", "started_at",
        "finalized_at", "wall_clock_ms", "active_ms", "finalized_by", "links_saved",
        "unmapped_sources_at_finalize");
    private static final List<String> SUGGESTION_HEADERS = List.of("id", "team_id", "stage", "source_id",
        "target_id", "score", "reason", "run_id", "generated_at", "decision", "decided_at", "decided_by",
        "decision_method", "resulting_link_id", "resulting_link_count", "withdrawn_at", "withdrawn_reason",
        "system_rejected");
    private static final List<String> COVERAGE_HEADERS = List.of("id", "analysis_run_id", "team_id",
        "gaps_detected", "gaps_provisional", "gaps_with_explanation", "gaps_with_recommendation",
        "gaps_with_both", "generation_failures", "recommendation_status", "created_at");

    private final ValidationSummaryService summaryService;
    private final ZoneId serverZone;

    @Autowired
    public ValidationExportService(ValidationSummaryService summaryService) {
        this(summaryService, ZoneId.systemDefault());
    }

    ValidationExportService(ValidationSummaryService summaryService, ZoneId serverZone) {
        this.summaryService = summaryService;
        this.serverZone = serverZone;
    }

    public byte[] exportZip(ValidationFilter filter) throws IOException {
        return toZip(tablesFor(filter));
    }

    public byte[] exportExcel(ValidationFilter filter) throws IOException {
        return toExcel(sheetsFor(filter));
    }

    // ── Tables ──────────────────────────────────────────────────────────────

    /** summary.csv plus the four raw tables, named as they appear in the ZIP. */
    public List<Table> tablesFor(ValidationFilter filter) {
        ValidationDataset data = summaryService.load(filter);
        List<TeamValidationSummary> summaries = summaryService.summarize(filter, data);
        return List.of(
            summaryTable("summary.csv", summaries),
            eventsTable("validation_events.csv", data.events(), e -> true),
            sessionsTable("stage_sessions.csv", data.stageSessions()),
            suggestionsTable("ai_suggestions.csv", data.suggestions()),
            coverageTable("analysis_coverage.csv", data.coverage()));
    }

    /** Summary sheet plus one sheet per metric (M1 … M8). */
    public List<Table> sheetsFor(ValidationFilter filter) {
        ValidationDataset data = summaryService.load(filter);
        List<TeamValidationSummary> summaries = summaryService.summarize(filter, data);
        List<Table> sheets = new ArrayList<>();
        sheets.add(summaryTable("Summary", summaries));
        for (MetricCode metric : MetricCode.values()) {
            String name = metric.name() + " " + metric.label();
            sheets.add(switch (metric) {
                case M1, M2, M3, M6, M8 -> eventsTable(name, data.events(),
                    e -> metric.name().equals(e.getMetricCode()));
                case M4 -> sessionsTable(name, data.stageSessions());
                case M5 -> suggestionsTable(name, data.suggestions());
                case M7 -> coverageTable(name, data.coverage());
            });
        }
        return sheets;
    }

    Table summaryTable(String name, List<TeamValidationSummary> summaries) {
        List<String> headers = new ArrayList<>(List.of("team_id"));
        for (String metric : TIMED_SUMMARY_METRICS) {
            String m = metric.toLowerCase(Locale.ROOT);
            headers.addAll(List.of(m + "_count", m + "_median_s", m + "_max_s", m + "_pct_within_target", m + "_met"));
        }
        headers.addAll(List.of("m5_suggestions", "m5_accepted", "m5_edited", "m5_rejected", "m5_pending",
            "m5_acceptance_rate", "m5_met", "m7_runs", "m7_gaps_counted", "m7_gaps_with_both",
            "m7_coverage_pct", "m7_met"));

        List<List<Object>> rows = new ArrayList<>();
        for (TeamValidationSummary team : summaries) {
            List<Object> row = new ArrayList<>();
            row.add(team.teamId());
            for (String metric : TIMED_SUMMARY_METRICS) {
                MetricSummary m = team.metric(metric);
                row.add(m.getCount());
                row.add(seconds(m.getMedianMs()));
                row.add(seconds(m.getMaxMs()));
                row.add(m.getPctWithinTarget());
                row.add(m.getTargetMet());
            }
            MetricSummary m5 = team.metric("M5");
            row.addAll(Arrays.asList(m5.getCount(), m5.getAccepted(), m5.getEdited(), m5.getRejected(),
                m5.getPending(), m5.getAcceptanceRate(), m5.getTargetMet()));
            MetricSummary m7 = team.metric("M7");
            row.addAll(Arrays.asList(m7.getCount(), m7.getGapsCounted(), m7.getGapsWithBoth(),
                m7.getCoveragePct(), m7.getTargetMet()));
            rows.add(row);
        }
        return new Table(name, headers, rows);
    }

    Table eventsTable(String name, List<ValidationEvent> events, Predicate<ValidationEvent> include) {
        List<List<Object>> rows = events.stream().filter(include).map(e -> Arrays.<Object>asList(
            e.getId(), e.getTeamId(), e.getUserId(), e.getMetricCode(), e.getOperation(), e.getRepositoryId(),
            e.getExportFormat(), e.getAnalysisRunId(), e.getStartedAt(), e.getEndedAt(), e.getDurationMs(),
            e.getStatus(), e.getErrorMessage(), e.getItemCount(), e.getCreatedAt())).toList();
        return new Table(name, EVENT_HEADERS, rows);
    }

    Table sessionsTable(String name, List<StageSession> sessions) {
        List<List<Object>> rows = sessions.stream().map(s -> Arrays.<Object>asList(
            s.getId(), s.getTeamId(), s.getStage(), s.getStartedAt(), s.getFinalizedAt(), s.getWallClockMs(),
            s.getActiveMs(), s.getFinalizedBy(), s.getLinksSaved(), s.getUnmappedSourcesAtFinalize())).toList();
        return new Table(name, SESSION_HEADERS, rows);
    }

    Table suggestionsTable(String name, List<MappingSuggestion> suggestions) {
        // decided_by is the user id; the legacy decided_by column (an email) is never exported.
        List<List<Object>> rows = suggestions.stream().map(s -> Arrays.<Object>asList(
            s.getId(), s.getTeamCode(), StageKeys.toCode(s.getStage()), s.getSourceId(), s.getTargetComponentId(),
            s.getScore(), s.getReason(), s.getRunId(), instant(s.getCreatedAt()),
            s.getStatus() == null ? null : s.getStatus().name().toLowerCase(Locale.ROOT),
            instant(s.getDecidedAt()), s.getDecidedByUserId(), s.getDecisionMethod(), s.getResultingLinkId(),
            s.getResultingLinkCount(), instant(s.getWithdrawnAt()), s.getWithdrawnReason(),
            s.isSystemRejected())).toList();
        return new Table(name, SUGGESTION_HEADERS, rows);
    }

    Table coverageTable(String name, List<AnalysisCoverage> coverage) {
        List<List<Object>> rows = coverage.stream().map(c -> Arrays.<Object>asList(
            c.getId(), c.getAnalysisRunId(), c.getTeamId(), c.getGapsDetected(), c.getGapsProvisional(),
            c.getGapsWithExplanation(), c.getGapsWithRecommendation(), c.getGapsWithBoth(),
            c.getGenerationFailures(), c.getRecommendationStatus(), c.getCreatedAt())).toList();
        return new Table(name, COVERAGE_HEADERS, rows);
    }

    private Instant instant(LocalDateTime local) {
        return local == null ? null : local.atZone(serverZone).toInstant();
    }

    /** Seconds with two decimals (23 ms → 0.02), so short runs never read as zero. */
    private static Double seconds(Long ms) {
        return ms == null ? null : Math.round(ms / 10.0) / 100.0;
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    static byte[] toZip(List<Table> tables) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Table table : tables) {
                zip.putNextEntry(new ZipEntry(table.name()));
                zip.write(toCsv(table).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** CSV with a UTF-8 BOM so Excel opens it with the right encoding (same as the audit export). */
    static String toCsv(Table table) {
        StringBuilder csv = new StringBuilder("﻿");
        appendCsvRow(csv, new ArrayList<>(table.headers()));
        table.rows().forEach(row -> appendCsvRow(csv, row));
        return csv.toString();
    }

    private static void appendCsvRow(StringBuilder csv, List<?> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) csv.append(',');
            csv.append(escapeCsv(text(values.get(i))));
        }
        csv.append("\r\n");
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof Double d) return d == Math.rint(d) && !d.isInfinite() ? String.valueOf(d.longValue()) : d.toString();
        return value.toString();
    }

    static String escapeCsv(String value) {
        String safe = value;
        // Keep spreadsheet apps from evaluating text (e.g. AI-written reasons) as a formula.
        if (!safe.isEmpty() && "=+@\t\r".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    static byte[] toExcel(List<Table> sheets) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            for (Table table : sheets) {
                Sheet sheet = workbook.createSheet(sheetName(table.name()));
                Row header = sheet.createRow(0);
                for (int c = 0; c < table.headers().size(); c++) {
                    Cell cell = header.createCell(c);
                    cell.setCellValue(table.headers().get(c));
                    cell.setCellStyle(headerStyle);
                    sheet.setColumnWidth(c, 18 * 256);
                }
                for (int r = 0; r < table.rows().size(); r++) {
                    Row row = sheet.createRow(r + 1);
                    List<Object> values = table.rows().get(r);
                    for (int c = 0; c < values.size(); c++) {
                        Object value = values.get(c);
                        Cell cell = row.createCell(c);
                        if (value == null) continue;
                        if (value instanceof Number n) cell.setCellValue(n.doubleValue());
                        else if (value instanceof Boolean b) cell.setCellValue(b);
                        else cell.setCellValue(value.toString());
                    }
                }
                sheet.createFreezePane(0, 1);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** Excel sheet names: max 31 characters, no []:*?/\ . */
    private static String sheetName(String name) {
        String cleaned = name.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        return cleaned.length() > 31 ? cleaned.substring(0, 31).trim() : cleaned;
    }
}
