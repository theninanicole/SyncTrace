package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContinuityFindingRepository extends JpaRepository<ContinuityFinding, Long> {
    List<ContinuityFinding> findByTeamCode(String teamCode);
    List<ContinuityFinding> findByTeamCodeOrderByDetectedAtDesc(String teamCode);
    List<ContinuityFinding> findByGoalId(Long goalId);
    void deleteByTeamCode(String teamCode);
}
