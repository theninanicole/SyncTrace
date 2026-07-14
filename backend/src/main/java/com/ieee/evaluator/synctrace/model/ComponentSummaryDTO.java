package com.ieee.evaluator.synctrace.model;

import lombok.Getter;

import java.time.LocalDateTime;

// Lightweight projection of Component for list views — excludes the large
// base64 image/content columns so listing endpoints don't pull them from Postgres.
@Getter
public class ComponentSummaryDTO {
    private final Long id;
    private final DocType docType;
    private final String name;
    private final Long sourceHistoryId;
    private final LocalDateTime createdAt;

    public ComponentSummaryDTO(Long id, DocType docType, String name, Long sourceHistoryId, LocalDateTime createdAt) {
        this.id              = id;
        this.docType         = docType;
        this.name            = name;
        this.sourceHistoryId = sourceHistoryId;
        this.createdAt       = createdAt;
    }
}
