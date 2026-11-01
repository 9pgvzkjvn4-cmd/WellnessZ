package com.wellnessz.casekernel.device;

import java.time.Instant;

/**
 * Endpoint identity life-cycle commands, implemented by security (E34) and used by the console (E32) at publish
 * (wizard step 8) and decommissioning (E32-HLR-08). Contract sections 13.2 and 15.
 */
public interface DeviceEndpointProvisioning {

    /**
     * Creates (or reuses) the endpoint identity a published device instance submits through. For a device behind an
     * already-enrolled gateway, pass the gateway's endpoint id in {@code existingEndpointId}: the device instance is
     * authorised on it and no enrolment token is issued.
     */
    EndpointIssue provision(String actor, ProvisionRequest request);

    /** Revokes the identity (or only the device instance's authorisation on a shared gateway); effective within 5 minutes. */
    void revoke(String actor, String endpointId, String deviceInstanceId, String reasonCode);

    EndpointState state(String endpointId);

    record ProvisionRequest(String deviceInstanceId, DeviceCodes.EndpointKind kind, String siteCode, String zone, String tenantCode,
                            String existingEndpointId) {
    }

    /**
     * @param enrolmentToken one-time token shown once to the administrator (null when attached to an existing endpoint);
     *                       exchanged by the gateway / agent at {@code POST /device-enrolment/enrol}
     */
    record EndpointIssue(String endpointId, DeviceCodes.EndpointStatus status, String enrolmentToken, Instant enrolmentExpiresAt) {

        @Override
        public String toString() {
            return "EndpointIssue[endpointId=" + endpointId + ", status=" + status + "]"; // never print the token
        }
    }

    /**
     * @param kind endpoint kind ({@code null} when the implementation does not say)
     * @param zone network zone of the endpoint identity (E34-HLR-02) – not the device's segment behind it
     */
    record EndpointState(String endpointId, DeviceCodes.EndpointStatus status, Instant since, boolean recertificationRequired,
                         DeviceCodes.EndpointKind kind, String zone) {

        public EndpointState(String endpointId, DeviceCodes.EndpointStatus status, Instant since, boolean recertificationRequired) {
            this(endpointId, status, since, recertificationRequired, null, null);
        }
    }
}
