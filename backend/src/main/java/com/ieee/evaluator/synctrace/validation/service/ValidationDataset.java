package com.ieee.evaluator.synctrace.validation.service;

import com.ieee.evaluator.synctrace.model.MappingSuggestion;
import com.ieee.evaluator.synctrace.validation.model.AnalysisCoverage;
import com.ieee.evaluator.synctrace.validation.model.StageSession;
import com.ieee.evaluator.synctrace.validation.model.ValidationEvent;

import java.util.List;

/** Every raw validation record that matched a {@link ValidationFilter}. */
public record ValidationDataset(
        List<ValidationEvent> events,
        List<StageSession> stageSessions,
        List<MappingSuggestion> suggestions,
        List<AnalysisCoverage> coverage) {
}
