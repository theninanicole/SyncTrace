package com.ieee.evaluator.synctrace.model;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
public class GoalSummaryDTO {
    private final Long id;
    private final String description;
    private final LocalDateTime createdAt;
    private final Map<DocType, Boolean> categoryStatus;

    public GoalSummaryDTO(Goal goal, Map<DocType, Boolean> categoryStatus) {
        this.id             = goal.getId();
        this.description    = goal.getDescription();
        this.createdAt      = goal.getCreatedAt();
        this.categoryStatus = categoryStatus;
    }
}
