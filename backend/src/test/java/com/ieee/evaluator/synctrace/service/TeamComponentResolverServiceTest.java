package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.model.EvaluationHistory;
import com.ieee.evaluator.model.EvaluationHistoryFileNameDTO;
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
import java.util.Set;

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
    void findTeamComponentsReturnsEmptyListForBlankTeamCode() {
        TraceComponent component = new TraceComponent();
        component.setDocType(DocType.IMPLEMENTATION);
        component.setName(TEAM_CODE + " - src/main.java");

        assertTrue(resolver.findTeamComponents(" ").isEmpty());
    }

    @Test
    void findTeamComponentsKeepsOnlyTheFileNamesAndComponentsTheTeamOwns() {
        String lowerCaseCode = TEAM_CODE.toLowerCase();
        when(historyRepository.findFileNamesContaining(lowerCaseCode)).thenReturn(List.of(
            new EvaluationHistoryFileNameDTO(1L, "[SDD] G01 - " + TEAM_CODE + " | Name"),
            // Contains the code as a substring, but it is a different team's code.
            new EvaluationHistoryFileNameDTO(2L, "[SDD] G02 - " + TEAM_CODE + "1 | Name")));

        TraceComponent evaluated = historyComponent(1L);
        TraceComponent otherHistory = historyComponent(2L);
        TraceComponent ownCode = codeComponent(TEAM_CODE + " - src/App.java");
        TraceComponent mentionsTeam = codeComponent("2026-SEM1-IT01-02 - docs/" + TEAM_CODE + ".md");
        when(componentRepository.findTeamCandidates(Set.of(1L), lowerCaseCode))
            .thenReturn(List.of(evaluated, otherHistory, ownCode, mentionsTeam));

        assertEquals(List.of(evaluated, ownCode), resolver.findTeamComponents(lowerCaseCode));
    }

    @Test
    void manualComponentBelongsToTheTeamInItsSourceRefNotTheOneItsNameMentions() {
        TraceComponent manual = codeComponent("2026-SEM1-IT01-02 - notes.md");
        manual.setSourceRef(TeamComponentResolverService.manualTeamRef(TEAM_CODE));

        assertTrue(resolver.belongsToTeam(manual, TEAM_CODE));
        assertFalse(resolver.belongsToTeam(manual, "2026-SEM1-IT01-02"));
    }

    private static TraceComponent historyComponent(Long historyId) {
        TraceComponent component = new TraceComponent();
        component.setDocType(DocType.SDD);
        component.setSourceHistoryId(historyId);
        return component;
    }

    private static TraceComponent codeComponent(String name) {
        TraceComponent component = new TraceComponent();
        component.setDocType(DocType.IMPLEMENTATION);
        component.setName(name);
        return component;
    }
}
