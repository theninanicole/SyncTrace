package com.ieee.evaluator.synctrace.validation.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * One metric's result for one team (and, for M4, optionally one stage). Fields that do not
 * apply to a metric stay null and are left out of the JSON.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MetricSummary {
    private String metric;
    private String label;
    /** M4 stage code, or null for the metric as a whole. */
    private String stage;

    /** Runs / finalized sessions / suggestions / analysis runs. */
    private int count;
    private Long medianMs;
    private Long maxMs;
    private Long minMs;
    private Double pctWithinTarget;
    private Boolean targetMet;
    /** Target as configured: milliseconds for timed metrics, percent for M5 and M7. */
    private Double target;
    private String targetUnit;

    // M1/M2/M3/M6/M8
    private Integer failed;
    private Integer timedOut;

    // M4
    private Integer openSessions;
    private Long medianWallClockMs;

    // M5
    private Integer accepted;
    private Integer edited;
    private Integer rejected;
    private Integer pending;
    private Integer withdrawn;
    private Integer systemRejected;
    private Double acceptanceRate;

    // M7
    private Integer gapsCounted;
    private Integer gapsWithBoth;
    private Integer generationFailures;
    private Double coveragePct;
}
