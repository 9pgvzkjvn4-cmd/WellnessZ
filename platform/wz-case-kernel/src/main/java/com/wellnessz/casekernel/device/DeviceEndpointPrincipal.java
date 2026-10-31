package com.wellnessz.casekernel.device;

import java.util.Optional;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * An authenticated device / gateway endpoint (E34-HLR-01) as placed in the security context by the device-ingest filter
 * ({@link com.wellnessz.casekernel.device.ingest.DeviceIngestSecurityConfig}). Ingest controllers read it with
 * {@link #current()}; the authentication name is {@code device:<endpointId>}.
 *
 * @param endpointId        E34 endpoint identity id, e.g. {@code EP-LHR-GW-01}
 * @param kind              endpoint kind
 * @param siteCode          site the identity is bound to
 * @param zone              network zone of the identity (E34-HLR-02), e.g. {@code DEVICE_VLAN}, {@code GATEWAY_ZONE}, {@code VENDOR_CLOUD}
 * @param tenantCode        tenant partition (E31-HLR-12), {@code GROUP} for the airline group
 * @param deviceInstanceIds device instances this endpoint may submit for (empty = resolved per message from the catalogue)
 * @param certificateSha256 thumbprint of the client certificate presented at the ingress
 */
public record DeviceEndpointPrincipal(String endpointId, DeviceCodes.EndpointKind kind, String siteCode, String zone, String tenantCode,
                                      Set<String> deviceInstanceIds, String certificateSha256) {

    public DeviceEndpointPrincipal {
        deviceInstanceIds = deviceInstanceIds == null ? Set.of() : Set.copyOf(deviceInstanceIds);
    }

    public String authenticationName() {
        return "device:" + endpointId;
    }

    /** The endpoint of the current request, if the request came through the device-ingest filter chain. */
    public static Optional<DeviceEndpointPrincipal> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof DeviceEndpointPrincipal p ? Optional.of(p) : Optional.empty();
    }

    /** As {@link #current()}, failing with 401 semantics when absent. */
    public static DeviceEndpointPrincipal require() {
        return current().orElseThrow(() -> new com.wellnessz.common.error.ApiException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "endpoint_unauthenticated", "Device endpoint authentication required"));
    }
}
