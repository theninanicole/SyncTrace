package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.StagedTraceMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StagedTraceMappingRepository extends JpaRepository<StagedTraceMapping, Long> {
    List<StagedTraceMapping> findByTeamCodeIgnoreCaseOrderByCreatedAtAscIdAsc(String teamCode);

    /** Component ids appear as targets in every stage and as sources in every stage but PROPOSAL_SRS. */
    @Modifying
    @Query("DELETE FROM StagedTraceMapping m WHERE m.targetId = :componentId "
        + "OR (m.sourceId = :componentId AND m.stage <> 'PROPOSAL_SRS')")
    void deleteComponentReferences(@Param("componentId") Long componentId);

    @Modifying
    @Query("DELETE FROM StagedTraceMapping m WHERE m.stage = 'PROPOSAL_SRS' AND m.sourceId = :goalId")
    void deleteGoalReferences(@Param("goalId") Long goalId);
}
