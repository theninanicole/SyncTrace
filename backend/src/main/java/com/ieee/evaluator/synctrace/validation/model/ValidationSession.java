package com.ieee.evaluator.synctrace.validation.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** A named time window ("Validation session — Nov 25, 9–11 AM") used only to filter exports. */
@Data
@Entity
@Table(name = "validation_sessions")
public class ValidationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    /** Comma-separated team codes; empty means every team. */
    @Column(name = "team_ids", nullable = false, columnDefinition = "TEXT")
    private String teamIds = "";

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public List<String> teamIdList() {
        if (teamIds == null || teamIds.isBlank()) return List.of();
        return Arrays.stream(teamIds.split(","))
            .map(String::trim)
            .filter(code -> !code.isEmpty())
            .toList();
    }
}
