package com.ieee.evaluator.synctrace.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
    name = "synctrace_github_repositories",
    uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "repo"})
)
public class GitHubRepositoryLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String repo;

    @Column(name = "repository_url", nullable = false, length = 600)
    private String repositoryUrl;

    @Column(name = "team_code", length = 120)
    private String teamCode;

    @Column(name = "role", length = 20)
    private String role = "OTHER";

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "ingestion_status", length = 20)
    private String ingestionStatus = "NOT_INGESTED";

    @Column(name = "ingested_file_count")
    private Integer ingestedFileCount = 0;

    @Column(name = "last_ingest_error", columnDefinition = "TEXT")
    private String lastIngestError;

    @Column(name = "last_ingested_at")
    private LocalDateTime lastIngestedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (active == null) {
            active = true;
        }
        if (role == null || role.isBlank()) {
            role = "OTHER";
        }
        if (ingestionStatus == null || ingestionStatus.isBlank()) {
            ingestionStatus = "NOT_INGESTED";
        }
        if (ingestedFileCount == null) {
            ingestedFileCount = 0;
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
