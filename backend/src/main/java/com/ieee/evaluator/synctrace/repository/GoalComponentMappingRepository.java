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
    List<GoalComponentMapping> findByGoalId(Long goalId);
    Optional<GoalComponentMapping> findByGoalIdAndComponentId(Long goalId, Long componentId);
    void deleteByComponentId(Long componentId);
    void deleteByGoalId(Long goalId);
    
    @Query("SELECT m.goalId, c.docType FROM GoalComponentMapping m JOIN TraceComponent c ON m.componentId = c.id WHERE m.goalId IN :goalIds")
    List<Object[]> findDocTypesByGoalIds(@Param("goalIds") List<Long> goalIds);
}