package com.ieee.evaluator.synctrace.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "smart_goals")
public class SmartGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Optional team scope, e.g. 2526-sem2-it332-08. */
    @Column(name = "team_code")
    private String teamCode;

    private LocalDateTime createdAt;
}
