package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.DiagnosticRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface DiagnosticRecommendationRepository extends JpaRepository<DiagnosticRecommendation, Long> {
    List<DiagnosticRecommendation> findByFindingId(Long findingId);
    List<DiagnosticRecommendation> findByFindingIdIn(List<Long> findingIds);
    @Transactional
    void deleteByFindingId(Long findingId);
}
