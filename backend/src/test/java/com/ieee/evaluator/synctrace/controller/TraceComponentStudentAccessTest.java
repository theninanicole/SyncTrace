package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.service.SyncTraceAccessGuard;
import com.ieee.evaluator.synctrace.service.TeamComponentResolverService;
import com.ieee.evaluator.synctrace.service.TraceComponentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TraceComponentStudentAccessTest {

    private static final String TEAM_CODE = "2627-SEM1-IT411-08";
    private static final Long COMPONENT_ID = 7L;
    private static final Map<String, String> RETYPE = Map.of("artifactKind", "SEQUENCE");

    @Mock private TraceComponentService componentService;
    @Mock private TeamComponentResolverService teamComponentResolver;
    @Mock private SyncTraceAccessGuard accessGuard;

    private TraceComponentController controller;
    private TraceComponent component;

    @BeforeEach
    void setUp() {
        controller = new TraceComponentController(componentService, null, teamComponentResolver, accessGuard, null);
        component = new TraceComponent();
        component.setAiExtracted(true);
        when(accessGuard.isStudent()).thenReturn(true);
        when(accessGuard.resolveEffectiveTeamCode(null)).thenReturn(TEAM_CODE);
        when(componentService.getComponentById(COMPONENT_ID)).thenReturn(Optional.of(component));
        when(teamComponentResolver.belongsToTeam(component, TEAM_CODE)).thenReturn(true);
    }

    @Test
    void studentCanRetypeTheirTeamsExtractedComponent() {
        ResponseEntity<?> response = controller.renameTraceComponent(COMPONENT_ID, RETYPE);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(componentService).updateArtifactKind(COMPONENT_ID, ArtifactKind.SEQUENCE);
    }

    @Test
    void studentCannotRetypeAManualComponent() {
        component.setAiExtracted(false);

        ResponseEntity<?> response = controller.renameTraceComponent(COMPONENT_ID, RETYPE);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(componentService, never()).updateArtifactKind(any(), any());
    }

    @Test
    void studentCannotRetypeAnotherTeamsComponent() {
        when(teamComponentResolver.belongsToTeam(component, TEAM_CODE)).thenReturn(false);

        ResponseEntity<?> response = controller.renameTraceComponent(COMPONENT_ID, RETYPE);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(componentService, never()).updateArtifactKind(any(), any());
    }

    @Test
    void forbiddenRetypeDoesNotApplyARenameSentWithIt() {
        when(teamComponentResolver.belongsToTeam(component, TEAM_CODE)).thenReturn(false);

        controller.renameTraceComponent(COMPONENT_ID, Map.of("artifactKind", "SEQUENCE", "name", "Renamed"));

        verify(componentService, never()).renameComponent(eq(COMPONENT_ID), any());
    }

    @Test
    void studentCanRenameTheirTeamsComponent() {
        component.setAiExtracted(false);

        ResponseEntity<?> response = controller.renameTraceComponent(COMPONENT_ID, Map.of("codeName", "UC-09"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(componentService).updateCodeName(COMPONENT_ID, "UC-09");
    }

    @Test
    void studentCannotRenameAnotherTeamsComponent() {
        when(teamComponentResolver.belongsToTeam(component, TEAM_CODE)).thenReturn(false);

        ResponseEntity<?> response = controller.renameTraceComponent(COMPONENT_ID, Map.of("codeName", "UC-09"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(componentService, never()).updateCodeName(any(), any());
    }

    @Test
    void studentCannotChangeTheNameThatCarriesTheTeamCode() {
        ResponseEntity<?> response = controller.renameTraceComponent(
            COMPONENT_ID, Map.of("name", "2627-SEM1-IT411-09 - src/App.java"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(componentService, never()).renameComponent(any(), any());
    }

    @Test
    void studentsAddComponentsToTheirOwnTeamWhateverTheRequestSays() {
        when(accessGuard.resolveEffectiveTeamCode("2627-SEM1-IT411-09")).thenReturn(TEAM_CODE);

        ResponseEntity<?> response = controller.createTraceComponent(Map.of(
            "docType", "SRS", "name", "Login", "content", "Login", "artifactKind", "USE_CASE",
            "teamCode", "2627-SEM1-IT411-09"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(componentService).createComponent(
            TraceComponent.DocType.SRS, ArtifactKind.USE_CASE, "Login", "Login", null, null, TEAM_CODE);
    }

    @Test
    void studentWithoutATeamCannotAddComponents() {
        when(accessGuard.resolveEffectiveTeamCode(null))
            .thenThrow(new SyncTraceAccessGuard.AccessDeniedException("No team is on file for this student."));

        ResponseEntity<?> response = controller.createTraceComponent(Map.of(
            "docType", "SRS", "name", "Login", "content", "Login", "artifactKind", "USE_CASE"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void studentCanDeleteTheirTeamsComponent() {
        component.setAiExtracted(false);

        ResponseEntity<?> response = controller.deleteTraceComponent(COMPONENT_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(componentService).deleteComponent(COMPONENT_ID);
    }

    @Test
    void studentCannotDeleteAnotherTeamsComponent() {
        when(teamComponentResolver.belongsToTeam(component, TEAM_CODE)).thenReturn(false);

        ResponseEntity<?> response = controller.deleteTraceComponent(COMPONENT_ID);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(componentService, never()).deleteComponent(any());
    }

    @Test
    void teacherIsNotLimitedToATeam() {
        when(accessGuard.isStudent()).thenReturn(false);
        when(teamComponentResolver.belongsToTeam(component, TEAM_CODE)).thenReturn(false);

        assertEquals(HttpStatus.OK, controller.deleteTraceComponent(COMPONENT_ID).getStatusCode());
        verify(componentService).deleteComponent(COMPONENT_ID);
    }
}
