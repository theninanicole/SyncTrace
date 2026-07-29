package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.SmartGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SmartGoalRepository extends JpaRepository<SmartGoal, Long> {
    Optional<SmartGoal> findByDescriptionIgnoreCase(String description);
    List<SmartGoal> findAllByOrderByCreatedAtDesc();
    List<SmartGoal> findByTeamCodeIgnoreCaseOrderByCreatedAtDesc(String teamCode);
}
