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
    }
}
