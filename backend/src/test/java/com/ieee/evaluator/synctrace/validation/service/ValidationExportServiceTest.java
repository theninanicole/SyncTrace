package com.ieee.evaluator.synctrace.validation.service;

import com.ieee.evaluator.synctrace.model.MappingSuggestion;
import com.ieee.evaluator.synctrace.repository.MappingSuggestionRepository;
import com.ieee.evaluator.synctrace.validation.config.ValidationProperties;
import com.ieee.evaluator.synctrace.validation.model.*;
import com.ieee.evaluator.synctrace.validation.repository.*;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ValidationExportServiceTest {

    private ValidationExportService exportService;

    @BeforeEach
    void setUp() {
        ValidationEventRepository events = mock(ValidationEventRepository.class);
        StageSessionRepository sessions = mock(StageSessionRepository.class);
        MappingSuggestionRepository suggestions = mock(MappingSuggestionRepository.class);
        AnalysisCoverageRepository coverage = mock(AnalysisCoverageRepository.class);

        ValidationEvent event = new ValidationEvent();
        event.setId(1L);
        event.setTeamId("TEAM-A");
        event.setUserId("uuid-1");
        event.setMetricCode("M1");
        event.setOperation("GOAL_EXTRACTION");
        event.setStartedAt(Instant.parse("2026-11-25T01:00:00Z"));
        event.setEndedAt(Instant.parse("2026-11-25T01:00:12Z"));
        event.setDurationMs(12_000);
        event.setStatus("success");
        when(events.findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscIdAsc(any(), any()))
            .thenReturn(List.of(event));

        StageSession session = new StageSession();
        session.setId(2L);
        session.setTeamId("TEAM-A");
        session.setStage("proposal_srs");
        session.setStartedAt(Instant.parse("2026-11-25T01:05:00Z"));
        session.setFinalizedAt(Instant.parse("2026-11-25T01:15:00Z"));
        session.setWallClockMs(600_000L);
        session.setActiveMs(480_000);
        session.setFinalizedBy("uuid-2");
        when(sessions.findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscIdAsc(any(), any()))
            .thenReturn(List.of(session));

        MappingSuggestion suggestion = new MappingSuggestion();
        suggestion.setId(3L);
        suggestion.setTeamCode("TEAM-A");
        suggestion.setStage("PROPOSAL_SRS");
        suggestion.setSourceId(1L);
        suggestion.setTargetComponentId(10L);
        suggestion.setReason("=HYPERLINK(\"x\") looks, like a match");
        suggestion.setStatus(MappingSuggestion.Status.REJECTED);
        suggestion.setDecidedBy("student@example.edu");          // email: must never be exported
        suggestion.setDecidedByUserId("uuid-3");
        suggestion.setCreatedAt(LocalDateTime.of(2026, 11, 25, 1, 6));
        when(suggestions.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(any(), any()))
            .thenReturn(List.of(suggestion));

        AnalysisCoverage run = new AnalysisCoverage();
        run.setId(4L);
        run.setAnalysisRunId("run-1");
        run.setTeamId("TEAM-A");
        run.setGapsDetected(2);
        run.setGapsWithBoth(2);
        run.setRecommendationStatus("success");
        when(coverage.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(any(), any()))
            .thenReturn(List.of(run));

        ValidationSummaryService summary = new ValidationSummaryService(events, sessions, suggestions, coverage,
            mock(TeamSummaryRowRepository.class), ValidationProperties.defaults(), ZoneOffset.UTC);
        exportService = new ValidationExportService(summary, ZoneOffset.UTC);
    }

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void zipHasTheSummaryAndOneRawCsvPerTable() throws Exception {
        Map<String, String> files = unzip(exportService.exportZip(ValidationFilter.allTime(List.of())));

        assertEquals(List.of("summary.csv", "validation_events.csv", "stage_sessions.csv",
            "ai_suggestions.csv", "analysis_coverage.csv"), new ArrayList<>(files.keySet()));

        String[] summary = files.get("summary.csv").replace("﻿", "").split("\r\n");
        assertTrue(summary[0].startsWith("team_id,m1_count,m1_median_s,m1_max_s,m1_pct_within_target,m1_met"));
        assertTrue(summary[0].contains("m5_acceptance_rate") && summary[0].contains("m7_coverage_pct"));
        assertTrue(summary[1].startsWith("TEAM-A,1,12,12,100,true"), summary[1]);

        assertTrue(files.get("ai_suggestions.csv").contains("proposal_srs"));
        assertTrue(files.get("ai_suggestions.csv").contains("uuid-3"));
        assertTrue(files.get("stage_sessions.csv").contains("480000"));
    }

    @Test
    void exportsContainNoEmails() throws Exception {
        for (String content : unzip(exportService.exportZip(ValidationFilter.allTime(List.of()))).values()) {
            assertFalse(content.contains("@"), content);
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(
                exportService.exportExcel(ValidationFilter.allTime(List.of()))))) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        assertFalse(cell.toString().contains("@"), sheet.getSheetName() + ": " + cell);
                    }
                }
            }
        }
    }

    @Test
    void workbookHasASummarySheetAndOneSheetPerMetric() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(
                exportService.exportExcel(ValidationFilter.allTime(List.of()))))) {
            assertEquals(9, workbook.getNumberOfSheets());
            assertEquals("Summary", workbook.getSheetAt(0).getSheetName());
            for (int i = 1; i <= 8; i++) {
                assertTrue(workbook.getSheetAt(i).getSheetName().startsWith("M" + i), workbook.getSheetAt(i).getSheetName());
            }
            assertEquals("TEAM-A", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals(1, workbook.getSheet(workbook.getSheetAt(1).getSheetName()).getLastRowNum()); // one M1 row
        }
    }

    @Test
    void csvEscapingQuotesAndDefusesFormulas() {
        assertEquals("plain", ValidationExportService.escapeCsv("plain"));
        assertEquals("\"a,b\"", ValidationExportService.escapeCsv("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", ValidationExportService.escapeCsv("say \"hi\""));
        assertEquals("'=1+1", ValidationExportService.escapeCsv("=1+1"));
    }
}
