package com.wellnessz.casekernel.device;

/**
 * Authenticates device / gateway calls to the ingest API (E34-HLR-01), implemented by E34 and called only by the kernel
 * device-ingest filter. Both factors are required: the client-certificate SHA-256 thumbprint the ingress verified
 * (header {@code X-Client-Cert-Sha256}) and the per-endpoint token ({@code Authorization: DeviceToken <token>}) bound to
 * the endpoint id ({@code X-Device-Endpoint}). Contract sections 7 and 15.3.
 *
 * <p>The implementation compares the token against its stored hash in constant time, checks that the thumbprint matches
 * the active certificate of the endpoint, that the endpoint is ACTIVE (not ISOLATED / REVOKED), and applies zoning rules.
 * It must not publish the failure event itself – the filter publishes {@code ENDPOINT_AUTH_FAILED} once.</p>
 */
public interface DeviceEndpointAuthenticator {

    Result authenticate(Credentials credentials);

    /**
     * @param certificateSha256 lowercase hex SHA-256 of the DER client certificate, {@code null} when the ingress saw none
     * @param token             the per-endpoint token (never logged)
     * @param remoteAddress     client address as seen behind the ingress (X-Forwarded-For resolved by the framework)
     */
    record Credentials(String endpointId, String certificateSha256, String token, String remoteAddress, String method, String path) {

        @Override
        public String toString() {
            return "Credentials[endpointId=" + endpointId + ", path=" + path + "]"; // never print the token
        }
    }

    /**
     * @param failureCode unknown_endpoint | bad_token | certificate_mismatch | mtls_required | endpoint_isolated |
     *                    endpoint_revoked | endpoint_pending | zone_violation | token_expired
     */
    record Result(DeviceEndpointPrincipal principal, String failureCode) {

        public static Result success(DeviceEndpointPrincipal principal) {
            return new Result(principal, null);
        }

        public static Result failure(String code) {
            return new Result(null, code);
        }

        public boolean authenticated() {
            return principal != null;
        }
    }

    /** Fail-closed default when E34 is not deployed. */
    DeviceEndpointAuthenticator DENY_ALL = c -> Result.failure("endpoint_security_unavailable");
}
