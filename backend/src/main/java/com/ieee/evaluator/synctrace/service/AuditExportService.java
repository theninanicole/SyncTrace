package com.ieee.evaluator.synctrace.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
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
    private final ObjectMapper objectMapper;

    public AuditExportService(
            SmartGoalRepository goalRepository,
            TraceComponentRepository componentRepository,
            ContinuityFindingRepository findingRepository,
            DiagnosticRecommendationRepository recommendationRepository,
            GoalComponentMappingRepository mappingRepository,
            TeamComponentResolverService teamComponentResolver,
            ObjectMapper objectMapper) {
        this.goalRepository = goalRepository;
        this.componentRepository = componentRepository;
        this.findingRepository = findingRepository;
        this.recommendationRepository = recommendationRepository;
        this.mappingRepository = mappingRepository;
        this.teamComponentResolver = teamComponentResolver;
        this.objectMapper = objectMapper;
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

    private byte[] exportJson(String teamCode) throws Exception {
        AuditReportData data = collectAuditData(teamCode);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("teamCode", teamCode);
        report.put("exportedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        report.put("goals", data.goals());
        report.put("components", data.components());
        report.put("findings", data.findings());
        report.put("recommendations", data.recommendations());

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
    }

    private byte[] exportCsv(String teamCode) {
        AuditReportData data = collectAuditData(teamCode);
        StringBuilder csv = new StringBuilder();

        csv.append("SMART Goals\n");
        csv.append("ID,Description,Team Code,Created At\n");
        for (Map<String, Object> goal : data.goals()) {
            appendCsvRow(csv, goal.get("id"), goal.get("description"), goal.get("teamCode"), goal.get("createdAt"));
        }
        csv.append("\n");

        csv.append("Components\n");
        csv.append("ID,Doc Type,Artifact Kind,Name,AI Extracted,Created At\n");
        for (Map<String, Object> component : data.components()) {
            appendCsvRow(csv, component.get("id"), component.get("docType"), component.get("artifactKind"),
                    component.get("name"), component.get("aiExtracted"), component.get("createdAt"));
        }
        csv.append("\n");

        csv.append("Continuity Findings\n");
        csv.append("ID,Team Code,Severity,Doc Type From,Doc Type To,Description,Detected At\n");
        for (Map<String, Object> finding : data.findings()) {
            appendCsvRow(csv, finding.get("id"), finding.get("teamCode"), finding.get("severity"),
                    finding.get("docTypeFrom"), finding.get("docTypeTo"), finding.get("description"),
                    finding.get("detectedAt"));
        }
        csv.append("\n");

        csv.append("Recommendations\n");
        csv.append("ID,Finding ID,Root Cause,Recommendation,Priority,Created At\n");
        for (Map<String, Object> rec : data.recommendations()) {
            appendCsvRow(csv, rec.get("id"), rec.get("findingId"), rec.get("rootCause"),
                    rec.get("recommendation"), rec.get("priority"), rec.get("createdAt"));
        }

        return csv.toString().getBytes();
    }

    private void appendCsvRow(StringBuilder csv, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) csv.append(",");
            csv.append(escapeCsv(csvValue(values[i])));
        }
        csv.append("\n");
    }

    private String csvValue(Object value) {
        return value == null ? "" : value.toString();
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
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", goal.getId());
        map.put("description", goal.getDescription());
        map.put("teamCode", goal.getTeamCode());
        map.put("createdAt", goal.getCreatedAt());
        return map;
    }

    private Map<String, Object> componentToMap(TraceComponent component) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", component.getId());
        map.put("docType", component.getDocType());
        map.put("artifactKind", component.getArtifactKind() != null ? component.getArtifactKind().name() : "UNSPECIFIED");
        map.put("name", component.getName());
        map.put("aiExtracted", component.getAiExtracted());
        map.put("createdAt", component.getCreatedAt());
        return map;
    }

    private Map<String, Object> findingToMap(ContinuityFinding finding) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", finding.getId());
        map.put("teamCode", finding.getTeamCode());
        map.put("severity", finding.getSeverity());
        map.put("docTypeFrom", finding.getDocTypeFrom());
        map.put("docTypeTo", finding.getDocTypeTo());
        map.put("description", finding.getDescription());
        map.put("detectedAt", finding.getDetectedAt());
        return map;
    }

    private Map<String, Object> recommendationToMap(DiagnosticRecommendation rec) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rec.getId());
        map.put("findingId", rec.getFindingId());
        map.put("rootCause", rec.getRootCause());
        map.put("recommendation", rec.getRecommendation());
        map.put("priority", rec.getPriority());
        map.put("createdAt", rec.getCreatedAt());
        return map;
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
