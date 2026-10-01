package com.ieee.evaluator.synctrace.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One link in a team's shared staged mapping chain (e.g. goal → UC-01 in PROPOSAL_SRS,
 * UC-01 → CL-02 in SRS_SDD). For the PROPOSAL_SRS stage sourceId is a SmartGoal id;
 * in every other stage both ends are TraceComponent ids.
 */
@Data
@Entity
@Table(name = "staged_trace_mappings", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"team_code", "stage", "source_id", "target_id"})
})
public class StagedTraceMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_code", nullable = false)
    private String teamCode;

    @Column(name = "stage", nullable = false, length = 40)
    private String stage;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
