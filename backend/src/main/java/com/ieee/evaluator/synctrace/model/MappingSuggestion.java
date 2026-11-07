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

    public enum Status { PENDING, ACCEPTED, EDITED, REJECTED }
}
