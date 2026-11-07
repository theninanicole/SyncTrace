package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.MappingSuggestion;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class MappingSuggestionPromptService {
    public String build(String stage, List<MappingSuggestionService.Candidate> sources,
            List<MappingSuggestionService.Candidate> targets) {
        StringBuilder prompt = new StringBuilder("""
            Return only a JSON array of likely traceability mappings.
            Each item must have sourceId, targetId, score (0 to 1), and a one-line reason.
            Do not invent ids. Use at most three targets per source.
            Stage: """).append(stage).append("\nSources:\n");
        sources.forEach(s -> prompt.append(s.id()).append(": ").append(s.text()).append('\n'));
        prompt.append("Targets:\n");
        targets.forEach(t -> prompt.append(t.id()).append(": ").append(t.text()).append('\n'));
        return prompt.toString();
    }
}
