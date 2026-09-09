package com.ieee.evaluator.synctrace.model;

import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;
import java.time.LocalDateTime;

public record TraceComponentSummaryDTO(
    Long id,
    DocType docType,
    ArtifactKind artifactKind,
    String name,
    String codeName,
    Boolean aiExtracted,
    Long sourceHistoryId,
    String sourceType,
    String sourceRef,
    String sourceUrl,
    LocalDateTime sourceCapturedAt,
    LocalDateTime createdAt
) {}
