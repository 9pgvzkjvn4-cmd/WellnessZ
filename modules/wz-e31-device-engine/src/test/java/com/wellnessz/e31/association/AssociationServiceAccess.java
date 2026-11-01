package com.wellnessz.e31.association;

import java.util.List;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;

/** Test access to the package-private association-method filter. */
public final class AssociationServiceAccess {

    private AssociationServiceAccess() {
    }

    public static List<AssociationEngine.Candidate> allowed(List<AssociationEngine.Candidate> candidates, DeviceCatalogueLookup.DeviceInstance i) {
        return AssociationService.allowedCandidates(candidates, i);
    }
}
