package com.ieee.evaluator.synctrace.validation.service;

import com.ieee.evaluator.synctrace.model.StagedTraceMapping;
import com.ieee.evaluator.synctrace.repository.StagedTraceMappingRepository;
import com.ieee.evaluator.synctrace.service.MappingSuggestionService;
import com.ieee.evaluator.synctrace.validation.config.ValidationProperties;
import com.ieee.evaluator.synctrace.validation.model.StageSession;
import com.ieee.evaluator.synctrace.validation.repository.StageSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * M4 stage sessions. Rules (docs/validation-metrics.md):
 * <ul>
 *   <li>The first visible heartbeat for a team's stage opens its first session.</li>
 *   <li>Heartbeats and link changes add active time (gaps over the idle threshold add nothing).</li>
 *   <li>Save Mapping with the stage selected finalizes the open session.</li>
 *   <li>A link change in a stage with no open session (e.g. after finalizing) opens a NEW
 *       session; heartbeats alone never reopen a finalized stage.</li>
 * </ul>
 * Calls arrive one at a time on the validation-metrics thread, so updates never race.
 */
@Slf4j
@Service
public class StageSessionService {

    private final StageSessionRepository sessionRepository;
    private final StagedTraceMappingRepository stagedRepository;
    private final MappingSuggestionService mappingSuggestionService;
    private final ValidationProperties properties;

    public StageSessionService(StageSessionRepository sessionRepository,
            StagedTraceMappingRepository stagedRepository,
            MappingSuggestionService mappingSuggestionService,
            ValidationProperties properties) {
        this.sessionRepository = sessionRepository;
        this.stagedRepository = stagedRepository;
        this.mappingSuggestionService = mappingSuggestionService;
        this.properties = properties;
    }

    @Transactional
    public void heartbeat(String teamId, String stageCode, boolean visible, Instant at) {
        Optional<StageSession> open = findOpen(teamId, stageCode);
        if (open.isEmpty()) {
            if (visible && !sessionRepository.existsByTeamIdIgnoreCaseAndStage(teamId, stageCode)) {
                sessionRepository.save(newSession(teamId, stageCode, at));
            }
            return;
        }
        StageSession session = open.get();
        apply(session, at, visible);
        sessionRepository.save(session);
    }

    /** A link was added, removed, accepted or edited in this stage. */
    @Transactional
    public void linkChanged(String teamId, String stageCode, Instant at) {
        Optional<StageSession> open = findOpen(teamId, stageCode);
        if (open.isEmpty()) {
            sessionRepository.save(newSession(teamId, stageCode, at));
            return;
        }
        StageSession session = open.get();
        apply(session, at, true);
        sessionRepository.save(session);
    }

    /** Save Mapping with this stage selected. Returns the finalized session, if one was open. */
    @Transactional
    public Optional<StageSession> finalizeStage(String teamId, String stageCode, String userId, Instant at) {
        Optional<StageSession> open = findOpen(teamId, stageCode);
        if (open.isEmpty()) return Optional.empty();

        StageSession session = open.get();
        apply(session, at, false);
        session.setFinalizedAt(at);
        session.setWallClockMs(Math.max(0, Duration.between(session.getStartedAt(), at).toMillis()));
        session.setFinalizedBy(userId);

        String stageKey = StageKeys.toKey(stageCode);
        List<StagedTraceMapping> stageLinks = stagedRepository.findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(teamId)
            .stream().filter(link -> stageKey.equals(link.getStage())).toList();
        session.setLinksSaved(stageLinks.size());
        session.setUnmappedSourcesAtFinalize(unmappedSources(teamId, stageKey, stageLinks));
        return Optional.of(sessionRepository.save(session));
    }

    private Integer unmappedSources(String teamId, String stageKey, List<StagedTraceMapping> stageLinks) {
        try {
            Set<Long> mapped = new HashSet<>();
            stageLinks.forEach(link -> mapped.add(link.getSourceId()));
            return (int) mappingSuggestionService.sourceIds(teamId, stageKey).stream()
                .filter(id -> !mapped.contains(id))
                .count();
        } catch (RuntimeException e) {
            log.warn("Could not count unmapped sources for {} {}: {}", teamId, stageKey, e.getMessage());
            return null;
        }
    }

    private void apply(StageSession session, Instant at, boolean stillVisible) {
        ActiveTime.Step step = ActiveTime.accumulate(session.getLastHeartbeatAt(), at, properties.idleGapMs(), stillVisible);
        session.setActiveMs(session.getActiveMs() + step.addMs());
        session.setLastHeartbeatAt(step.newLast());
    }

    private Optional<StageSession> findOpen(String teamId, String stageCode) {
        return sessionRepository.findFirstByTeamIdIgnoreCaseAndStageAndFinalizedAtIsNullOrderByIdDesc(teamId, stageCode);
    }

    private static StageSession newSession(String teamId, String stageCode, Instant at) {
        StageSession session = new StageSession();
        session.setTeamId(teamId);
        session.setStage(stageCode);
        session.setStartedAt(at);
        session.setLastHeartbeatAt(at);
        session.setActiveMs(0);
        session.setCreatedAt(at);
        return session;
    }
}
