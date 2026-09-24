package com.ieee.evaluator.synctrace.model;

import com.ieee.evaluator.synctrace.model.TraceComponent.DocType;

public enum MappingStage {
    PROPOSAL_SRS(DocType.SRS),
    SRS_SDD(DocType.SDD),
    SRS_STD(DocType.STD),
    SRS_SPMP(DocType.SPMP),
    SDD_IMPLEMENTATION(DocType.IMPLEMENTATION);

    private final DocType targetDocType;

    MappingStage(DocType targetDocType) {
        this.targetDocType = targetDocType;
    }

    public DocType getTargetDocType() {
        return targetDocType;
    }
}
