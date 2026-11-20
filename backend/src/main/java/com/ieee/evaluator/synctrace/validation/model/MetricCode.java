package com.ieee.evaluator.synctrace.validation.model;

/** The validation outcomes from the Software Project Proposal. */
public enum MetricCode {
    M1("SMART Goal extraction run time"),
    M2("Component retrieval run time"),
    M3("GitHub repository preprocessing time"),
    M4("Mapping stage finalization time"),
    M5("AI-suggested links accepted or edited"),
    M6("Continuity analysis run time"),
    M7("Gaps with explanation and recommendation"),
    M8("Audit report export time");

    private final String label;

    MetricCode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
