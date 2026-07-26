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

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch;

    @Column(nullable = false)
    private Boolean active = true;

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
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
