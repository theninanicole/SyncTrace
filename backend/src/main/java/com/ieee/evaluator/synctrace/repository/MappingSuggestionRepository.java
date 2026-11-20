package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.MappingSuggestion;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface MappingSuggestionRepository extends JpaRepository<MappingSuggestion, Long> {
    List<MappingSuggestion> findByTeamCodeIgnoreCaseAndStageAndStatusOrderByScoreDesc(
        String teamCode, String stage, MappingSuggestion.Status status);
    List<MappingSuggestion> findByTeamCodeIgnoreCaseAndStageAndStatus(
        String teamCode, String stage, MappingSuggestion.Status status);

    /**
     * Retires the team's still-pending suggestions for a stage without deleting them (they stay
     * PENDING for the M5 record, with withdrawnAt/withdrawnReason set).
     */
    @Modifying
    @Transactional
    @Query("update MappingSuggestion s set s.withdrawnAt = :at, s.withdrawnReason = :reason "
        + "where lower(s.teamCode) = lower(:team) and s.stage = :stage "
        + "and s.status = com.ieee.evaluator.synctrace.model.MappingSuggestion.Status.PENDING "
        + "and s.withdrawnAt is null")
    int markPendingWithdrawn(@Param("team") String team, @Param("stage") String stage,
        @Param("reason") String reason, @Param("at") LocalDateTime at);

    /** Validation export / summary: every suggestion generated in a window. */
    List<MappingSuggestion> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(
        LocalDateTime from, LocalDateTime to);

    List<MappingSuggestion> findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(String teamCode);
}
