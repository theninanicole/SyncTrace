package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ArtifactKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives short document-style codes (UC-01, TC-03, CD-01, …) for trace components.
 */
public final class ComponentCodeHelper {

    private static final Pattern CODED_ID = Pattern.compile(
        "\\b((?:UC|TC|FR|NFR|REQ|CL|CLS|CD|SQ|SEQ|AD|ACT|DFD|CTX|CX|MS|ML|TK|TSK|DL|DEL|TD|TL|WF|UI|ER|ERD)"
            + "[-\\s_]?\\d{1,3})\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ELEMENTS_LINE = Pattern.compile(
        "(?im)^\\s*[-*]?\\s*Elements?\\s*:\\s*(.+)$"
    );

    private static final Pattern ELEMENT_TOKEN = Pattern.compile(
        "((?:UC|TC|FR|NFR|REQ|CL|CLS|CD|SQ|SEQ|AD|ACT|DFD|CTX|CX|MS|ML|TK|TSK|DL|DEL|TD|TL|WF|UI|ER|ERD)"
            + "[-\\s_]?\\d{1,3})"
            + "(?:[\\s:\\-]+([^,;\\n]+))?",
        Pattern.CASE_INSENSITIVE
    );

    private ComponentCodeHelper() {}

    public record CodedElement(String codeName, String label) {}

    /** Prefer document codes listed under Elements:, else any coded IDs in the text. */
    public static List<CodedElement> extractElements(String text, ArtifactKind kind) {
        if (text == null || text.isBlank()) return List.of();

        Map<String, CodedElement> found = new LinkedHashMap<>();

        Matcher elementsLine = ELEMENTS_LINE.matcher(text);
        while (elementsLine.find()) {
            parseElementTokens(elementsLine.group(1), found);
        }

        if (found.isEmpty()) {
            parseElementTokens(text, found);
        }

        // If still empty, try a single ID on the first line (common for titled findings)
        if (found.isEmpty()) {
            String firstLine = text.lines().findFirst().orElse("").trim();
            Matcher m = CODED_ID.matcher(firstLine);
            if (m.find()) {
                String code = normalizeCode(m.group(1));
                String label = firstLine.replace(m.group(1), "").replaceAll("^[-:–—\\s]+", "").trim();
                found.put(code, new CodedElement(code, label.isBlank() ? code : code + " " + label));
            }
        }

        return new ArrayList<>(found.values());
    }

    public static String extractPrimaryCode(String text) {
        List<CodedElement> elements = extractElements(text, ArtifactKind.UNSPECIFIED);
        return elements.isEmpty() ? null : elements.get(0).codeName();
    }

    /** Short diagram/family code when the document did not expose element IDs. */
    public static String nextDiagramCode(ArtifactKind kind, Map<String, Integer> counters) {
        String prefix = prefixFor(kind);
        int next = counters.merge(prefix, 1, Integer::sum);
        return prefix + "-" + String.format(Locale.ROOT, "%02d", next);
    }

    public static String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String compact = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
        Matcher m = Pattern.compile("^([A-Z]+)[-]?(\\d{1,3})$").matcher(compact);
        if (!m.matches()) return compact;
        String prefix = canonicalizePrefix(m.group(1));
        return prefix + "-" + String.format(Locale.ROOT, "%02d", Integer.parseInt(m.group(2)));
    }

    public static String prefixFor(ArtifactKind kind) {
        if (kind == null) return "CMP";
        return switch (kind) {
            case USE_CASE -> "UC";
            case ACTIVITY -> "AD";
            case WIREFRAME -> "WF";
            case CONTEXT_DIAGRAM -> "CTX";
            case DATA_FLOW -> "DFD";
            case CLASS -> "CL";
            case SEQUENCE -> "SQ";
            case UI -> "UI";
            case DATA_MODEL -> "ER";
            case NON_OO -> "NO";
            case TASK -> "TK";
            case DELIVERABLE -> "DL";
            case MILESTONE -> "MS";
            case TEST_DESIGN -> "TD";
            case TEST_CASE -> "TC";
            case TEST_LOG -> "TL";
            case IMPL_OO, IMPL_NON_OO, OTHER_IMPLEMENTATION -> "SRC";
            default -> "CMP";
        };
    }

    /** Prefer stored code, else derive from name/content, else null. */
    public static String resolveDisplayCode(String codeName, String name, String content) {
        if (codeName != null && !codeName.isBlank()) return normalizeCode(codeName);
        String fromName = extractPrimaryCode(name);
        if (fromName != null) return fromName;
        return extractPrimaryCode(content);
    }

    public static String codeFromFilePath(String path) {
        if (path == null || path.isBlank()) return null;
        String file = path.replace('\\', '/');
        int slash = file.lastIndexOf('/');
        String base = slash >= 0 ? file.substring(slash + 1) : file;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.trim();
        if (base.isBlank()) return null;
        if (base.length() > 40) base = base.substring(0, 40);
        return base;
    }

    private static void parseElementTokens(String segment, Map<String, CodedElement> found) {
        if (segment == null || segment.isBlank()) return;
        Matcher m = ELEMENT_TOKEN.matcher(segment);
        while (m.find()) {
            String code = normalizeCode(m.group(1));
            if (code == null || found.containsKey(code)) continue;
            String labelPart = m.group(2) != null ? m.group(2).trim() : "";
            labelPart = labelPart.replaceAll("[)\\]]$", "").trim();
            String label = labelPart.isBlank() ? code : code + " " + labelPart;
            if (label.length() > 250) label = label.substring(0, 249).trim() + "…";
            found.put(code, new CodedElement(code, label));
        }
    }

    private static String canonicalizePrefix(String prefix) {
        return switch (prefix.toUpperCase(Locale.ROOT)) {
            case "CLS" -> "CL";
            case "SEQ" -> "SQ";
            case "ACT" -> "AD";
            case "CX" -> "CTX";
            case "ML" -> "MS";
            case "TSK" -> "TK";
            case "DEL" -> "DL";
            case "ERD" -> "ER";
            default -> prefix.toUpperCase(Locale.ROOT);
        };
    }
}
