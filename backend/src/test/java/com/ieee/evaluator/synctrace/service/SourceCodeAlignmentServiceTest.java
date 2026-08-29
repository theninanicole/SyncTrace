package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.service.AiProvider;
import com.ieee.evaluator.service.ProgressEmitter;
import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.ContinuityFindingRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (no Spring context) so these run without a database or Google credentials.
 */
@ExtendWith(MockitoExtension.class)
class SourceCodeAlignmentServiceTest {

    @Mock private TraceComponentRepository componentRepository;
    @Mock private ContinuityFindingRepository findingRepository;
    @Mock private ProgressEmitter progressEmitter;
    @Mock private TeamComponentResolverService teamComponentResolver;
    @Mock private AiProvider openAiProvider;

    private SourceCodeAlignmentService service;

    private static final String TEAM_CODE = "2026-SEM1-IT01-01";

    @BeforeEach
    void setUp() {
        lenient().when(openAiProvider.getProviderName()).thenReturn("openai");
        service = new SourceCodeAlignmentService(
                componentRepository, findingRepository, progressEmitter, teamComponentResolver, List.of(openAiProvider));
    }

    @Test
    void analyzeAlignmentSkipsAiWhenNoSddComponentsExistForTeam() throws Exception {
        TraceComponent impl = new TraceComponent();
        impl.setDocType(DocType.IMPLEMENTATION);

        when(componentRepository.findAll()).thenReturn(List.of(impl));
        when(teamComponentResolver.filterByTeam(any(), eq(TEAM_CODE))).thenReturn(List.of(impl));

        List<ContinuityFinding> findings = service.analyzeAlignment(TEAM_CODE, "openai", null);

        assertTrue(findings.isEmpty());
        verify(openAiProvider, never()).complete(anyString());
    }

    @Test
    void analyzeAlignmentStripsMarkdownFencesBeforeParsingAiResponse() throws Exception {
        TraceComponent sdd = new TraceComponent();
        sdd.setDocType(DocType.SDD);
        sdd.setName("Design Document");
        sdd.setContent("SDD content");

        TraceComponent impl = new TraceComponent();
        impl.setDocType(DocType.IMPLEMENTATION);
        impl.setName("Implementation");
        impl.setContent("public class Example {}");

        when(componentRepository.findAll()).thenReturn(List.of(sdd, impl));
        when(teamComponentResolver.filterByTeam(any(), eq(TEAM_CODE))).thenReturn(List.of(sdd, impl));
        when(openAiProvider.complete(anyString())).thenReturn(
                "```json\n[{\"description\":\"Missing validation logic\",\"severity\":\"HIGH\"}]\n```");
        when(findingRepository.save(any(ContinuityFinding.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<ContinuityFinding> findings = service.analyzeAlignment(TEAM_CODE, "openai", null);

        assertEquals(1, findings.size());
        assertEquals("Missing validation logic", findings.get(0).getDescription());
        assertEquals(ContinuityFinding.Severity.HIGH, findings.get(0).getSeverity());
        assertEquals(TEAM_CODE, findings.get(0).getTeamCode());
    }

    @Test
    void analyzeAlignmentEmitsProgressErrorWhenNoProviderMatchesRequestedModel() {
        String sessionId = "session-1";

        assertThrows(IllegalStateException.class,
                () -> service.analyzeAlignment(TEAM_CODE, "unknown-model", sessionId));

        verify(progressEmitter).error(eq(sessionId), anyString());
    }
}
