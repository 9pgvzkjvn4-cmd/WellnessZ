package com.wellnessz.casekernel.device;

import java.util.Set;

/**
 * Callback from security (E34) when an endpoint is isolated, revoked or released (E34-HLR-09: isolation within 15 minutes,
 * re-certification before reconnection). Implemented by the console (E32: instances become QUARANTINED and need
 * re-certification) and the engine (E31: stops accepting, parks in-flight messages). Contract section 15.8.
 */
public interface DeviceEndpointStatusListener {

    /**
     * @param status     new status (ISOLATED | REVOKED | ACTIVE)
     * @param reasonCode e.g. {@code ANOMALY_FAILED_AUTH_BURST}, {@code MANUAL_ISOLATION}, {@code DECOMMISSIONED}, {@code RELEASED}
     */
    void endpointStatusChanged(String endpointId, Set<String> deviceInstanceIds, DeviceCodes.EndpointStatus status, String reasonCode);
}
