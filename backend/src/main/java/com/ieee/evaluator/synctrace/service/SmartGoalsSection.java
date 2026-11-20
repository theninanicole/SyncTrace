package com.ieee.evaluator.synctrace.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads the "SMART Goals" section of a proposal evaluation report. Server-side port of
 * {@code frontend/src/utils/smartGoalsSection.js} (readSmartGoals); keep the two in step.
 *
 * <pre>
 *   SMART Goals:
 *   * G1: General objective
 *     - G1.1: Specific objective
 * </pre>
 *
 * Goal tree shape: [{ goalKind: "GENERAL", description, children: [{ goalKind: "SPECIFIC", description }] }]
 */
public final class SmartGoalsSection {

    static final String SMART_GOALS_HEADING = "SMART Goals";

    private static final List<String> REPORT_SECTION_HEADINGS = List.of(
        "Summary", "Rubric Evaluation", "Strengths", "Weaknesses", "Missing Sections", "Recommendations",
        "Conclusion", "Revision Analysis", "Remaining Issues", "Next Steps", "Diagram Analysis",
        SMART_GOALS_HEADING);

    private static final Pattern GOALS_HEADING = headingLine(List.of(SMART_GOALS_HEADING));
    private static final Pattern OTHER_HEADINGS = headingLine(REPORT_SECTION_HEADINGS.stream()
        .filter(name -> !name.equals(SMART_GOALS_HEADING)).toList());

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern BULLET = Pattern.compile("^(?:[-*•]|\\d+[.)])\\s+", FLAGS);
    private static final Pattern LABEL = Pattern.compile("^G\\s*\\d+(\\.\\d+)?\\s*[:.)–—-]\\s*",
        FLAGS | Pattern.CASE_INSENSITIVE);
    private static final Pattern NONE = Pattern.compile("^none\\b", FLAGS | Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_SPACE = Pattern.compile("^\\s", FLAGS);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", FLAGS);

    private SmartGoalsSection() {
    }

    // A heading on a line of its own: "SMART Goals:", "**SMART Goals:**", "## SMART Goals".
    private static Pattern headingLine(List<String> names) {
        String alternatives = names.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        return Pattern.compile("^[ \\t]*(?:#{1,3}[ \\t]*)?(?:\\*\\*)?(?:" + alternatives
            + "):?(?:\\*\\*)?:?[ \\t]*\\r?$", Pattern.MULTILINE);
    }

    /** Empty when the report has no SMART Goals section at all (e.g. an older report). */
    public static Optional<List<Map<String, Object>>> read(String reportText) {
        String text = reportText == null ? "" : reportText;
        Matcher heading = GOALS_HEADING.matcher(text);
        if (!heading.find()) return Optional.empty();
        int bodyStart = heading.end();
        Matcher next = OTHER_HEADINGS.matcher(text);
        int end = next.find(bodyStart) ? next.start() : text.length();
        return Optional.of(parseBody(text.substring(bodyStart, end)));
    }

    /** Section body → goal tree. Tolerates hand edits: labels and bullets are optional. */
    static List<Map<String, Object>> parseBody(String body) {
        List<Map<String, Object>> goals = new ArrayList<>();
        List<Map<String, Object>> currentChildren = null;

        for (String raw : (body == null ? "" : body).split("\n", -1)) {
            if (raw.isBlank()) continue;
            boolean indented = LEADING_SPACE.matcher(raw).find();
            String line = BULLET.matcher(raw.strip()).replaceFirst("").replace("**", "");
            Matcher label = LABEL.matcher(line);
            boolean labelled = label.find();
            String description = oneLine(labelled ? line.substring(label.end()) : line);
            if (description.isEmpty()) continue;
            // "None found." and the like — the report's way of saying there are no goals.
            if (!labelled && NONE.matcher(description).find()) continue;

            boolean isChild = labelled ? label.group(1) != null : indented;
            if (isChild && currentChildren != null) {
                currentChildren.add(node("SPECIFIC", description, null));
                continue;
            }
            currentChildren = new ArrayList<>();
            goals.add(node("GENERAL", description, currentChildren));
        }
        return goals;
    }

    private static Map<String, Object> node(String kind, String description, List<Map<String, Object>> children) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("goalKind", kind);
        node.put("description", description);
        if (children != null) node.put("children", children);
        return node;
    }

    private static String oneLine(String value) {
        return WHITESPACE.matcher(value == null ? "" : value).replaceAll(" ").strip();
    }
}
