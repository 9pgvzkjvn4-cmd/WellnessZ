package com.wellnessz.casekernel.support;

import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.OhCase;

/**
 * Module-contributed pre-condition on a status change, e.g. "recordability must be classified before leaving
 * INTAKE" (E01-F07) or "a safety-critical case cannot close without a fitness decision" (E02-F02).
 */
public interface CaseTransitionGuard {

    /** Throw {@code ApiException} (422) with a user-facing reason when the transition must not happen. */
    void check(OhCase c, CaseStatus next);
}
