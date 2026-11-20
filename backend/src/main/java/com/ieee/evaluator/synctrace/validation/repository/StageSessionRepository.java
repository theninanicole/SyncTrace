package com.ieee.evaluator.synctrace.validation.repository;

import com.ieee.evaluator.synctrace.validation.model.StageSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StageSessionRepository extends JpaRepository<StageSession, Long> {
    Optional<StageSession> findFirstByTeamIdIgnoreCaseAndStageAndFinalizedAtIsNullOrderByIdDesc(
        String teamId, String stage);

    boolean existsByTeamIdIgnoreCaseAndStage(String teamId, String stage);

    List<StageSession> findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscIdAsc(
        Instant from, Instant to);

    List<StageSession> findByTeamIdIgnoreCaseOrderByStartedAtAscIdAsc(String teamId);
}
