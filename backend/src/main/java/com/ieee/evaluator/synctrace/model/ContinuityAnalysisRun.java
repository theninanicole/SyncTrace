package com.ieee.evaluator.synctrace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "continuity_analysis_runs")
public class ContinuityAnalysisRun {

    @Id
    @Column(name = "team_code", nullable = false, length = 120)
    private String teamCode;

    @Column(nullable = false)
    private LocalDateTime lastAnalyzedAt;
}
