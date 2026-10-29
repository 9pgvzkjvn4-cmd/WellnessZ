package com.wellnessz.casekernel.model;

import com.wellnessz.common.model.DataClassification;

/** High-level case category; psychological and substance-related cases are the restricted class (E09/E10). */
public enum CaseCategory {
    INJURY(DataClassification.CLINICAL),
    ILLNESS(DataClassification.CLINICAL),
    DECREASE_IN_FITNESS(DataClassification.CLINICAL),
    PSYCHOLOGICAL(DataClassification.RESTRICTED_CLINICAL),
    SUBSTANCE(DataClassification.RESTRICTED_CLINICAL),
    EXPOSURE(DataClassification.CLINICAL),
    OTHER(DataClassification.CLINICAL);

    private final DataClassification classification;

    CaseCategory(DataClassification classification) {
        this.classification = classification;
    }

    public DataClassification classification() {
        return classification;
    }
}
