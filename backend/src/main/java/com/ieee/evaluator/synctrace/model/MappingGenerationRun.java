package com.ieee.evaluator.synctrace.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "mapping_generation_runs")
public class MappingGenerationRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "team_code", nullable = false) private String teamCode;
    @Column(nullable = false, length = 40) private String stage;
    private long durationMs;
    private int suggestionCount;
    @Column(nullable = false, length = 20) private String status;
    private LocalDateTime createdAt;
}
