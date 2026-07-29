package com.ieee.evaluator.synctrace.service;

import org.springframework.stereotype.Service;

@Service
public class ProposalPromptService {

    public String smartGoalExtractionPrompt(String proposalText) {
        return """
            You are analyzing a project proposal document for SyncTrace (software engineering capstone).

            Extract the SMART goals stated in the proposal's Objective section only
            (look for a heading such as "Objectives", "Objective", "Goals", or "General/Specific Objectives" —
            ignore the Introduction, Background, Scope, Methodology, and other sections).

            Do not categorize goals as general or specific. Return each objective as a single flat goal.
            Preserve wording close to the document. Do not invent goals that are not in the proposal.

            Return raw JSON only (no markdown fences). Use this shape:

            [
              {
                "description": "Objective text from the proposal"
              }
            ]

            Rules:
            - List every distinct objective from the Objective section as its own entry, in the order they appear.
            - If no Objective section is found, return [].

            Proposal text:
            %s
            """.formatted(proposalText);
    }
}
