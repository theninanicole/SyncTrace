package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.repository.EvaluationHistoryRepository;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Edge-case coverage for the team-scoping logic shared by continuity detection,
 * readiness, and audit export. Pure unit tests (no Spring context).
 */
@ExtendWith(MockitoExtension.class)
class TeamComponentResolverServiceTest {

    @Mock private TraceComponentRepository componentRepository;
    @Mock private EvaluationHistoryRepository historyRepository;

    private TeamComponentResolverService resolver;

    private static final String TEAM_CODE = "2026-SEM1-IT01-01";

    @BeforeEach
    void setUp() {
        resolver = new TeamComponentResolverService(componentRepository, historyRepository);
    }

    @Test
    void resolveTeamCodeReturnsEmptyForImplementationComponentWithMalformedName() {
        TraceComponent component = new TraceComponent();
        component.setDocType(DocType.IMPLEMENTATION);
        component.setName("orphaned-file.java"); // missing the "{teamCode} - {path}" separator

        assertTrue(resolver.resolveTeamCode(component).isEmpty());
        assertFalse(resolver.belongsToTeam(component, TEAM_CODE));
    }

    @Test
    void resolveTeamCodeReturnsEmptyForNonImplementationComponentWithoutSourceHistory() {
        TraceComponent component = new TraceComponent();
        component.setDocType(DocType.SRS);
        component.setName("Requirements Document");
        // No sourceHistoryId set — this component cannot be team-scoped at all.

        assertTrue(resolver.resolveTeamCode(component).isEmpty());
    }

    @Test
    void resolveTeamCodeReturnsEmptyWhenSourceHistoryReferenceIsDangling() {
        TraceComponent component = new TraceComponent();
        component.setDocType(DocType.SDD);
        component.setSourceHistoryId(999L);

        when(historyRepository.findById(999L)).thenReturn(Optional.empty());

        assertTrue(resolver.resolveTeamCode(component).isEmpty());
    }

    @Test
    void belongsToTeamMatchesTeamCodeCaseInsensitively() {
        TraceComponent component = new TraceComponent();
        component.setDocType(DocType.IMPLEMENTATION);
        component.setName(TEAM_CODE.toLowerCase() + " - src/main.java");

        assertTrue(resolver.belongsToTeam(component, TEAM_CODE.toUpperCase()));
    }

    @Test
    void resolveTeamCodeExtractsCodeFromEvaluationHistoryRegardlessOfDocType() {
        EvaluationHistory history = new EvaluationHistory();
        history.setId(1L);
        history.setFileName("[SPMP] G01 - " + TEAM_CODE + " | Student A");

        TraceComponent component = new TraceComponent();
        component.setDocType(DocType.SPMP);
        component.setSourceHistoryId(1L);

        when(historyRepository.findById(1L)).thenReturn(Optional.of(history));

        assertEquals(Optional.of(TEAM_CODE), resolver.resolveTeamCode(component));
    }

    @Test
    void filterByTeamReturnsEmptyListForBlankTeamCode() {
        TraceComponent component = new TraceComponent();
        component.setDocType(DocType.IMPLEMENTATION);
        component.setName(TEAM_CODE + " - src/main.java");

        assertTrue(resolver.filterByTeam(List.of(component), " ").isEmpty());
    }
}
