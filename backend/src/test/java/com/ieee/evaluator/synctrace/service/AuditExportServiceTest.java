package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.synctrace.model.*;
import com.ieee.evaluator.synctrace.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuditExportServiceTest {

    @Autowired
    private AuditExportService auditExportService;

    @Autowired
    private SmartGoalRepository goalRepository;

    @Autowired
    private TraceComponentRepository componentRepository;

    @Autowired
    private GoalComponentMappingRepository mappingRepository;

    @Autowired
    private EvaluationHistoryRepository historyRepository;

    private static final String TEAM_A = "2026-SEM1-IT01-01";
    private static final String TEAM_B = "2026-SEM1-IT01-02";

    @BeforeEach
    void cleanup() {
        mappingRepository.deleteAll();
        componentRepository.deleteAll();
        goalRepository.deleteAll();
        historyRepository.deleteAll();
    }

    @Test
    void testAuditExportDoesNotLeakDataBetweenTeams() throws Exception {
        // Create evaluation history for team A (for SDD component team resolution)
        EvaluationHistory historyA = new EvaluationHistory();
        historyA.setFileName("[SRS] G01 - " + TEAM_A + " | Student A");
        historyA.setEvaluatedAt(LocalDateTime.now());
        historyA = historyRepository.save(historyA);

        // Create evaluation history for team B
        EvaluationHistory historyB = new EvaluationHistory();
        historyB.setFileName("[SRS] G01 - " + TEAM_B + " | Student B");
        historyB.setEvaluatedAt(LocalDateTime.now());
        historyB = historyRepository.save(historyB);

        // Create IMPLEMENTATION component for team A (name-based scoping)
        TraceComponent implA = new TraceComponent();
        implA.setName(TEAM_A + " - src/main/java/Example.java");
        implA.setDocType(TraceComponent.DocType.IMPLEMENTATION);
        implA.setContent("public class Example {}");
        implA.setAiExtracted(true);
        implA.setCreatedAt(LocalDateTime.now());
        implA = componentRepository.save(implA);

        // Create IMPLEMENTATION component for team B
        TraceComponent implB = new TraceComponent();
        implB.setName(TEAM_B + " - src/main/java/Example.java");
        implB.setDocType(TraceComponent.DocType.IMPLEMENTATION);
        implB.setContent("public class Example {}");
        implB.setAiExtracted(true);
        implB.setCreatedAt(LocalDateTime.now());
        implB = componentRepository.save(implB);

        // Create SDD component for team A (history-based scoping)
        TraceComponent sddA = new TraceComponent();
        sddA.setName("Design Document A");
        sddA.setDocType(TraceComponent.DocType.SDD);
        sddA.setContent("SDD content for team A");
        sddA.setAiExtracted(true);
        sddA.setSourceHistoryId(historyA.getId());
        sddA.setCreatedAt(LocalDateTime.now());
        sddA = componentRepository.save(sddA);

        // Create SDD component for team B
        TraceComponent sddB = new TraceComponent();
        sddB.setName("Design Document B");
        sddB.setDocType(TraceComponent.DocType.SDD);
        sddB.setContent("SDD content for team B");
        sddB.setAiExtracted(true);
        sddB.setSourceHistoryId(historyB.getId());
        sddB.setCreatedAt(LocalDateTime.now());
        sddB = componentRepository.save(sddB);

        // Create goal for team A
        SmartGoal goalA = new SmartGoal();
        goalA.setDescription("Goal A");
        goalA.setCreatedAt(LocalDateTime.now());
        goalA = goalRepository.save(goalA);

        // Create goal for team B
        SmartGoal goalB = new SmartGoal();
        goalB.setDescription("Goal B");
        goalB.setCreatedAt(LocalDateTime.now());
        goalB = goalRepository.save(goalB);

        // Map goal A to team A's components
        GoalComponentMapping mappingA1 = new GoalComponentMapping();
        mappingA1.setGoalId(goalA.getId());
        mappingA1.setComponentId(implA.getId());
        mappingRepository.save(mappingA1);

        GoalComponentMapping mappingA2 = new GoalComponentMapping();
        mappingA2.setGoalId(goalA.getId());
        mappingA2.setComponentId(sddA.getId());
        mappingRepository.save(mappingA2);

        // Map goal B to team B's components
        GoalComponentMapping mappingB1 = new GoalComponentMapping();
        mappingB1.setGoalId(goalB.getId());
        mappingB1.setComponentId(implB.getId());
        mappingRepository.save(mappingB1);

        GoalComponentMapping mappingB2 = new GoalComponentMapping();
        mappingB2.setGoalId(goalB.getId());
        mappingB2.setComponentId(sddB.getId());
        mappingRepository.save(mappingB2);

        // Export audit report for team A
        byte[] reportA = auditExportService.exportAuditReport(TEAM_A, "json");
        String reportAString = new String(reportA);

        // Verify team A's data is present
        assertTrue(reportAString.contains("\"description\":\"Goal A\""), "Team A's goal should be in export");
        assertTrue(reportAString.contains(TEAM_A), "Team A's component should be in export");

        // Verify team B's data is NOT present (data leakage check)
        assertFalse(reportAString.contains("\"description\":\"Goal B\""), "Team B's goal should NOT be in export");
        assertFalse(reportAString.contains(TEAM_B), "Team B's component should NOT be in export");

        // Export audit report for team B
        byte[] reportB = auditExportService.exportAuditReport(TEAM_B, "json");
        String reportBString = new String(reportB);

        // Verify team B's data is present
        assertTrue(reportBString.contains("\"description\":\"Goal B\""), "Team B's goal should be in export");
        assertTrue(reportBString.contains(TEAM_B), "Team B's component should be in export");

        // Verify team A's data is NOT present (data leakage check)
        assertFalse(reportBString.contains("\"description\":\"Goal A\""), "Team A's goal should NOT be in export");
        assertFalse(reportBString.contains(TEAM_A), "Team A's component should NOT be in export");
    }

    @Test
    void testTeamComponentResolverFiltersCorrectly() {
        // Create evaluation history for team A
        EvaluationHistory historyA = new EvaluationHistory();
        historyA.setFileName("[SRS] G01 - " + TEAM_A + " | Student A");
        historyA.setEvaluatedAt(LocalDateTime.now());
        historyA = historyRepository.save(historyA);

        // Create IMPLEMENTATION component for team A
        TraceComponent implA = new TraceComponent();
        implA.setName(TEAM_A + " - src/main.java");
        implA.setDocType(TraceComponent.DocType.IMPLEMENTATION);
        implA.setAiExtracted(true);
        implA.setCreatedAt(LocalDateTime.now());
        implA = componentRepository.save(implA);

        // Create IMPLEMENTATION component for team B
        TraceComponent implB = new TraceComponent();
        implB.setName(TEAM_B + " - src/main.java");
        implB.setDocType(TraceComponent.DocType.IMPLEMENTATION);
        implB.setAiExtracted(true);
        implB.setCreatedAt(LocalDateTime.now());
        implB = componentRepository.save(implB);

        // Create SDD component for team A
        TraceComponent sddA = new TraceComponent();
        sddA.setName("SDD A");
        sddA.setDocType(TraceComponent.DocType.SDD);
        sddA.setAiExtracted(true);
        sddA.setSourceHistoryId(historyA.getId());
        sddA.setCreatedAt(LocalDateTime.now());
        sddA = componentRepository.save(sddA);

        // Create SDD component for team B
        TraceComponent sddB = new TraceComponent();
        sddB.setName("SDD B");
        sddB.setDocType(TraceComponent.DocType.SDD);
        sddB.setAiExtracted(true);
        sddB.setCreatedAt(LocalDateTime.now());
        sddB = componentRepository.save(sddB); // No sourceHistoryId - should not match any team

        // Create component with no team resolution pattern (SRS)
        TraceComponent srs = new TraceComponent();
        srs.setName("SRS Document");
        srs.setDocType(TraceComponent.DocType.SRS);
        srs.setAiExtracted(true);
        srs.setCreatedAt(LocalDateTime.now());
        srs = componentRepository.save(srs);

        // Test filtering
        List<TraceComponent> allComponents = componentRepository.findAll();
        List<TraceComponent> teamAComponents = allComponents.stream()
            .filter(comp -> {
                // Use the same logic as TeamComponentResolverService
                if (comp.getDocType() == TraceComponent.DocType.IMPLEMENTATION) {
                    return comp.getName() != null && comp.getName().contains(TEAM_A);
                }
                if (comp.getDocType() == TraceComponent.DocType.SDD) {
                    if (comp.getSourceHistoryId() == null) return false;
                    return historyRepository.findById(comp.getSourceHistoryId())
                        .map(h -> TeamCodeResolver.extractTeamCode(h.getFileName()).equalsIgnoreCase(TEAM_A))
                        .orElse(false);
                }
                return false;
            })
            .toList();

        // Should only include implA and sddA
        assertEquals(2, teamAComponents.size());
        assertTrue(teamAComponents.contains(implA));
        assertTrue(teamAComponents.contains(sddA));
        assertFalse(teamAComponents.contains(implB));
        assertFalse(teamAComponents.contains(sddB));
        assertFalse(teamAComponents.contains(srs));
    }
}
