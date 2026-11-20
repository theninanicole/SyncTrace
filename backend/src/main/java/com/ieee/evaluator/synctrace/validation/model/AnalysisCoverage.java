package com.ieee.evaluator.synctrace.validation.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** Gap explanation coverage of one continuity analysis run (M7). */
@Data
@Entity
@Table(name = "validation_analysis_coverage")
public class AnalysisCoverage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "analysis_run_id", nullable = false, length = 64)
    private String analysisRunId;

    @Column(name = "team_id", nullable = false, length = 120)
    private String teamId;

    @Column(name = "gaps_detected", nullable = false)
    private int gapsDetected;

    @Column(name = "gaps_provisional", nullable = false)
    private int gapsProvisional;

    @Column(name = "gaps_with_explanation", nullable = false)
    private int gapsWithExplanation;

    @Column(name = "gaps_with_recommendation", nullable = false)
    private int gapsWithRecommendation;

    @Column(name = "gaps_with_both", nullable = false)
    private int gapsWithBoth;

    @Column(name = "generation_failures", nullable = false)
    private int generationFailures;

    @Column(name = "recommendation_status", nullable = false, length = 10)
    private String recommendationStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Gaps that count toward coverage (detected minus provisional). */
    public int countedGaps() {
        return Math.max(0, gapsDetected - gapsProvisional);
    }
}
