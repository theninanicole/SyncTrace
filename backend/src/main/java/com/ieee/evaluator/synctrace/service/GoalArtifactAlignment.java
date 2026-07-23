package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.synctrace.model.ArtifactKind;
import com.ieee.evaluator.synctrace.model.SmartGoal.GoalKind;

import java.util.EnumSet;
import java.util.Set;

/**
 * Adviser mapping preferences:
 * GENERAL objectives → module-level artifacts;
 * SPECIFIC objectives → function/transaction artifacts.
 */
public final class GoalArtifactAlignment {

    public static final Set<ArtifactKind> MODULE_KINDS = EnumSet.of(
        ArtifactKind.CONTEXT_DIAGRAM,
        ArtifactKind.DATA_FLOW,
        ArtifactKind.OTHER_SRS,
        ArtifactKind.CLASS,
        ArtifactKind.DATA_MODEL,
        ArtifactKind.NON_OO,
        ArtifactKind.OTHER_SDD,
        ArtifactKind.MILESTONE,
        ArtifactKind.DELIVERABLE,
        ArtifactKind.OTHER_SPMP,
        ArtifactKind.TEST_DESIGN,
        ArtifactKind.OTHER_STD,
        ArtifactKind.IMPL_OO,
        ArtifactKind.IMPL_NON_OO,
        ArtifactKind.OTHER_IMPLEMENTATION
    );

    public static final Set<ArtifactKind> FUNCTION_KINDS = EnumSet.of(
        ArtifactKind.USE_CASE,
        ArtifactKind.ACTIVITY,
        ArtifactKind.WIREFRAME,
        ArtifactKind.SEQUENCE,
        ArtifactKind.UI,
        ArtifactKind.TASK,
        ArtifactKind.TEST_CASE,
        ArtifactKind.TEST_LOG,
        ArtifactKind.IMPL_OO,
        ArtifactKind.IMPL_NON_OO
    );

    private GoalArtifactAlignment() {}

    public static boolean isPreferred(GoalKind goalKind, ArtifactKind artifactKind) {
        if (artifactKind == null || artifactKind == ArtifactKind.UNSPECIFIED) return false;
        if (goalKind == GoalKind.GENERAL) return MODULE_KINDS.contains(artifactKind);
        return FUNCTION_KINDS.contains(artifactKind);
    }

    public static String preferredHint(GoalKind goalKind) {
        if (goalKind == GoalKind.GENERAL) {
            return "Prefer module-level artifacts (context/data modules, classes, milestones, deliverables).";
        }
        return "Prefer function/transaction artifacts (use cases, activities, sequences, UI, test cases).";
    }
}
