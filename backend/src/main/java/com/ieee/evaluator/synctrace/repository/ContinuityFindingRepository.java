package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.ContinuityFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ContinuityFindingRepository extends JpaRepository<ContinuityFinding, Long> {
    List<ContinuityFinding> findByTeamCode(String teamCode);
    List<ContinuityFinding> findByTeamCodeIgnoreCase(String teamCode);
    List<ContinuityFinding> findByTeamCodeOrderByDetectedAtDesc(String teamCode);
    // Team codes arrive in whatever case the roster / GitHub sheet used, so every
    // team-scoped lookup must ignore case or findings "disappear" between pages.
    List<ContinuityFinding> findByTeamCodeIgnoreCaseOrderByDetectedAtDescIdAsc(String teamCode);
    List<ContinuityFinding> findByGoalId(Long goalId);
    @Transactional
    void deleteByTeamCode(String teamCode);
}
