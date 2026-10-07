package com.ieee.evaluator.synctrace.service;

import org.springframework.stereotype.Service;

@Service
public class ProposalPromptService {

    public String smartGoalExtractionPrompt(String proposalText) {
        return """
            You are analyzing a project proposal document for SyncTrace (software engineering capstone).

            Extract SMART objectives from the proposal's Objective section only
            (look for a heading such as "Objectives", "Objective", "Goals", or "General/Specific Objectives" —
            ignore the Introduction, Background, Scope, Methodology, and other sections).

            Group each objective under this hierarchy:
            - GENERAL objectives describe high-level aims and are later converted into MODULES.
            - SPECIFIC objectives describe concrete functions/transactions and belong under their
              GENERAL parent objective when possible.

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
            - Put each general objective's related specific objectives in its "children" array.
            - Each SPECIFIC objective must appear exactly ONCE in the whole output. Never repeat the
              same specific objective under more than one GENERAL objective.
            - If the proposal lists several general objectives followed by one shared list of specific
              objectives, place each specific objective under the single general objective it most
              directly supports.
            - If the proposal only lists flat goals with no general/specific split, return them as
              SPECIFIC objects (no children) at the top level.
            - If none found, return [].

            Proposal text:
            %s
            """.formatted(proposalText);
    }
}
