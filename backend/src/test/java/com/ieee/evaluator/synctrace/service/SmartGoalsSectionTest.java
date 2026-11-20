package com.ieee.evaluator.synctrace.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SmartGoalsSectionTest {

    @Test
    void readsGoalTreeUntilNextHeading() {
        String report = """
            Summary:
            Fine.

            **SMART Goals:**
            * G1: Build the portal
              - G1.1: Login within 2 s
              - G1.2: Upload files
            * G2: Train staff

            Conclusion:
            Done.
            """;
        Optional<List<Map<String, Object>>> goals = SmartGoalsSection.read(report);
        assertTrue(goals.isPresent());
        assertEquals(2, goals.get().size());
        assertEquals("Build the portal", goals.get().get(0).get("description"));
        assertEquals("GENERAL", goals.get().get(0).get("goalKind"));
        assertEquals(2, ((List<?>) goals.get().get(0).get("children")).size());
        assertEquals("Train staff", goals.get().get(1).get("description"));
    }

    @Test
    void emptyWhenNoSection() {
        assertTrue(SmartGoalsSection.read("Summary:\nNothing here.").isEmpty());
    }

    @Test
    void noneFoundMeansNoGoals() {
        Optional<List<Map<String, Object>>> goals = SmartGoalsSection.read("## SMART Goals\nNone found.\n");
        assertTrue(goals.isPresent());
        assertTrue(goals.get().isEmpty());
    }
}
