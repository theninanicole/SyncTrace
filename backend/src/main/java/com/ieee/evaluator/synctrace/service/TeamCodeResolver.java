package com.ieee.evaluator.synctrace.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for extracting team codes from submission filenames.
 * Ports the logic from frontend/src/utils/dashboardUtils.js extractSubmissionMeta()
 */
public class TeamCodeResolver {

    // Pattern matches team codes like: 2526-SEM2-IT332-08
    private static final Pattern TEAM_CODE_PATTERN = Pattern.compile("\\b\\d{4}-SEM\\d-IT\\d+-\\d{2}\\b");

    /**
     * Extracts the team code from a submission filename.
     * 
     * @param fileName The submission filename (e.g., "[SRS] G01 - 2526-SEM2-IT332-08 | Name")
     * @return The team code (e.g., "2526-SEM2-IT332-08") or empty string if not found
     */
    public static String extractTeamCode(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }

        String upper = fileName.toUpperCase();
        Matcher matcher = TEAM_CODE_PATTERN.matcher(upper);
        
        if (matcher.find()) {
            return matcher.group(0);
        }
        
        return "";
    }

    /**
     * Cleans a team code before it is stored: surrounding whitespace is trimmed, but the code
     * is otherwise kept exactly as listed in the submission form / class list (there is no
     * default format or case). Because those sources don't always agree on case, every
     * team-scoped lookup of stored data must ignore case instead.
     */
    public static String normalize(String teamCode) {
        if (teamCode == null) return null;
        String trimmed = teamCode.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private TeamCodeResolver() {
        // Utility class - prevent instantiation
    }
}
