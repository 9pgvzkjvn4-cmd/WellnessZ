package com.wellnessz.casekernel.model;

/** Identity snapshot resolved from the HRIS employee master at case creation (E01-HLR-05). */
public record CaseIdentity(String displayName, String subsidiaryCode, String departmentCode, String orgUnitPath,
                           String costCentre, String jobCode, String locationCode, String shift, String language,
                           SafetyCategory safetyCategory, boolean hrisOverride) {
}
