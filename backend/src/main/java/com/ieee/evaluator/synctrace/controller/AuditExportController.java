package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.service.AuditExportService;
import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/synctrace/audit")
public class AuditExportController {

    private final AuditExportService auditExportService;
    private final SyncTraceAccessGuard accessGuard;

    public AuditExportController(AuditExportService auditExportService, SyncTraceAccessGuard accessGuard) {
        this.auditExportService = auditExportService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/{teamCode}/export")
    public ResponseEntity<?> exportAuditReport(
            @PathVariable String teamCode,
            @RequestParam(defaultValue = "json") String format) {
        try {
            // Teachers may export any team; students only their own (same rule as the Results page).
            String effectiveTeamCode = accessGuard.resolveEffectiveTeamCode(teamCode);
            String normalizedFormat = format.toLowerCase(Locale.ROOT);
            byte[] reportData = auditExportService.exportAuditReport(effectiveTeamCode, normalizedFormat);

            MediaType mediaType = switch (normalizedFormat) {
                case "pdf" -> MediaType.APPLICATION_PDF;
                case "csv" -> MediaType.parseMediaType("text/csv; charset=UTF-8");
                default -> MediaType.APPLICATION_JSON;
            };

            String safeTeam = effectiveTeamCode.trim().replaceAll("[^A-Za-z0-9._-]", "_");
            String filename = "audit-report-" + safeTeam + "-" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) +
                "." + normalizedFormat;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(mediaType);
            headers.setContentDispositionFormData("attachment", filename);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(reportData);
        } catch (SyncTraceAccessGuard.AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Export failed: " + e.getMessage()));
        }
    }
}
