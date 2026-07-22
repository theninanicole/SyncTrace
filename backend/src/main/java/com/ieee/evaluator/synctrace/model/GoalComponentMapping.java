package com.ieee.evaluator.synctrace.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "goal_component_mappings", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"goalId", "componentId"})
})
public class GoalComponentMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "goalId")
    private Long goalId;

    @Column(name = "componentId")
    private Long componentId;

    private LocalDateTime createdAt;
}
