package com.ieee.evaluator.synctrace.validation.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/** All-time summary of one metric for one team, refreshed after each recorded event. */
@Data
@Entity
@Table(name = "validation_team_summary")
public class TeamSummaryRow {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "team_id", nullable = false, length = 120)
        private String teamId;
        @Column(name = "metric_code", nullable = false, length = 4)
        private String metricCode;
        /** '' for the whole metric; M4 also gets one row per stage. */
        @Column(name = "stage", nullable = false, length = 20)
        private String stage;
    }

    @EmbeddedId
    private Key key;

    @Column(name = "count", nullable = false)
    private int count;

    @Column(name = "median_ms")
    private Long medianMs;

    @Column(name = "max_ms")
    private Long maxMs;

    @Column(name = "min_ms")
    private Long minMs;

    @Column(name = "pct_within_target")
    private Double pctWithinTarget;

    @Column(name = "target_met")
    private Boolean targetMet;

    private Integer accepted;
    private Integer edited;
    private Integer rejected;
    private Integer pending;

    @Column(name = "acceptance_rate")
    private Double acceptanceRate;

    @Column(name = "coverage_pct")
    private Double coveragePct;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;
}
