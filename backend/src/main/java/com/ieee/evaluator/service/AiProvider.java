package com.ieee.evaluator.service;

import java.util.List;

public interface AiProvider {
    // The identifier used by the frontend (e.g., "openai", "gemini")
    String getProviderName(); 
    
    // 1-arg: Base text-only analysis
    String analyze(String text) throws Exception; 

    // 2-arg: Multimodal path
    default String analyze(String documentContent, List<String> base64Images) throws Exception {
        return analyze(documentContent);
    }

    // 3-arg: Revision analysis path
    default String analyze(String documentContent, List<String> base64Images, String previousEvaluation) throws Exception {
        return analyze(documentContent, base64Images);
    }

    // 4-arg: Custom instructions path (NEWEST)
    default String analyze(String documentContent, List<String> base64Images, String previousEvaluation, String customInstructions) throws Exception {
        return analyze(documentContent, base64Images, previousEvaluation);
    }

    /**
     * Sends {@code prompt} to the model as-is (no document-review wrapping).
     * Use for SyncTrace tasks that expect structured JSON responses.
     */
    default String complete(String prompt) throws Exception {
        return analyze(prompt);
    }
}