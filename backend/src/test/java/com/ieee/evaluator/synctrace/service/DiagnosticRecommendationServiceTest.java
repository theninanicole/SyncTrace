package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.service.AiProvider;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.DiagnosticRecommendation;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.DiagnosticRecommendationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (no Spring context) so these run without a database or Google credentials.
 */
@ExtendWith(MockitoExtension.class)
class DiagnosticRecommendationServiceTest {

    @Mock private ContinuityFindingRepository findingRepository;
    @Mock private DiagnosticRecommendationRepository recommendationRepository;
    @Mock private ProgressEmitter progressEmitter;
    @Mock private AiProvider openAiProvider;

    private DiagnosticRecommendationService service;

    private static final String TEAM_CODE = "2026-SEM1-IT01-01";

    @BeforeEach
    void setUp() {
        lenient().when(openAiProvider.getProviderName()).thenReturn("openai");
        service = new DiagnosticRecommendationService(
                findingRepository, recommendationRepository, progressEmitter, List.of(openAiProvider));
    }

    @Test
    void generateRecommendationsReturnsEmptyListWithoutCallingAiWhenNoFindingsExist() throws Exception {
        when(findingRepository.findByTeamCode(TEAM_CODE)).thenReturn(List.of());

        List<DiagnosticRecommendation> recommendations = service.generateRecommendations(TEAM_CODE, "openai", null);

        assertTrue(recommendations.isEmpty());
        verify(openAiProvider, never()).complete(anyString());
        verify(recommendationRepository, never()).save(any());
    }

    @Test
    void generateRecommendationsParsesAiResponseIntoSavedRecommendations() throws Exception {
        ContinuityFinding finding = new ContinuityFinding();
        finding.setId(5L);
        finding.setTeamCode(TEAM_CODE);
        finding.setSeverity(ContinuityFinding.Severity.HIGH);
        finding.setDocTypeFrom(DocType.SRS);
        finding.setDocTypeTo(DocType.SDD);
        finding.setDescription("Missing SDD coverage");

        when(findingRepository.findByTeamCode(TEAM_CODE)).thenReturn(List.of(finding));
        when(openAiProvider.complete(anyString())).thenReturn(
                "[{\"rootCause\":\"No design doc mapped\",\"recommendation\":\"Add an SDD component\",\"priority\":\"HIGH\"}]");
        when(recommendationRepository.save(any(DiagnosticRecommendation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<DiagnosticRecommendation> recommendations = service.generateRecommendations(TEAM_CODE, "openai", null);

        assertEquals(1, recommendations.size());
        DiagnosticRecommendation saved = recommendations.get(0);
        assertEquals(5L, saved.getFindingId());
        assertEquals("No design doc mapped", saved.getRootCause());
        assertEquals("Add an SDD component", saved.getRecommendation());
        assertEquals("HIGH", saved.getPriority());
        verify(recommendationRepository).deleteByFindingId(5L);
    }

    @Test
    void generateRecommendationsThrowsWhenNoProviderMatchesRequestedModel() {
        assertThrows(IllegalStateException.class,
                () -> service.generateRecommendations(TEAM_CODE, "unknown-model", null));
    }

    @Test
    void generateRecommendationsEmitsProgressErrorWhenNoProviderMatchesRequestedModel() {
        String sessionId = "session-1";

        assertThrows(IllegalStateException.class,
                () -> service.generateRecommendations(TEAM_CODE, "unknown-model", sessionId));

        verify(progressEmitter).error(eq(sessionId), anyString());
    }

    @Test
    void generateRecommendationsStripsMarkdownFencesBeforeParsingAiResponse() throws Exception {
        ContinuityFinding finding = new ContinuityFinding();
        finding.setId(9L);
        finding.setTeamCode(TEAM_CODE);
        finding.setSeverity(ContinuityFinding.Severity.HIGH);
        finding.setDocTypeFrom(DocType.SRS);
        finding.setDocTypeTo(DocType.SDD);
        finding.setDescription("Missing SDD coverage");

        when(findingRepository.findByTeamCode(TEAM_CODE)).thenReturn(List.of(finding));
        when(openAiProvider.complete(anyString())).thenReturn(
                "```json\n[{\"rootCause\":\"No design doc\",\"recommendation\":\"Add SDD\",\"priority\":\"HIGH\"}]\n```");
        when(recommendationRepository.save(any(DiagnosticRecommendation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<DiagnosticRecommendation> recommendations = service.generateRecommendations(TEAM_CODE, "openai", null);

        assertEquals(1, recommendations.size());
        assertEquals("Add SDD", recommendations.get(0).getRecommendation());
    }

    @Test
    void getRecommendationsForTeamReturnsEmptyListWhenTeamCodeBlank() {
        assertTrue(service.getRecommendationsForTeam(" ").isEmpty());
        verifyNoInteractions(findingRepository);
    }

    @Test
    void getRecommendationsForTeamReturnsRecommendationsSortedNewestFirst() {
        ContinuityFinding finding = new ContinuityFinding();
        finding.setId(7L);
        finding.setTeamCode(TEAM_CODE);

        DiagnosticRecommendation older = new DiagnosticRecommendation();
        older.setId(1L);
        older.setFindingId(7L);
        older.setCreatedAt(LocalDateTime.now().minusHours(1));

        DiagnosticRecommendation newer = new DiagnosticRecommendation();
        newer.setId(2L);
        newer.setFindingId(7L);
        newer.setCreatedAt(LocalDateTime.now());

        when(findingRepository.findByTeamCodeOrderByDetectedAtDesc(TEAM_CODE)).thenReturn(List.of(finding));
        when(recommendationRepository.findByFindingIdIn(List.of(7L))).thenReturn(List.of(older, newer));

        List<DiagnosticRecommendation> result = service.getRecommendationsForTeam(TEAM_CODE);

        assertEquals(List.of(newer, older), result);
    }
}
