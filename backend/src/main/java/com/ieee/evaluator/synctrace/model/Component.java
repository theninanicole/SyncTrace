package com.ieee.evaluator.synctrace.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "components")
public class Component {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false)
    private DocType docType;

    @Column(nullable = false)
    private String name;

    // Extracted snippet / description shown in the component detail view.
    @Column(columnDefinition = "TEXT")
    private String content;

    // Set when this component was produced by AI extraction from an evaluated submission.
    @Column(name = "source_history_id")
    private Long sourceHistoryId;

    // Base64 page image (from the source submission) showing this component's diagram, if any.
    @Column(name = "image_data", columnDefinition = "TEXT")
    private String imageData;

    @Column(name = "ai_extracted")
    private Boolean aiExtracted = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
