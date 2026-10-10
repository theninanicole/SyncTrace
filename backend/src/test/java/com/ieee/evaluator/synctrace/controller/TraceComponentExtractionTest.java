package com.ieee.evaluator.synctrace.controller;

import com.ieee.evaluator.synctrace.model.TraceComponent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Diagram findings edited into an evaluation by hand must extract like AI-written ones. */
class TraceComponentExtractionTest {

    private final TraceComponentController controller =
        new TraceComponentController(null, null, null, null, null);

    @SuppressWarnings("unchecked")
    private List<TraceComponent> extract(String evaluation, List<String> images) {
        return (List<TraceComponent>) ReflectionTestUtils.invokeMethod(
            controller, "extractComponentsFromEvaluation", evaluation, 7L, images);
    }

    @Test
    void extractsHandAddedDiagramsWrittenInLooserFormats() {
        String evaluation = """
            Summary: Solid document.

            **Diagram Analysis:**
            * [IMG-1] - Use Case Diagram: Covers the main actors.
              - Elements: Login, Manage Traceability
            * [img-2] - Activity Diagram: Added by the teacher.
            * [IMG 3] - Class Diagram: Also added by hand.

            ## Strengths
            - Clear scope.
            """;

        List<TraceComponent> components = extract(evaluation, List.of("a", "b", "c"));

        assertEquals(List.of("UC-01", "AD-01", "CL-01"),
            components.stream().map(TraceComponent::getCodeName).toList());
        assertEquals(List.of("a", "b", "c"),
            components.stream().map(TraceComponent::getImageData).toList());
    }

    @Test
    void readsDiagramsFromASecondDiagramAnalysisSection() {
        String evaluation = """
            Diagram Analysis:
            * [IMG-1] - Use Case Diagram: From the AI.

            Weaknesses:
            - Thin test plan.

            Diagram Analysis:
            * [IMG-4] - Sequence Diagram: Added afterwards.
            """;

        List<TraceComponent> components = extract(evaluation, List.of("a", "b", "c", "d"));

        assertEquals(List.of("UC-01", "SQ-01"),
            components.stream().map(TraceComponent::getCodeName).toList());
        assertEquals("d", components.get(1).getImageData());
    }

    @Test
    void extractsTheHandAddedUseCaseDiagramAsShownInTheReport() {
        String evaluation = """
            Diagram Analysis:
            * [IMG-20] - Activity Diagram: Shows how a teacher completes the mapping use case.
              - Elements: Select a team, Open the mapping
            * [IMG-28] - Use Case Diagram:
              - Elements: Manage Traceability Mapping, Extract SMART Goals, Extract Components, Establish/Maintain Mapping
              - Correctness: Acceptable.
            """;

        List<TraceComponent> components = extract(evaluation, java.util.Collections.nCopies(28, "img"));

        assertEquals(2, components.size());
        assertEquals("AD-01", components.get(0).getCodeName(), "type comes from the heading, not the body");
        assertEquals("UC-01", components.get(1).getCodeName());
        assertEquals(TraceComponent.DocType.SRS, components.get(1).getDocType());
        assertEquals("UC-01 — Use Case Diagram", components.get(1).getName());
        assertEquals("img", components.get(1).getImageData());
    }

    @Test
    void ordersComponentsLikeTheReportsDiagramAnalysis() {
        // The AI grouped its entries by diagram type; the report shows them by page image.
        String evaluation = """
            Diagram Analysis:
            * [IMG-5] - Use Case Diagram: Enrollment.
            * [IMG-9] - Use Case Diagram: Grading.
            * [IMG-2] - Activity Diagram: Login flow.
            * [IMG-7] - Sequence Diagram: Submit grade.
            """;

        List<TraceComponent> components = extract(evaluation, java.util.Collections.nCopies(9, "img"));

        assertEquals(List.of("AD-01", "UC-01", "SQ-01", "UC-02"),
            components.stream().map(TraceComponent::getCodeName).toList());
        assertEquals(List.of(0, 1, 2, 3),
            components.stream().map(TraceComponent::getDisplayOrder).toList());
    }
}
