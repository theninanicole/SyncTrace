package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.MappingSuggestion;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface MappingSuggestionRepository extends JpaRepository<MappingSuggestion, Long> {
    List<MappingSuggestion> findByTeamCodeIgnoreCaseAndStageAndStatusOrderByScoreDesc(
        String teamCode, String stage, MappingSuggestion.Status status);
    List<MappingSuggestion> findByTeamCodeIgnoreCaseAndStageAndStatus(
        String teamCode, String stage, MappingSuggestion.Status status);
    @Modifying
    @Transactional
    @Query("delete from MappingSuggestion s where lower(s.teamCode)=lower(:team) and s.stage=:stage and s.status=:status")
    void deletePendingByTeamAndStage(@Param("team") String team, @Param("stage") String stage,
        @Param("status") MappingSuggestion.Status status);
}
