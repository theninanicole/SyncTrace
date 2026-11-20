package com.ieee.evaluator.synctrace.validation;

import com.ieee.evaluator.synctrace.model.StagedTraceMapping;
import com.ieee.evaluator.synctrace.repository.StagedTraceMappingRepository;
import com.ieee.evaluator.synctrace.service.MappingSuggestionService;
import com.ieee.evaluator.synctrace.validation.config.ValidationProperties;
import com.ieee.evaluator.synctrace.validation.model.StageSession;
import com.ieee.evaluator.synctrace.validation.repository.StageSessionRepository;
import com.ieee.evaluator.synctrace.validation.service.StageSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StageSessionServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-25T01:00:00Z");
    private final List<StageSession> store = new ArrayList<>();
    private StageSessionService service;

    @BeforeEach
    void setUp() {
        StageSessionRepository repository = mock(StageSessionRepository.class);
        when(repository.findFirstByTeamIdIgnoreCaseAndStageAndFinalizedAtIsNullOrderByIdDesc(anyString(), anyString()))
            .thenAnswer(inv -> store.stream()
                .filter(s -> s.getTeamId().equalsIgnoreCase(inv.getArgument(0)))
                .filter(s -> s.getStage().equals(inv.getArgument(1)))
                .filter(s -> s.getFinalizedAt() == null)
                .reduce((first, second) -> second));
        when(repository.existsByTeamIdIgnoreCaseAndStage(anyString(), anyString()))
            .thenAnswer(inv -> store.stream().anyMatch(s -> s.getTeamId().equalsIgnoreCase(inv.getArgument(0))
                && s.getStage().equals(inv.getArgument(1))));
        when(repository.save(any(StageSession.class))).thenAnswer(inv -> {
            StageSession session = inv.getArgument(0);
            if (session.getId() == null) {
                session.setId((long) store.size() + 1);
                store.add(session);
            }
            return session;
        });

        StagedTraceMappingRepository staged = mock(StagedTraceMappingRepository.class);
        when(staged.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc("TEAM")).thenReturn(List.of(
            link("PROPOSAL_SRS", 1), link("PROPOSAL_SRS", 1), link("SRS_SDD", 10)));
        MappingSuggestionService suggestions = mock(MappingSuggestionService.class);
        when(suggestions.sourceIds("TEAM", "PROPOSAL_SRS")).thenReturn(List.of(1L, 2L, 3L));

        service = new StageSessionService(repository, staged, suggestions, ValidationProperties.defaults());
    }

    private static StagedTraceMapping link(String stage, long source) {
        StagedTraceMapping link = new StagedTraceMapping();
        link.setTeamCode("TEAM");
        link.setStage(stage);
        link.setSourceId(source);
        link.setTargetId(source + 100);
        return link;
    }

    private static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    @Test
    void firstVisibleHeartbeatOpensTheFirstSession() {
        service.heartbeat("TEAM", "proposal_srs", false, at(0));
        assertTrue(store.isEmpty(), "a hidden beat never opens a session");

        service.heartbeat("TEAM", "proposal_srs", true, at(0));
        assertEquals(1, store.size());
        assertEquals(at(0), store.get(0).getStartedAt());
        assertNull(store.get(0).getFinalizedAt());
    }

    @Test
    void finalizeStoresWallClockActiveTimeLinksAndUnmappedSources() {
        service.heartbeat("TEAM", "proposal_srs", true, at(0));
        service.heartbeat("TEAM", "proposal_srs", true, at(30));
        service.heartbeat("TEAM", "proposal_srs", true, at(60));
        service.heartbeat("TEAM", "proposal_srs", true, at(400)); // 5:40 gap: idle
        Optional<StageSession> finalized = service.finalizeStage("TEAM", "proposal_srs", "user-9", at(430));

        StageSession session = finalized.orElseThrow();
        assertEquals(at(430), session.getFinalizedAt());
        assertEquals(430_000, session.getWallClockMs());
        assertEquals(90_000, session.getActiveMs()); // 0→60 plus 400→430
        assertEquals("user-9", session.getFinalizedBy());
        assertEquals(2, session.getLinksSaved()); // two PROPOSAL_SRS links
        assertEquals(2, session.getUnmappedSourcesAtFinalize()); // sources 2 and 3 have no link
        assertNull(session.getLastHeartbeatAt());
    }

    @Test
    void reopeningAFinalizedStageCreatesANewRowAndKeepsTheOldOne() {
        service.heartbeat("TEAM", "proposal_srs", true, at(0));
        service.finalizeStage("TEAM", "proposal_srs", "u", at(60));
        StageSession first = store.get(0);

        service.heartbeat("TEAM", "proposal_srs", true, at(90));
        assertEquals(1, store.size(), "looking at a finalized stage does not reopen it");

        service.linkChanged("TEAM", "proposal_srs", at(120));
        assertEquals(2, store.size());
        assertEquals(at(60), first.getFinalizedAt(), "the finalized row is never overwritten");
        StageSession reopened = store.get(1);
        assertEquals(at(120), reopened.getStartedAt());
        assertNull(reopened.getFinalizedAt());

        service.heartbeat("TEAM", "proposal_srs", true, at(150));
        service.finalizeStage("TEAM", "proposal_srs", "u", at(170));
        assertEquals(50_000, reopened.getActiveMs());
        assertEquals(50_000, reopened.getWallClockMs());
    }

    @Test
    void saveWithNoOpenSessionFinalizesNothing() {
        assertTrue(service.finalizeStage("TEAM", "srs_sdd", "u", at(0)).isEmpty());
        assertTrue(store.isEmpty());
    }

    @Test
    void stagesAreTrackedIndependently() {
        service.heartbeat("TEAM", "proposal_srs", true, at(0));
        service.heartbeat("TEAM", "srs_sdd", true, at(10));
        service.finalizeStage("TEAM", "srs_sdd", "u", at(40));
        assertNull(store.get(0).getFinalizedAt());
        assertNotNull(store.get(1).getFinalizedAt());
    }
}
