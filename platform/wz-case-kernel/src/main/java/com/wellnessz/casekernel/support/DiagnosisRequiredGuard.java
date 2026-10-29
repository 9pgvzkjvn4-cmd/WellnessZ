package com.wellnessz.casekernel.support;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.common.error.ApiException;

/** E03-F03 acceptance: every closed clinical case carries at least one coded diagnosis. */
@Component
public class DiagnosisRequiredGuard implements CaseTransitionGuard {

    static final Set<CaseType> CLINICAL_TYPES = EnumSet.of(CaseType.INJURY, CaseType.ILLNESS, CaseType.EXPOSURE, CaseType.FITNESS,
            CaseType.MENTAL_HEALTH, CaseType.SURVEILLANCE_FOLLOW_UP);

    private final boolean enabled;

    public DiagnosisRequiredGuard(@Value("${wellnessz.case.diagnosis-required-on-close:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void check(OhCase c, CaseStatus next) {
        if (enabled && next == CaseStatus.CLOSED && c.getCaseType() != null && CLINICAL_TYPES.contains(c.getCaseType())
                && !c.hasCodedDiagnosis()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "diagnosis_required",
                    "Code at least one diagnosis (SNOMED CT / ICD) before closing this case");
        }
    }
}
