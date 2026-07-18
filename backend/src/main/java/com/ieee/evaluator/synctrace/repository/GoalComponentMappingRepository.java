package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.GoalComponentMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GoalComponentMappingRepository extends JpaRepository<GoalComponentMapping, Long> {

    @Query("select m from GoalComponentMapping m join fetch m.component where m.goal.id = :goalId order by m.id asc")
    List<GoalComponentMapping> findByGoalIdWithComponents(@Param("goalId") Long goalId);

    @Query("select m from GoalComponentMapping m join fetch m.component where m.goal.id in :goalIds order by m.goal.id asc, m.id asc")
    List<GoalComponentMapping> findByGoalIdsWithComponents(@Param("goalIds") List<Long> goalIds);

    Optional<GoalComponentMapping> findByGoalIdAndComponentId(Long goalId, Long componentId);

    void deleteByGoalId(Long goalId);

    void deleteByComponentId(Long componentId);
}
