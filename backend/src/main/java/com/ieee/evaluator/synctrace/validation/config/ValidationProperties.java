package com.ieee.evaluator.synctrace.validation.config;

import com.ieee.evaluator.synctrace.validation.model.MetricCode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * VALIDATION_TARGETS: the one place every validation target and timing constant lives.
 * Bound from {@code synctrace.validation.*} in application.properties, so a target can be
 * changed (or overridden per environment) without touching code.
 */
@ConfigurationProperties("synctrace.validation")
public record ValidationProperties(Targets targets, long heartbeatIntervalMs, long idleGapMs) {

    public record Targets(
            long m1MaxMs,
            long m2MaxMs,
            long m3MaxMs,
            long m4MaxMs,
            double m5MinRate,
            long m6MaxMs,
            double m7MinCoverage,
            long m8MaxMs,
            double requiredPctWithinTarget) {

        /** Duration ceiling for a timed metric (M1, M2, M3, M4, M6, M8); null for rate metrics. */
        public Long maxMsFor(MetricCode metric) {
            return switch (metric) {
                case M1 -> m1MaxMs;
                case M2 -> m2MaxMs;
                case M3 -> m3MaxMs;
                case M4 -> m4MaxMs;
                case M6 -> m6MaxMs;
                case M8 -> m8MaxMs;
                case M5, M7 -> null;
            };
        }
    }

    public ValidationProperties {
        if (targets == null) {
            targets = new Targets(30_000, 30_000, 30_000, 900_000, 70, 60_000, 100, 10_000, 100);
        }
        if (heartbeatIntervalMs <= 0) heartbeatIntervalMs = 30_000;
        if (idleGapMs <= 0) idleGapMs = 120_000;
    }

    public static ValidationProperties defaults() {
        return new ValidationProperties(null, 0, 0);
    }
}
