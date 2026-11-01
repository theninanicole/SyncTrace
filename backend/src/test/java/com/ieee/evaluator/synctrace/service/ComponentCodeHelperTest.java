package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ArtifactKind;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentCodeHelperTest {

    @Test
    void ignoresReferencedCodesOfAnotherKind() {
        String finding = """
            * [IMG-20] - Activity Diagram:
            - Elements: Select a team and open the mapping (FR-01), Save mapping
            - Alignment: Supports UC-02 and FR-03.""";

        assertTrue(ComponentCodeHelper.extractOwnElements(finding, ArtifactKind.ACTIVITY).isEmpty());
    }

    @Test
    void keepsCodesMatchingTheDiagramKind() {
        String finding = """
            * [IMG-28] - Use Case Diagram:
            - Elements: UC-1 Login, UC-02 Manage Traceability, FR-01
            - Alignment: Covers FR-02.""";

        List<ComponentCodeHelper.CodedElement> own =
            ComponentCodeHelper.extractOwnElements(finding, ArtifactKind.USE_CASE);

        assertEquals(List.of("UC-1", "UC-02"), own.stream().map(ComponentCodeHelper.CodedElement::codeName).toList());
    }

    @Test
    void acceptsAliasPrefixes() {
        String finding = "* [IMG-3] ACT-4 Checkout Activity Diagram";

        List<ComponentCodeHelper.CodedElement> own =
            ComponentCodeHelper.extractOwnElements(finding, ArtifactKind.ACTIVITY);

        assertEquals("ACT-4", own.get(0).codeName());
    }

    @Test
    void keepsTheDocumentsOwnIdEvenWithAPrefixThatIsNotBuiltIn() {
        assertEquals("SD-04", ownCode("* [IMG-3] - SD-04 Login Sequence Diagram:", ArtifactKind.SEQUENCE));
        assertEquals("CD-02", ownCode("* [IMG-3] - Class Diagram CD-02:", ArtifactKind.CLASS));
        assertEquals("ERD-1", ownCode("* [IMG-3] - ERD-1 Data Design:", ArtifactKind.DATA_MODEL));
        assertEquals("BR-2", ownCode("* [IMG-3] - Wireframe BR_2:", ArtifactKind.WIREFRAME));
    }

    @Test
    void listsElementsWithIdsThatAreNotBuiltIn() {
        String finding = """
            * [IMG-9] - Sequence Diagram:
            - Elements: SD-01 Login, SD-02 Register, UC-01""";

        assertEquals(List.of("SD-01", "SD-02"),
            ComponentCodeHelper.extractOwnElements(finding, ArtifactKind.SEQUENCE).stream()
                .map(ComponentCodeHelper.CodedElement::codeName).toList());
    }

    @Test
    void doesNotMistakeCitationsOrPageImagesForTheDiagramsId() {
        assertTrue(ComponentCodeHelper.extractOwnElements(
            "* [IMG-3] - Activity Diagram for UC-01 and FR-02:", ArtifactKind.ACTIVITY).isEmpty());
        assertTrue(ComponentCodeHelper.extractOwnElements(
            "* [IMG-3] - Class Diagram per IEEE-1016:", ArtifactKind.CLASS).isEmpty());
        assertTrue(ComponentCodeHelper.extractOwnElements(
            "* [IMG-3] - Use Case Diagram for team 2627-SEM1-IT411-08:", ArtifactKind.USE_CASE).isEmpty());
    }

    private static String ownCode(String finding, ArtifactKind kind) {
        return ComponentCodeHelper.extractOwnElements(finding, kind).get(0).codeName();
    }

    @Test
    void fallbackNumberingSkipsReservedCodes() {
        Map<String, Integer> counters = new HashMap<>();
        ComponentCodeHelper.reserveCode("UC-2", counters);

        assertEquals("UC-03", ComponentCodeHelper.nextDiagramCode(ArtifactKind.USE_CASE, counters));
        assertEquals("AD-01", ComponentCodeHelper.nextDiagramCode(ArtifactKind.ACTIVITY, counters));
    }

    @Test
    void keepsSubNumberedCodesAsTyped() {
        assertEquals("UC-1.1", ComponentCodeHelper.normalizeCode("uc-1.1"));
        assertEquals("UC-2.10", ComponentCodeHelper.normalizeCode("UC 2.10"));
        assertEquals("UC-01", ComponentCodeHelper.normalizeCode("UC-1"));
        assertEquals("UC-1.1", ComponentCodeHelper.extractPrimaryCode("UC-1.1"));
        assertEquals("UC-1.1", ComponentCodeHelper.extractPrimaryCode("UC-1.1 Login"));
    }

    @Test
    void storedCodeIsDisplayedExactlyAsSaved() {
        assertEquals("uc 1.1 - Login flow", ComponentCodeHelper.resolveDisplayCode("  uc 1.1 - Login flow ", "UC-01", null));
    }
}
