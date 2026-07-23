package com.ieee.evaluator.synctrace.service;

import org.springframework.stereotype.Service;

@Service
public class ProposalPromptService {

    public String smartGoalExtractionPrompt(String proposalText) {
        return """
            You are analyzing a project proposal document for SyncTrace (software engineering capstone).

            Extract SMART objectives using this hierarchy from the adviser model:
            - GENERAL objectives describe high-level aims and are later mapped to MODULES.
            - SPECIFIC objectives describe concrete functions/transactions and belong under a GENERAL parent when possible.

            Prefer sections labeled General Objectives / Specific Objectives / Goals / Objectives.
            Preserve wording close to the document. Do not invent goals that are not in the proposal.

            Return raw JSON only (no markdown fences). Use this shape:

            [
              {
                "goalKind": "GENERAL",
                "description": "General objective text from the proposal",
                "children": [
                  {
                    "goalKind": "SPECIFIC",
                    "description": "Specific objective / function-transaction text"
                  }
                ]
              }
            ]

            Rules:
            - Top-level items should be GENERAL when the proposal has general objectives.
            - Put related specific objectives in "children".
            - If the proposal only lists flat goals with no general/specific split, return them as SPECIFIC objects (no children) at the top level.
            - If none found, return [].

            Proposal text:
            %s
            """.formatted(proposalText);
    }
}
