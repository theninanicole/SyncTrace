package com.ieee.evaluator.synctrace.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "traceability_mapping_activity")
public class TraceabilityMappingActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String teamCode;
    private String stage;
    private String performedBy;
    private Integer mappingCount;
    private LocalDateTime performedAt;
}