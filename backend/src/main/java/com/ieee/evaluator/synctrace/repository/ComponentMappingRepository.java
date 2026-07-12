package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.ComponentMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ComponentMappingRepository extends JpaRepository<ComponentMapping, Long> {

    List<ComponentMapping> findByGoalId(Long goalId);

    List<ComponentMapping> findByGoalIdIn(List<Long> goalIds);

    boolean existsByGoalIdAndComponentId(Long goalId, Long componentId);

    @Transactional
    void deleteByGoalIdAndComponentId(Long goalId, Long componentId);

    @Transactional
    void deleteByGoalId(Long goalId);

    @Transactional
    void deleteByComponentId(Long componentId);
}
