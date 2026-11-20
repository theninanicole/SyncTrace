package com.ieee.evaluator.synctrace.validation.service;

import java.util.List;

/** All metric results for one team; {@code m4Stages} has one entry per mapping stage. */
public record TeamValidationSummary(String teamId, List<MetricSummary> metrics, List<MetricSummary> m4Stages) {

    public MetricSummary metric(String code) {
        return metrics.stream().filter(m -> code.equals(m.getMetric())).findFirst().orElse(null);
    }
}
