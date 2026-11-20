package com.ieee.evaluator.synctrace.validation.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which teams and time window a summary or export covers. Rows are matched on when the work
 * started (events, stage sessions), when the suggestion was generated, or when coverage was
 * recorded. {@code to} is exclusive.
 *
 * @param teamIds requested team codes as given (empty = every team with data)
 */
public record ValidationFilter(Set<String> teamIds, Instant from, Instant to, String label) {

    public static final Instant BEGINNING = Instant.EPOCH;

    public ValidationFilter {
        teamIds = teamIds == null ? Set.of() : Set.copyOf(teamIds);
        from = from == null ? BEGINNING : from;
        to = to == null ? Instant.now().plus(1, ChronoUnit.DAYS) : to;
        label = label == null || label.isBlank() ? "all-time" : label;
    }

    public static ValidationFilter allTime(Collection<String> teamIds) {
        return new ValidationFilter(teamIds == null ? Set.of() : new LinkedHashSet<>(teamIds), null, null, null);
    }

    public boolean allTeams() {
        return teamIds.isEmpty();
    }

    public boolean includesTeam(String teamId) {
        if (allTeams()) return true;
        if (teamId == null) return false;
        String key = key(teamId);
        return teamIds.stream().anyMatch(requested -> key(requested).equals(key));
    }

    public boolean includesInstant(Instant at) {
        return at != null && !at.isBefore(from) && at.isBefore(to);
    }

    /** Team codes are case-preserved when stored but always compared ignoring case. */
    public static String key(String teamId) {
        return teamId == null ? "" : teamId.trim().toUpperCase(Locale.ROOT);
    }
}
