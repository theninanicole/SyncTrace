package com.ieee.evaluator.synctrace.validation;

import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.security.AuthenticatedUser;
import com.ieee.evaluator.service.GoogleDocsService;
import com.ieee.evaluator.service.OpenAiProvider;
import com.ieee.evaluator.synctrace.controller.*;
import com.ieee.evaluator.synctrace.model.GitHubRepositoryLink;
import com.ieee.evaluator.synctrace.model.MappingSuggestion;
import com.ieee.evaluator.synctrace.repository.MappingSuggestionRepository;
import com.ieee.evaluator.synctrace.service.MappingSuggestionService;
import com.ieee.evaluator.synctrace.validation.controller.ValidationMetricsController;
import com.ieee.evaluator.synctrace.validation.model.*;
import com.ieee.evaluator.synctrace.validation.repository.*;
import com.ieee.evaluator.synctrace.validation.service.ValidationRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end run of the validation metrics on a fresh test team, through the real controllers,
 * services, PostgreSQL database, background recorder and exports. Only the outside services
 * that need real credentials are replaced: the AI provider (a fake that answers each prompt
 * type after a short simulated delay) and the Google Drive reader. GitHub ingestion is real.
 *
 * <p>Opt-in (needs a database and network):
 * {@code SPRING_DATASOURCE_URL=... ./mvnw test -Dtest=ValidationMetricsEndToEndTest -De2e=true -De2e.out=/some/dir}
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "e2e", matches = "true")
class ValidationMetricsEndToEndTest {

    private static final String REPO_URL = "https://github.com/octocat/Spoon-Knife";
    private static final long SIMULATED_AI_MS = 700;

    @TestBean(name = "openAiProvider", methodName = "fakeAi")
    OpenAiProvider openAiProvider;

    @MockitoBean
    GoogleDocsService googleDocsService;

    @Autowired ProposalAnalysisController proposalController;
    @Autowired TraceComponentController componentController;
    @Autowired GitHubController gitHubController;
    @Autowired MappingSuggestionController suggestionController;
    @Autowired StagedMappingController stagedController;
    @Autowired ContinuityController continuityController;
    @Autowired AuditExportController auditController;
    @Autowired ValidationMetricsController metricsController;
    @Autowired ValidationRecorder recorder;
    @Autowired EvaluationHistoryRepository historyRepository;
    @Autowired MappingSuggestionRepository suggestionRepository;
    @Autowired ValidationEventRepository eventRepository;
    @Autowired StageSessionRepository stageSessionRepository;
    @Autowired AnalysisCoverageRepository coverageRepository;
    @Autowired TeamSummaryRowRepository summaryRowRepository;

    static OpenAiProvider fakeAi() {
        OpenAiProvider ai = mock(OpenAiProvider.class);
        when(ai.getProviderName()).thenReturn("openai");
        try {
            when(ai.complete(anyString())).thenAnswer(invocation -> answer(invocation.getArgument(0)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return ai;
    }

    /** Answers each kind of SyncTrace prompt the way a well-behaved model would. */
    static String answer(String prompt) throws InterruptedException {
        Thread.sleep(SIMULATED_AI_MS);
        if (prompt.contains("Return only a JSON array of likely traceability mappings")) {
            String sources = prompt.substring(prompt.indexOf("Sources:\n") + 9, prompt.indexOf("Targets:\n"));
            String sourceId = sources.split(":", 2)[0].trim();
            List<String> targets = new ArrayList<>();
            Matcher m = Pattern.compile("(?m)^(\\d+): ").matcher(prompt.substring(prompt.indexOf("Targets:\n")));
            while (m.find() && targets.size() < 3) targets.add(m.group(1));
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < targets.size(); i++) {
                if (i > 0) json.append(',');
                json.append("{\"sourceId\":").append(sourceId).append(",\"targetId\":").append(targets.get(i))
                    .append(",\"score\":").append(0.9 - i * 0.1).append(",\"reason\":\"Shared feature wording\"}");
            }
            return json.append(']').toString();
        }
        if (prompt.contains("FINDINGS:")) {
            int count = (int) prompt.lines().filter(line -> line.matches("^\\d+\\. \\[.*")).count();
            StringBuilder json = new StringBuilder("[");
            for (int i = 1; i <= count; i++) {
                if (i > 1) json.append(',');
                json.append("{\"findingNumber\":").append(i)
                    .append(",\"rootCause\":\"This happened because the linked artifact is missing.\"")
                    .append(",\"recommendation\":\"Add the missing artifact and link it to the goal.\"")
                    .append(",\"priority\":\"HIGH\"}");
            }
            return json.append(']').toString();
        }
        if (prompt.toUpperCase(Locale.ROOT).contains("SMART")) {
            return """
                [{"goalKind":"GENERAL","description":"Help student teams keep their IEEE documents traceable.",
                  "children":[{"description":"Map every SRS use case to a design element by week 10."},
                              {"description":"Detect continuity gaps within 60 seconds."}]}]
                """;
        }
        return "[]";
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String role, String team, String userId) {
        AuthenticatedUser user = new AuthenticatedUser(role.toLowerCase(Locale.ROOT) + "@e2e.test", role, team, userId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

    /**
     * Runs one step the way an HTTP request runs it: with an EntityManager open for the whole
     * call (Spring's open-session-in-view, which is on by default), but no surrounding transaction.
     */
    private <T> T request(java.util.concurrent.Callable<T> call) throws Exception {
        jakarta.persistence.EntityManager em = entityManagerFactory.createEntityManager();
        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(
            entityManagerFactory, new org.springframework.orm.jpa.EntityManagerHolder(em));
        try {
            return call.call();
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            em.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> response) {
        assertTrue(response.getStatusCode().is2xxSuccessful(), String.valueOf(response.getBody()));
        return (Map<String, Object>) response.getBody();
    }

    @Test
    void fullValidationSessionForOneTeam() throws Exception {
        String team = "2627-SEM1-IT9" + ThreadLocalRandom.current().nextInt(10_000, 99_999) + "-01";
        Instant sessionStart = Instant.now().minusSeconds(5);
        when(googleDocsService.extractDocumentContent(anyString(), anyInt(), any(), any()))
            .thenReturn(new GoogleDocsService.DocumentData(
                "SyncTrace proposal. General objective: keep IEEE documents traceable. "
                    + "Specific objectives: map SRS use cases to design; detect continuity gaps.", List.of()));

        signIn("STUDENT", team, "uuid-e2e-student");

        // 1. Extract SMART goals (M1), then save the reviewed tree.
        Map<String, Object> extracted = body(request(() -> proposalController.extractSmartGoals(Map.of(
            "fileId", "e2e-proposal", "fileName", "[PROPOSAL] " + team, "model", "auto", "teamCode", team))));
        body(request(() -> proposalController.saveReviewedGoals(Map.of("teamCode", team, "goals", extracted.get("goals")))));

        // 2. Retrieve components from an IEEE Docs Evaluator report (M2).
        EvaluationHistory history = new EvaluationHistory();
        history.setFileId("e2e-srs");
        history.setFileName("[SRS] G99 - " + team + " | E2E");
        history.setModelUsed("openai");
        history.setEvaluatedAt(LocalDateTime.now());
        history.setEvaluationResult("""
            Summary: Solid document.

            **Diagram Analysis:**
            * [IMG-1] - Use Case Diagram: Register account.
            * [IMG-2] - Use Case Diagram: Map traceability links.
            * [IMG-3] - Use Case Diagram: Run continuity analysis.
            * [IMG-4] - Use Case Diagram: Export audit report.
            * [IMG-5] - Class Diagram: Traceability domain model.

            ## Strengths
            - Clear scope.
            """);
        Long historyId = historyRepository.save(history).getId();
        Map<String, Object> components = body(request(() -> componentController.extractTraceComponents(
            Map.of("historyId", historyId), null)));
        assertTrue(((Number) components.get("count")).intValue() >= 4, components.toString());

        // 3. Link and ingest a real GitHub repository (M3).
        Map<String, Object> linked = body(request(() -> gitHubController.linkGitHubRepository(Map.of(
            "teamCode", team, "githubUrl", REPO_URL, "role", "FRONTEND"))));
        Long repositoryId = ((GitHubRepositoryLink) linked.get("repository")).getId();
        body(request(() -> gitHubController.ingestLinkedRepository(repositoryId, Map.of("teamCode", team, "sessionId", "e2e-ingest"))));

        // 4. Map Proposal → SRS: open the stage, take AI suggestions (accept, edit, reject), save.
        request(() -> metricsController.stageHeartbeat(Map.of("teamCode", team, "stage", "PROPOSAL_SRS", "state", "visible")));
        Thread.sleep(1_000);
        request(() -> metricsController.stageHeartbeat(Map.of("teamCode", team, "stage", "PROPOSAL_SRS", "state", "visible")));
        MappingSuggestionService.GenerationResult generated = (MappingSuggestionService.GenerationResult)
            request(() -> suggestionController.generate(Map.of("teamCode", team, "stage", "PROPOSAL_SRS", "sessionId", "e2e-suggest")))
                .getBody();
        List<MappingSuggestion> suggestions = generated.suggestions();
        assertEquals(3, suggestions.size(), "the fake model proposes three links");
        request(() -> suggestionController.decide(suggestions.get(0).getId(),
            new MappingSuggestionService.Decision("ACCEPT", null), team));
        request(() -> suggestionController.decide(suggestions.get(1).getId(), new MappingSuggestionService.Decision("EDIT",
            List.of(suggestions.get(1).getTargetComponentId(), suggestions.get(2).getTargetComponentId())), team));
        request(() -> suggestionController.decide(suggestions.get(2).getId(),
            new MappingSuggestionService.Decision("REJECT", null), team));
        request(() -> metricsController.stageHeartbeat(Map.of("teamCode", team, "stage", "PROPOSAL_SRS", "state", "visible")));
        body(request(() -> stagedController.sync(Map.of("teamCode", team, "stage", "PROPOSAL_SRS"))));

        // 5. Run continuity analysis: both steps share one run id (M6), coverage snapshot (M7).
        String runId = UUID.randomUUID().toString();
        body(request(() -> continuityController.detectContinuityGaps(Map.of("teamCode", team, "analysisRunId", runId))));
        body(request(() -> continuityController.generateRecommendations(Map.of(
            "teamCode", team, "model", "auto", "sessionId", runId, "analysisRunId", runId))));

        // 6. Export the audit report as PDF and CSV (M8).
        assertEquals(200, request(() -> auditController.exportAuditReport(team, "pdf")).getStatusCode().value());
        assertEquals(200, request(() -> auditController.exportAuditReport(team, "csv")).getStatusCode().value());

        assertTrue(recorder.awaitIdle(Duration.ofSeconds(30)), "metric writes finished");

        // ── What was recorded ───────────────────────────────────────────────
        List<ValidationEvent> events = eventRepository.findByTeamIdIgnoreCaseOrderByStartedAtAscIdAsc(team);
        Map<String, Long> byOperation = new TreeMap<>();
        events.forEach(e -> byOperation.merge(e.getOperation() + "/" + e.getStatus(), 1L, Long::sum));
        assertEquals(Map.of(
            "AUDIT_EXPORT/success", 2L, "COMPONENT_RETRIEVAL/success", 1L, "GAP_DETECTION/success", 1L,
            "GOAL_EXTRACTION/success", 1L, "RECOMMENDATIONS/success", 1L, "REPO_INGESTION/success", 1L), byOperation);
        assertTrue(events.stream().allMatch(e -> "uuid-e2e-student".equals(e.getUserId())));
        assertEquals(repositoryId, events.stream().filter(e -> e.getMetricCode().equals("M3"))
            .findFirst().orElseThrow().getRepositoryId());

        List<StageSession> sessions = stageSessionRepository.findByTeamIdIgnoreCaseOrderByStartedAtAscIdAsc(team);
        assertEquals(1, sessions.size());
        StageSession stage = sessions.get(0);
        assertNotNull(stage.getFinalizedAt());
        assertTrue(stage.getActiveMs() > 0 && stage.getActiveMs() <= stage.getWallClockMs());
        assertEquals(3, stage.getLinksSaved()); // 1 accepted + 2 from the edit
        assertEquals("uuid-e2e-student", stage.getFinalizedBy());

        Map<MappingSuggestion.Status, Long> decisions = new EnumMap<>(MappingSuggestion.Status.class);
        suggestionRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(team)
            .forEach(s -> decisions.merge(s.getStatus(), 1L, Long::sum));
        assertEquals(Map.of(MappingSuggestion.Status.ACCEPTED, 1L, MappingSuggestion.Status.EDITED, 1L,
            MappingSuggestion.Status.REJECTED, 1L), decisions);

        List<AnalysisCoverage> coverage = coverageRepository.findByTeamIdIgnoreCaseOrderByCreatedAtAscIdAsc(team);
        assertEquals(1, coverage.size());
        assertEquals(runId, coverage.get(0).getAnalysisRunId());
        assertEquals(coverage.get(0).getGapsDetected(), coverage.get(0).getGapsWithBoth());

        assertFalse(summaryRowRepository.findAll().stream()
            .noneMatch(row -> row.getKey().getTeamId().equalsIgnoreCase(team)), "validation_team_summary refreshed");

        // 7. Teacher: mark the session, read the page data, export CSV (zip) and Excel.
        signIn("TEACHER", null, "uuid-e2e-teacher");
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("name", "E2E validation session");
        created.put("startsAt", sessionStart.toString());
        created.put("endsAt", Instant.now().plusSeconds(60).toString());
        created.put("teamIds", List.of(team));
        ValidationSession session = (ValidationSession) request(() -> metricsController.createSession(created)).getBody();

        Map<String, Object> summary = body(request(() -> metricsController.summary(null, session.getId(), null, null)));
        byte[] zip = (byte[]) request(() -> metricsController.export("zip", null, session.getId(), null, null)).getBody();
        byte[] xlsx = (byte[]) request(() -> metricsController.export("xlsx", team, null, null, null)).getBody();

        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(5, files.size());
        String summaryCsv = files.get("summary.csv").replace("﻿", "");
        assertEquals(2, summaryCsv.split("\r\n").length, "header + one team row");
        assertTrue(summaryCsv.contains(team));
        files.values().forEach(content -> assertFalse(content.contains("@"), "no emails in exports"));

        String out = System.getProperty("e2e.out");
        if (out != null && !out.isBlank()) {
            Path dir = Files.createDirectories(Path.of(out));
            Files.write(dir.resolve("validation-metrics.zip"), zip);
            Files.write(dir.resolve("validation-metrics.xlsx"), xlsx);
            for (Map.Entry<String, String> file : files.entrySet()) {
                Files.writeString(dir.resolve(file.getKey()), file.getValue());
            }
            Files.writeString(dir.resolve("summary.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(summary));
            Files.writeString(dir.resolve("team.txt"), team);
        }
    }
}
