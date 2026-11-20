package com.ieee.evaluator.synctrace.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "mapping_suggestions")
public class MappingSuggestion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "team_code", nullable = false) private String teamCode;
    @Column(nullable = false, length = 40) private String stage;
    @Column(name = "source_id", nullable = false) private Long sourceId;
    @Column(name = "target_component_id", nullable = false) private Long targetComponentId;
    private double score;
    @Column(length = 500) private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;
    @Column(name = "run_id") private String runId;
    private String decidedBy;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;

    // ── Validation metric M5 (Flyway V1). Suggestions are never deleted: rows left undecided
    // by Discard or by regenerating stay PENDING with withdrawnAt set, so every suggestion ever
    // shown stays countable.
    @Column(name = "resulting_link_id") private Long resultingLinkId;
    @Column(name = "resulting_link_count") private Integer resultingLinkCount;
    @Column(name = "decided_by_user_id", length = 64) private String decidedByUserId;
    /** single | accept_all */
    @Column(name = "decision_method", length = 12) private String decisionMethod;
    @Column(name = "withdrawn_at") private LocalDateTime withdrawnAt;
    /** discarded | superseded */
    @Column(name = "withdrawn_reason", length = 12) private String withdrawnReason;
    /** Rejected by the system because the target component was deleted, not by a user. */
    @Column(name = "system_rejected", nullable = false) private boolean systemRejected;

    public enum Status { PENDING, ACCEPTED, EDITED, REJECTED }

    public static final String WITHDRAWN_DISCARDED = "discarded";
    public static final String WITHDRAWN_SUPERSEDED = "superseded";
    public static final String METHOD_SINGLE = "single";
    public static final String METHOD_ACCEPT_ALL = "accept_all";

    public boolean isWithdrawn() {
        return withdrawnAt != null;
    }
}
