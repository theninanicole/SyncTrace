package com.ieee.evaluator.synctrace.validation.model;

/** The concrete server operation behind a timed metric event. */
public enum ValidationOperation {
    GOAL_EXTRACTION(MetricCode.M1),
    COMPONENT_RETRIEVAL(MetricCode.M2),
    REPO_INGESTION(MetricCode.M3),
    GAP_DETECTION(MetricCode.M6),
    RECOMMENDATIONS(MetricCode.M6),
    /** Source-code alignment: recorded under M6 but excluded from its target (plan decision D6). */
    ALIGNMENT(MetricCode.M6),
    AUDIT_EXPORT(MetricCode.M8);

    private final MetricCode metric;

    ValidationOperation(MetricCode metric) {
        this.metric = metric;
    }

    public MetricCode metric() {
        return metric;
    }
}
