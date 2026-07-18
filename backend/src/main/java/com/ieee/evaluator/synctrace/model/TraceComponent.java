package com.ieee.evaluator.synctrace.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "synctrace_components")
public class TraceComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_type", nullable = false, length = 40)
    private String docType;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "source_history_id")
    private Long sourceHistoryId;

    @Column(name = "source_type", length = 40)
    private String sourceType;

    @Column(name = "source_ref", length = 300)
    private String sourceRef;

    @Column(name = "source_url", length = 600)
    private String sourceUrl;

    @Column(name = "source_hash", length = 64)
    private String sourceHash;

    @Column(name = "source_captured_at")
    private LocalDateTime sourceCapturedAt;

    @Column(name = "image_data", columnDefinition = "TEXT")
    private String imageData;

    @Column(name = "ai_extracted", nullable = false)
    private Boolean aiExtracted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (aiExtracted == null) {
            aiExtracted = false;
        }
        if (sourceCapturedAt == null) {
            sourceCapturedAt = createdAt;
        }
    }
}
