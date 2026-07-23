package com.ieee.evaluator.synctrace.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "trace_components")
public class TraceComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private DocType docType;

    /** Fine-grained subtype (use case, class, milestone, etc.). */
    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_kind")
    private ArtifactKind artifactKind = ArtifactKind.UNSPECIFIED;

    @Column(length = 500)
    private String name;

    /** Short document identifier shown in matrices (UC-01, TC-03, CL-02, …). */
    @Column(name = "code_name", length = 64)
    private String codeName;

    @Column(columnDefinition = "TEXT")
    private String content;

    private Long sourceHistoryId;

    @Column(columnDefinition = "TEXT")
    private String imageData;

    private Boolean aiExtracted = false;

    private LocalDateTime createdAt;

    public enum DocType {
        SRS, SDD, SPMP, STD, IMPLEMENTATION, PROPOSAL
    }
}
