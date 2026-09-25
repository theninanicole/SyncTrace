package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.ContinuityAnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContinuityAnalysisRunRepository extends JpaRepository<ContinuityAnalysisRun, String> {
    Optional<ContinuityAnalysisRun> findByTeamCodeIgnoreCase(String teamCode);
}
