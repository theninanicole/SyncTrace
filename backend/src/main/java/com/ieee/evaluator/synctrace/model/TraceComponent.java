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

    /** Where this component's content originated: GITHUB, EVALUATION_HISTORY, or MANUAL. */
    @Column(name = "source_type", length = 32)
    private String sourceType;

    /** Human-readable origin locator, e.g. "owner/repo@branch:path" or "history:123". */
    @Column(name = "source_ref", length = 500)
    private String sourceRef;

    /** Direct link to the source when one exists (GitHub blob URL). */
    @Column(name = "source_url", length = 600)
    private String sourceUrl;

    /** When this component's content was captured/ingested from its source. */
    @Column(name = "source_captured_at")
    private LocalDateTime sourceCapturedAt;

    @Column(columnDefinition = "TEXT")
    private String imageData;

    private Boolean aiExtracted = false;

    private LocalDateTime createdAt;

    public enum DocType {
        SRS, SDD, SPMP, STD, IMPLEMENTATION, PROPOSAL
    }
}