package com.ieee.evaluator.synctrace.validation.repository;

import com.ieee.evaluator.synctrace.validation.model.AnalysisCoverage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AnalysisCoverageRepository extends JpaRepository<AnalysisCoverage, Long> {
    List<AnalysisCoverage> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(
        Instant from, Instant to);

    List<AnalysisCoverage> findByTeamIdIgnoreCaseOrderByCreatedAtAscIdAsc(String teamId);
}
