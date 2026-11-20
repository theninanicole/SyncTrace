package com.ieee.evaluator.synctrace.validation.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** One measured operation (M1, M2, M3, M6, M8). Created by Flyway V1. */
@Data
@Entity
@Table(name = "validation_events")
public class ValidationEvent {

    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String TIMEOUT = "timeout";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", length = 120)
    private String teamId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "metric_code", nullable = false, length = 4)
    private String metricCode;

    @Column(name = "operation", nullable = false, length = 40)
    private String operation;

    @Column(name = "repository_id")
    private Long repositoryId;

    @Column(name = "export_format", length = 8)
    private String exportFormat;

    @Column(name = "analysis_run_id", length = 64)
    private String analysisRunId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false)
    private Instant endedAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
