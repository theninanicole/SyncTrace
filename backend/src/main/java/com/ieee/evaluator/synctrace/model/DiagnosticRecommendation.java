package com.ieee.evaluator.synctrace.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "diagnostic_recommendations")
public class DiagnosticRecommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "findingId")
    private Long findingId;

    @Column(columnDefinition = "TEXT")
    private String rootCause;

    @Column(columnDefinition = "TEXT")
    private String recommendation;

    private String priority;

    private LocalDateTime createdAt;
}
