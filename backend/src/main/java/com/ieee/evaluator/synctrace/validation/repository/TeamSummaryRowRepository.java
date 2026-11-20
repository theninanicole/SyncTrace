package com.ieee.evaluator.synctrace.validation.repository;

import com.ieee.evaluator.synctrace.validation.model.TeamSummaryRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamSummaryRowRepository extends JpaRepository<TeamSummaryRow, TeamSummaryRow.Key> {
    @Modifying
    @Query("delete from TeamSummaryRow r where lower(r.key.teamId) = lower(:team)")
    int deleteByTeam(@Param("team") String team);
}
