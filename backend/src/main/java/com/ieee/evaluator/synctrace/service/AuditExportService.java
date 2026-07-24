package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.DiagnosticRecommendation;
import com.ieee.evaluator.synctrace.model.SmartGoal;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.DiagnosticRecommendationRepository;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import com.ieee.evaluator.synctrace.service.TeamComponentResolverService;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AuditExportService {

    private final SmartGoalRepository goalRepository;
    private final TraceComponentRepository componentRepository;
    private final ContinuityFindingRepository findingRepository;
    private final DiagnosticRecommendationRepository recommendationRepository;
    private final GoalComponentMappingRepository mappingRepository;
    private final TeamComponentResolverService teamComponentResolver;

    public AuditExportService(
            SmartGoalRepository goalRepository,
            TraceComponentRepository componentRepository,
            ContinuityFindingRepository findingRepository,
            DiagnosticRecommendationRepository recommendationRepository,
            GoalComponentMappingRepository mappingRepository,
            TeamComponentResolverService teamComponentResolver) {
        this.goalRepository = goalRepository;
        this.componentRepository = componentRepository;
        this.findingRepository = findingRepository;
        this.recommendationRepository = recommendationRepository;
        this.mappingRepository = mappingRepository;
        this.teamComponentResolver = teamComponentResolver;
    }

    public byte[] exportAuditReport(String teamCode, String format) throws Exception {
        switch (format.toLowerCase()) {
            case "json":
                return exportJson(teamCode);
            case "csv":
                return exportCsv(teamCode);
            case "pdf":
                return exportPdf(teamCode);
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }
    }

    private byte[] exportJson(String teamCode) {
        AuditReportData data = collectAuditData(teamCode);
        
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"teamCode\": \"").append(teamCode).append("\",\n");
        json.append("  \"exportedAt\": \"").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\",\n");
        
        json.append("  \"goals\": [");
        List<Map<String, Object>> goals = data.goals();
        for (int i = 0; i < goals.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\n    ").append(mapToJson(goals.get(i)));
        }
        json.append("\n  ],\n");
        
        json.append("  \"components\": [");
        List<Map<String, Object>> components = data.components();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\n    ").append(mapToJson(components.get(i)));
        }
        json.append("\n  ],\n");
        
        json.append("  \"findings\": [");
        List<Map<String, Object>> findings = data.findings();
        for (int i = 0; i < findings.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\n    ").append(mapToJson(findings.get(i)));
        }
        json.append("\n  ],\n");
        
        json.append("  \"recommendations\": [");
        List<Map<String, Object>> recommendations = data.recommendations();
        for (int i = 0; i < recommendations.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\n    ").append(mapToJson(recommendations.get(i)));
        }
        json.append("\n  ]\n");
        json.append("}");
        
        return json.toString().getBytes();
    }

    private byte[] exportCsv(String teamCode) {
        AuditReportData data = collectAuditData(teamCode);
        StringBuilder csv = new StringBuilder();
        
        // Findings CSV
        csv.append("ID,Team Code,Severity,Doc Type From,Doc Type To,Description,Detected At\n");
        for (Map<String, Object> finding : data.findings()) {
            csv.append(finding.get("id")).append(",");
            csv.append(escapeCsv(finding.get("teamCode").toString())).append(",");
            csv.append(escapeCsv(finding.get("severity").toString())).append(",");
            csv.append(escapeCsv(finding.get("docTypeFrom").toString())).append(",");
            csv.append(escapeCsv(finding.get("docTypeTo").toString())).append(",");
            csv.append(escapeCsv(finding.get("description").toString())).append(",");
            csv.append(escapeCsv(finding.get("detectedAt").toString())).append("\n");
        }
        
        return csv.toString().getBytes();
    }

    private byte[] exportPdf(String teamCode) throws Exception {
        AuditReportData data = collectAuditData(teamCode);
        
        Document document = new Document();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, baos);
        
        document.open();
        
        // Title
        Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD);
        document.add(new Paragraph("Traceability Audit Report", titleFont));
        
        document.add(new Paragraph("Team: " + teamCode));
        document.add(new Paragraph(
            "Generated: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        document.add(new Paragraph(" "));
        
        // Readiness Summary
        Font headerFont = new Font(Font.HELVETICA, 14, Font.BOLD);
        document.add(new Paragraph("Readiness Summary", headerFont));
        document.add(new Paragraph("Total Goals: " + data.goals().size()));
        document.add(new Paragraph("Total Components: " + data.components().size()));
        document.add(new Paragraph("Total Findings: " + data.findings().size()));
        document.add(new Paragraph(" "));
        
        // Goals Section
        document.add(new Paragraph("SMART Goals", headerFont));
        for (Map<String, Object> goal : data.goals()) {
            document.add(new Paragraph("- " + goal.get("description").toString()));
        }
        document.add(new Paragraph(" "));
        
        // Findings Section
        document.add(new Paragraph("Continuity Findings", headerFont));
        for (Map<String, Object> finding : data.findings()) {
            document.add(new Paragraph(
                "[" + finding.get("severity") + "] " + 
                finding.get("docTypeFrom") + " → " + finding.get("docTypeTo") + ": " +
                finding.get("description")));
        }
        document.add(new Paragraph(" "));
        
        // Recommendations Section
        document.add(new Paragraph("Recommendations", headerFont));
        for (Map<String, Object> rec : data.recommendations()) {
            document.add(new Paragraph("[" + rec.get("priority") + "] " + rec.get("recommendation")));
        }
        
        document.close();
        
        return baos.toByteArray();
    }

    private AuditReportData collectAuditData(String teamCode) {
        // Filter components by team code using shared resolution logic
        List<TraceComponent> allComponents = componentRepository.findAll();
        List<TraceComponent> teamComponents = teamComponentResolver.filterByTeam(allComponents, teamCode);
        
        // Get component IDs for team-scoped components
        List<Long> teamComponentIds = teamComponents.stream()
            .map(com.ieee.evaluator.synctrace.model.TraceComponent::getId)
            .toList();
        
        // Filter goals to only include those with at least one mapping to a team-scoped component
        List<SmartGoal> allGoals = goalRepository.findAll();
        List<SmartGoal> teamGoals = allGoals.stream()
            .filter(goal -> {
                List<Long> mappedComponentIds = mappingRepository.findByGoalId(goal.getId()).stream()
                    .map(com.ieee.evaluator.synctrace.model.GoalComponentMapping::getComponentId)
                    .toList();
                // Goal belongs to team if it has at least one mapping to a team-scoped component
                return mappedComponentIds.stream().anyMatch(teamComponentIds::contains);
            })
            .toList();
        
        // Findings are already team-scoped
        List<ContinuityFinding> allFindings = findingRepository.findByTeamCode(teamCode);
        
        List<Long> findingIds = allFindings.stream()
            .map(ContinuityFinding::getId)
            .toList();
        
        List<DiagnosticRecommendation> allRecommendations = findingIds.isEmpty() 
            ? List.of()
            : recommendationRepository.findAllById(findingIds);
        
        return new AuditReportData(
            teamGoals.stream().map(this::goalToMap).toList(),
            teamComponents.stream().map(this::componentToMap).toList(),
            allFindings.stream().map(this::findingToMap).toList(),
            allRecommendations.stream().map(this::recommendationToMap).toList()
        );
    }

    private Map<String, Object> goalToMap(SmartGoal goal) {
        return Map.of(
            "id", goal.getId(),
            "description", goal.getDescription(),
            "createdAt", goal.getCreatedAt()
        );
    }

    private Map<String, Object> componentToMap(TraceComponent component) {
        return Map.of(
            "id", component.getId(),
            "docType", component.getDocType(),
            "name", component.getName(),
            "aiExtracted", component.getAiExtracted(),
            "createdAt", component.getCreatedAt()
        );
    }

    private Map<String, Object> findingToMap(ContinuityFinding finding) {
        return Map.of(
            "id", finding.getId(),
            "teamCode", finding.getTeamCode(),
            "severity", finding.getSeverity(),
            "docTypeFrom", finding.getDocTypeFrom(),
            "docTypeTo", finding.getDocTypeTo(),
            "description", finding.getDescription(),
            "detectedAt", finding.getDetectedAt()
        );
    }

    private Map<String, Object> recommendationToMap(DiagnosticRecommendation rec) {
        return Map.of(
            "id", rec.getId(),
            "findingId", rec.getFindingId(),
            "rootCause", rec.getRootCause(),
            "recommendation", rec.getRecommendation(),
            "priority", rec.getPriority(),
            "createdAt", rec.getCreatedAt()
        );
    }

    private String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\": ");
            Object value = entry.getValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof String) {
                sb.append("\"").append(escapeJson(value.toString())).append("\"");
            } else {
                sb.append("\"").append(escapeJson(value.toString())).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private String escapeCsv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private record AuditReportData(
        List<Map<String, Object>> goals,
        List<Map<String, Object>> components,
        List<Map<String, Object>> findings,
        List<Map<String, Object>> recommendations
    ) {}
}
