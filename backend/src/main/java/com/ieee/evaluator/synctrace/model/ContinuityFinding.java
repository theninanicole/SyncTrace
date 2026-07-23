package com.ieee.evaluator.synctrace.model;

import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "continuity_findings")
public class ContinuityFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String teamCode;

    @Column(name = "goalId")
    private Long goalId;

    @Enumerated(EnumType.STRING)
    private DocType docTypeFrom;

    @Enumerated(EnumType.STRING)
    private DocType docTypeTo;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Column(columnDefinition = "TEXT")
    private String description;

    private LocalDateTime detectedAt;

    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}
