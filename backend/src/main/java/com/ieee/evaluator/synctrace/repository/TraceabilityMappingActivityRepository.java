package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.TraceabilityMappingActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TraceabilityMappingActivityRepository extends JpaRepository<TraceabilityMappingActivity, Long> {
    Optional<TraceabilityMappingActivity> findTopByTeamCodeIgnoreCaseOrderByPerformedAtDesc(String teamCode);
}