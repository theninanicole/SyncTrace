package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
import com.ieee.evaluator.synctrace.repository.SmartGoalRepository;
import com.ieee.evaluator.synctrace.repository.StagedTraceMappingRepository;
import com.ieee.evaluator.synctrace.repository.TraceComponentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TraceComponentPersistTest {

    @Mock private TraceComponentRepository componentRepository;
    @Mock private GoalComponentMappingRepository mappingRepository;
    @Mock private StagedTraceMappingRepository stagedMappingRepository;
    @Mock private SmartGoalRepository goalRepository;
    @Mock private ContinuityAnalysisResetService analysisResetService;
    @Mock private TeamComponentResolverService teamComponentResolver;

    private TraceComponentService service;
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new TraceComponentService(componentRepository, mappingRepository, stagedMappingRepository, goalRepository, analysisResetService, teamComponentResolver);
        when(componentRepository.save(any())).thenAnswer(inv -> {
            TraceComponent c = inv.getArgument(0);
            if (c.getId() == null) c.setId(ids.incrementAndGet());
            return c;
        });
        when(componentRepository.findAllByDocTypeAndCodeNameIgnoreCase(any(), any())).thenReturn(List.of());
        when(componentRepository.findByDocTypeAndNameIgnoreCase(any(), any())).thenReturn(Optional.empty());
    }

    private static TraceComponent diagram(Long id, String code, String content) {
        TraceComponent c = new TraceComponent();
        c.setId(id);
        c.setDocType(DocType.SDD);
        c.setArtifactKind(ArtifactKind.ACTIVITY);
        c.setCodeName(code);
        c.setName(code + " — " + content);
        c.setContent(content);
        c.setSourceHistoryId(7L);
        return c;
    }

    @Test
    void handAddedDiagramIsCreatedEvenWhenItTakesAnExistingAutoCode() {
        TraceComponent previouslyExtracted = diagram(1L, "AD-01", "* [IMG-20] - Activity Diagram: Checkout");
        when(componentRepository.findAllBySourceHistoryId(7L)).thenReturn(List.of(previouslyExtracted));

        // The hand-added diagram sits earlier in the evaluation, so it is numbered AD-01 now.
        TraceComponent added = diagram(null, "AD-01", "* [IMG-5] - Activity Diagram: Login");
        TraceComponent again = diagram(null, "AD-02", "* [IMG-20] - Activity Diagram: Checkout");

        List<TraceComponent> saved = service.persistExtractedComponents(List.of(added, again));

        assertEquals(2, saved.size());
        assertNotEquals(1L, saved.get(0).getId(), "new diagram must not merge into the old one");
        assertEquals("AD-02", saved.get(0).getCodeName());
        assertSame(previouslyExtracted, saved.get(1));
    }

    @Test
    void renamedComponentIsRecognisedOnReExtraction() {
        TraceComponent renamed = diagram(1L, "Checkout flow", "* [IMG-20] - Activity Diagram: Checkout");
        when(componentRepository.findAllBySourceHistoryId(7L)).thenReturn(List.of(renamed));

        List<TraceComponent> saved = service.persistExtractedComponents(
            List.of(diagram(null, "AD-01", "* [IMG-20] - Activity Diagram: Checkout")));

        assertSame(renamed, saved.get(0));
        assertEquals("Checkout flow", saved.get(0).getCodeName());
    }

    @Test
    void typeChangeWithinADocumentFamilyKeepsTheMappings() {
        TraceComponent component = diagram(1L, "AD-01", "* [IMG-20] - Activity Diagram: Checkout");
        component.setDocType(DocType.SRS);
        when(componentRepository.findById(1L)).thenReturn(Optional.of(component));

        TraceComponent updated = service.updateArtifactKind(1L, ArtifactKind.WIREFRAME);

        assertEquals(ArtifactKind.WIREFRAME, updated.getArtifactKind());
        assertEquals(DocType.SRS, updated.getDocType());
        verify(mappingRepository, never()).deleteByComponentId(1L);
        verify(stagedMappingRepository, never()).deleteComponentReferences(1L);
    }

    @Test
    void typeFromAnotherFamilyMovesTheComponentAndDropsItsMappings() {
        TraceComponent component = diagram(1L, "SD-01", "* [IMG-20] - Screen flow");
        component.setDocType(DocType.SDD);
        component.setArtifactKind(ArtifactKind.OTHER_SDD);
        when(componentRepository.findById(1L)).thenReturn(Optional.of(component));

        TraceComponent updated = service.updateArtifactKind(1L, ArtifactKind.WIREFRAME);

        assertEquals(DocType.SRS, updated.getDocType());
        verify(mappingRepository).deleteByComponentId(1L);
        verify(stagedMappingRepository).deleteComponentReferences(1L);
    }

    @Test
    void retiredTypesCannotBeAssigned() {
        TraceComponent component = diagram(1L, "AD-01", "* [IMG-20] - Activity Diagram: Checkout");
        when(componentRepository.findById(1L)).thenReturn(Optional.of(component));

        assertThrows(IllegalArgumentException.class,
            () -> service.updateArtifactKind(1L, ArtifactKind.CONTEXT_DIAGRAM));
        assertThrows(IllegalArgumentException.class,
            () -> service.createComponent(DocType.SRS, ArtifactKind.OTHER_SRS, "CTX-01", null));
    }

    @Test
    void teamsCanAddComponentsWithTheSameNameWithoutSharingThem() {
        TraceComponent created = service.createComponent(
            DocType.SRS, ArtifactKind.USE_CASE, "Login", "Login", null, null, "2627-SEM1-IT411-08");

        assertEquals("team:2627-SEM1-IT411-08", created.getSourceRef());
        verify(componentRepository, never()).findByDocTypeAndNameIgnoreCase(any(), any());
        verify(componentRepository).findFirstByDocTypeAndNameIgnoreCaseAndSourceRefIgnoreCase(
            DocType.SRS, "Login", "team:2627-SEM1-IT411-08");
    }

    @Test
    void documentComponentsCannotBecomeImplementationTypes() {
        TraceComponent component = diagram(1L, "AD-01", "* [IMG-20] - Activity Diagram: Checkout");
        when(componentRepository.findById(1L)).thenReturn(Optional.of(component));

        assertThrows(IllegalArgumentException.class,
            () -> service.updateArtifactKind(1L, ArtifactKind.IMPL_OO));
        assertThrows(IllegalArgumentException.class,
            () -> service.updateArtifactKind(1L, ArtifactKind.UNSPECIFIED));
    }

    @Test
    void correctedTypeSurvivesReExtraction() {
        TraceComponent corrected = diagram(1L, "SD-01", "* [IMG-20] - Screen flow");
        corrected.setDocType(DocType.SRS);
        corrected.setArtifactKind(ArtifactKind.WIREFRAME);
        when(componentRepository.findAllBySourceHistoryId(7L)).thenReturn(List.of(corrected));

        // The extractor still classifies this finding as SDD.
        TraceComponent candidate = diagram(null, "SD-01", "* [IMG-20] - Screen flow");
        candidate.setArtifactKind(ArtifactKind.OTHER_SDD);
        List<TraceComponent> saved = service.persistExtractedComponents(List.of(candidate));

        assertSame(corrected, saved.get(0), "must not create a duplicate in the old library");
        assertEquals(ArtifactKind.WIREFRAME, saved.get(0).getArtifactKind());
    }

    @Test
    void reportsEachCandidateAsItIsProcessed() {
        when(componentRepository.findAllBySourceHistoryId(7L)).thenReturn(List.of());
        List<Integer> seen = new java.util.ArrayList<>();

        service.persistExtractedComponents(List.of(
            diagram(null, "AD-01", "* [IMG-1] - Activity Diagram: A"),
            diagram(null, "AD-02", "* [IMG-2] - Activity Diagram: B")), seen::add);

        assertEquals(List.of(0, 1), seen);
    }
}
