package com.ieee.evaluator.synctrace.validation.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Maps the mapping-workspace stage keys (PROPOSAL_SRS …) to the M4 stage codes (proposal_srs …). */
public final class StageKeys {

    /** Mapping stage key → M4 stage code, in workflow order. */
    public static final Map<String, String> CODES;
    static {
        Map<String, String> codes = new LinkedHashMap<>();
        codes.put("PROPOSAL_SRS", "proposal_srs");
        codes.put("SRS_SDD", "srs_sdd");
        codes.put("SRS_STD", "srs_std");
        codes.put("SRS_SPMP", "srs_spmp");
        codes.put("SDD_IMPLEMENTATION", "sdd_impl");
        CODES = java.util.Collections.unmodifiableMap(codes);
    }

    private StageKeys() {
    }

    /** @return the M4 stage code, or null when the key is not one of the five stages */
    public static String toCode(String stageKey) {
        if (stageKey == null) return null;
        String key = stageKey.trim().toUpperCase(Locale.ROOT);
        if (CODES.containsKey(key)) return CODES.get(key);
        // Already a stage code
        String code = stageKey.trim().toLowerCase(Locale.ROOT);
        return CODES.containsValue(code) ? code : null;
    }

    /** @return the mapping stage key (PROPOSAL_SRS …) for an M4 stage code */
    public static String toKey(String stageCode) {
        if (stageCode == null) return null;
        for (Map.Entry<String, String> entry : CODES.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(stageCode.trim())) return entry.getKey();
        }
        return null;
    }
}
