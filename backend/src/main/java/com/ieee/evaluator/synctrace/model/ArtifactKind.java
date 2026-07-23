package com.ieee.evaluator.synctrace.model;

/**
 * Fine-grained artifact subtypes under each document family,
 * matching the adviser traceability requirements.
 */
public enum ArtifactKind {
    // SRS
    USE_CASE,
    ACTIVITY,
    WIREFRAME,
    CONTEXT_DIAGRAM,
    DATA_FLOW,
    OTHER_SRS,

    // SDD
    CLASS,
    SEQUENCE,
    UI,
    DATA_MODEL,
    NON_OO,
    OTHER_SDD,

    // SPMP
    TASK,
    DELIVERABLE,
    MILESTONE,
    OTHER_SPMP,

    // STD
    TEST_DESIGN,
    TEST_CASE,
    TEST_LOG,
    OTHER_STD,

    // Implementation
    IMPL_OO,
    IMPL_NON_OO,
    OTHER_IMPLEMENTATION,

    // Proposal / fallback
    OTHER_PROPOSAL,
    UNSPECIFIED;

    public TraceComponent.DocType toDocType() {
        return switch (this) {
            case USE_CASE, ACTIVITY, WIREFRAME, CONTEXT_DIAGRAM, DATA_FLOW, OTHER_SRS
                -> TraceComponent.DocType.SRS;
            case CLASS, SEQUENCE, UI, DATA_MODEL, NON_OO, OTHER_SDD
                -> TraceComponent.DocType.SDD;
            case TASK, DELIVERABLE, MILESTONE, OTHER_SPMP
                -> TraceComponent.DocType.SPMP;
            case TEST_DESIGN, TEST_CASE, TEST_LOG, OTHER_STD
                -> TraceComponent.DocType.STD;
            case IMPL_OO, IMPL_NON_OO, OTHER_IMPLEMENTATION
                -> TraceComponent.DocType.IMPLEMENTATION;
            case OTHER_PROPOSAL
                -> TraceComponent.DocType.PROPOSAL;
            case UNSPECIFIED
                -> null;
        };
    }

    public static ArtifactKind defaultFor(TraceComponent.DocType docType) {
        if (docType == null) return UNSPECIFIED;
        return switch (docType) {
            case SRS -> OTHER_SRS;
            case SDD -> OTHER_SDD;
            case SPMP -> OTHER_SPMP;
            case STD -> OTHER_STD;
            case IMPLEMENTATION -> OTHER_IMPLEMENTATION;
            case PROPOSAL -> OTHER_PROPOSAL;
        };
    }
}
