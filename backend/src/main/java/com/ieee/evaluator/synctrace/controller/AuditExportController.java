package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.service.AuditExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api/synctrace/audit")
public class AuditExportController {

    private final AuditExportService auditExportService;

    public AuditExportController(AuditExportService auditExportService) {
        this.auditExportService = auditExportService;
    }

    @GetMapping("/{teamCode}/export")
    public ResponseEntity<?> exportAuditReport(
            @PathVariable String teamCode,
            @RequestParam(defaultValue = "json") String format) {
        try {
            byte[] reportData = auditExportService.exportAuditReport(teamCode, format);
            
            MediaType mediaType = switch (format.toLowerCase()) {
                case "pdf" -> MediaType.APPLICATION_PDF;
                case "csv" -> MediaType.parseMediaType("text/csv");
                default -> MediaType.APPLICATION_JSON;
            };

            String filename = "audit-report-" + teamCode + "-" + 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + 
                "." + format.toLowerCase();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(mediaType);
            headers.setContentDispositionFormData("attachment", filename);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(reportData);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Export failed: " + e.getMessage()));
        }
    }
}
