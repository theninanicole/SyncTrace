package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.SmartGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SmartGoalRepository extends JpaRepository<SmartGoal, Long> {

    List<SmartGoal> findAllByOrderByCreatedAtAsc();
}
