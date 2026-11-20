package com.ieee.evaluator.synctrace.validation.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** One working session on a mapping stage (M4). Reopening a finalized stage inserts a new row. */
@Data
@Entity
@Table(name = "validation_stage_sessions")
public class StageSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false, length = 120)
    private String teamId;

    /** proposal_srs | srs_sdd | srs_std | srs_spmp | sdd_impl */
    @Column(name = "stage", nullable = false, length = 20)
    private String stage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "wall_clock_ms")
    private Long wallClockMs;

    @Column(name = "active_ms", nullable = false)
    private long activeMs;

    /** Internal marker of the open active interval; null while the tab is hidden or idle. */
    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "finalized_by", length = 64)
    private String finalizedBy;

    @Column(name = "links_saved")
    private Integer linksSaved;

    @Column(name = "unmapped_sources_at_finalize")
    private Integer unmappedSourcesAtFinalize;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
