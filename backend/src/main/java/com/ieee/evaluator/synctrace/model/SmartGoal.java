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

    /**
     * GENERAL objectives map to modules;
     * SPECIFIC objectives map to functions/transactions.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "goal_kind")
    private GoalKind goalKind = GoalKind.SPECIFIC;

    /** Parent GENERAL goal when this is a SPECIFIC objective. */
    @Column(name = "parent_goal_id")
    private Long parentGoalId;

    /** Optional team scope, e.g. 2526-sem2-it332-08. */
    @Column(name = "team_code")
    private String teamCode;

    private LocalDateTime createdAt;

    public enum GoalKind {
        GENERAL,
        SPECIFIC
    }
}
