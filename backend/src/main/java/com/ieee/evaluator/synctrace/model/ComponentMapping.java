package com.ieee.evaluator.synctrace.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
    name = "component_mappings",
    uniqueConstraints = @UniqueConstraint(columnNames = {"goal_id", "component_id"})
)
public class ComponentMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "goal_id", nullable = false)
    private Long goalId;

    @Column(name = "component_id", nullable = false)
    private Long componentId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
