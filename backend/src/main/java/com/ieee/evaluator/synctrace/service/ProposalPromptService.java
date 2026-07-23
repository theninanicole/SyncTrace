package com.ieee.evaluator.synctrace.service;

import org.springframework.stereotype.Service;

@Service
public class ProposalPromptService {

    public String smartGoalExtractionPrompt(String proposalText) {
        return """
            You are analyzing a project proposal document. Your task is to extract all SMART goals 
            (Specific, Measurable, Achievable, Relevant, Time-bound) from the proposal.
            
            Return your response as a raw JSON array of strings. Each string should be a complete SMART goal 
            as stated in the proposal. Do not include markdown code fences (```json or ```), just the raw JSON array.
            
            Example output format:
            ["The system shall allow users to login within 3 seconds.", "The system shall support 1000 concurrent users by Q4 2025."]
            
            If no SMART goals are found, return an empty array: []
            
            Proposal text:
            %s
            """.formatted(proposalText);
    }
}
