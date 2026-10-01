package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.TraceComponent;
import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import com.ieee.evaluator.synctrace.repository.GoalComponentMappingRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TraceComponentPersistTest {

    @Mock private TraceComponentRepository componentRepository;
    @Mock private GoalComponentMappingRepository mappingRepository;
    @Mock private StagedTraceMappingRepository stagedMappingRepository;

    private TraceComponentService service;
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new TraceComponentService(componentRepository, mappingRepository, stagedMappingRepository, null, null);
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
}
