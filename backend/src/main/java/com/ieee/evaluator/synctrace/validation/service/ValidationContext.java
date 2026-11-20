package com.ieee.evaluator.synctrace.validation.service;

import java.util.function.Predicate;

/**
 * What a timed operation is about. Mutable on purpose: some values (the team behind an
 * evaluation report, the repository behind a URL) are only known part-way through the work,
 * so the operation fills them in as it learns them.
 */
public final class ValidationContext {

    private String teamId;
    private String userId;
    private Long repositoryId;
    private String exportFormat;
    private String analysisRunId;
    private Predicate<Throwable> notARun = error -> false;

    public static ValidationContext forTeam(String teamId) {
        return new ValidationContext().teamId(teamId);
    }

    public ValidationContext teamId(String teamId) {
        this.teamId = teamId == null || teamId.isBlank() ? null : teamId.trim();
        return this;
    }

    public ValidationContext userId(String userId) {
        this.userId = userId;
        return this;
    }

    public ValidationContext repositoryId(Long repositoryId) {
        this.repositoryId = repositoryId;
        return this;
    }

    public ValidationContext exportFormat(String exportFormat) {
        this.exportFormat = exportFormat;
        return this;
    }

    public ValidationContext analysisRunId(String analysisRunId) {
        this.analysisRunId = analysisRunId;
        return this;
    }

    /** Errors that mean the request was rejected before any work, so no run is recorded. */
    public ValidationContext notARunWhen(Predicate<Throwable> predicate) {
        this.notARun = predicate == null ? error -> false : predicate;
        return this;
    }

    public String teamId() { return teamId; }
    public String userId() { return userId; }
    public Long repositoryId() { return repositoryId; }
    public String exportFormat() { return exportFormat; }
    public String analysisRunId() { return analysisRunId; }
    boolean isNotARun(Throwable error) { return notARun.test(error); }
}
