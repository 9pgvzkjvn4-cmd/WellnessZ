package com.wellnessz.casekernel.device.ingest;

import java.io.IOException;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceEndpointAuthenticator;
import com.wellnessz.casekernel.device.DeviceSecurityEventPublisher;
import com.wellnessz.casekernel.device.DeviceSecurityEventPublisher.DeviceSecurityEvent;

/**
 * Two-factor endpoint authentication for {@code /api/v1/device-ingest/**} (E34-HLR-01):
 * <ol>
 *   <li>mTLS – the ingress terminates TLS, verifies the client certificate against the device CA and forwards the SHA-256
 *       thumbprint of the DER certificate in {@code X-Client-Cert-Sha256} (the ingress must overwrite any client-supplied
 *       value). Missing thumbprint → 401 {@code mtls_required} unless {@code wellnessz.device.ingest.require-client-certificate=false}.</li>
 *   <li>Per-endpoint token – {@code X-Device-Endpoint: <endpointId>} and {@code Authorization: DeviceToken <token>}.</li>
 * </ol>
 * Both are verified by the E34 {@link DeviceEndpointAuthenticator}. Every failure publishes one {@code ENDPOINT_AUTH_FAILED}
 * security event (PHI-free) and answers an RFC 9457 problem without detail about which factor failed beyond the code.
 *
 * <p>Status codes (one rule for the filter, the ingest access-denied handler and the module controllers): an authentication
 * failure – missing or wrong credentials, certificate, endpoint state or zone – is <b>401</b>; an endpoint that
 * <i>authenticated</i> but uses a path its kind may not use ({@code endpoint_forbidden}, decided by E34 only after every
 * authentication factor passed) is <b>403</b>.</p>
 */
public class DeviceEndpointAuthenticationFilter extends OncePerRequestFilter {

    public static final String ENDPOINT_HEADER = "X-Device-Endpoint";
    public static final String TOKEN_SCHEME = "DeviceToken ";
    private static final Pattern ENDPOINT_ID = Pattern.compile("[A-Za-z0-9._\\-]{1,64}");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    /** Verdicts about an authenticated endpoint (authorisation, not authentication): answered 403. */
    static final java.util.Set<String> FORBIDDEN_CODES = java.util.Set.of("endpoint_forbidden");

    private final Supplier<DeviceEndpointAuthenticator> authenticator;
    private final Supplier<DeviceSecurityEventPublisher> securityEvents;
    private final String certificateHeader;
    private final boolean requireClientCertificate;
    private final Clock clock;

    /**
     * @param authenticator  resolved per request ({@code DeviceSpis::authenticator} – fail-closed without E34)
     * @param securityEvents resolved per request ({@code DeviceSpis::securityEvents})
     */
    public DeviceEndpointAuthenticationFilter(Supplier<DeviceEndpointAuthenticator> authenticator,
                                              Supplier<DeviceSecurityEventPublisher> securityEvents, String certificateHeader,
                                              boolean requireClientCertificate, Clock clock) {
        this.authenticator = authenticator;
        this.securityEvents = securityEvents;
        this.certificateHeader = certificateHeader;
        this.requireClientCertificate = requireClientCertificate;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(DeviceIngestSecurityConfig.INGEST_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String endpointId = request.getHeader(ENDPOINT_HEADER);
        String authorization = request.getHeader("Authorization");
        String thumbprint = normaliseThumbprint(request.getHeader(certificateHeader));
        String token = authorization != null && authorization.startsWith(TOKEN_SCHEME) ? authorization.substring(TOKEN_SCHEME.length()).strip() : null;
        String safeEndpoint = endpointId != null && ENDPOINT_ID.matcher(endpointId).matches() ? endpointId : null;
        if (safeEndpoint == null || token == null || token.isEmpty()) {
            reject(request, response, safeEndpoint, "credentials_missing");
            return;
        }
        if (thumbprint == null && requireClientCertificate) {
            reject(request, response, safeEndpoint, "mtls_required");
            return;
        }
        DeviceEndpointAuthenticator.Result result = authenticator.get().authenticate(new DeviceEndpointAuthenticator.Credentials(
                safeEndpoint, thumbprint, token, request.getRemoteAddr(), request.getMethod(), request.getRequestURI()));
        if (!result.authenticated()) {
            reject(request, response, safeEndpoint, result.failureCode() == null ? "endpoint_unauthenticated" : result.failureCode());
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new DeviceEndpointAuthentication(result.principal()));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Accepts lowercase / uppercase hex with or without colons; anything else counts as absent. */
    static String normaliseThumbprint(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.replace(":", "").strip().toLowerCase(Locale.ROOT);
        return SHA256_HEX.matcher(v).matches() ? v : null;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String endpointId, String code) throws IOException {
        try {
            securityEvents.get().publish(new DeviceSecurityEvent("ENDPOINT_AUTH_FAILED", DeviceCodes.Severity.MEDIUM, endpointId, null, null,
                    endpointId == null ? "anonymous" : "device:" + endpointId, "KERNEL", code,
                    Map.of("remoteAddress", String.valueOf(request.getRemoteAddr()), "method", request.getMethod()), clock.instant()));
        } catch (RuntimeException ex) {
            logger.warn("Device security event could not be published: " + ex.getClass().getSimpleName());
        }
        String safeCode = code != null && code.matches("[a-z_]{1,64}") ? code : "endpoint_unauthenticated";
        HttpStatus status = status(safeCode);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String detail = status == HttpStatus.FORBIDDEN ? "The endpoint may not use this path" : "Device endpoint authentication failed";
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":" + status.value()
                + ",\"code\":\"" + safeCode + "\",\"detail\":\"" + detail + "\"}");
    }

    /** 403 for an authenticated endpoint on a forbidden path, 401 for every authentication failure. */
    static HttpStatus status(String code) {
        return FORBIDDEN_CODES.contains(code) ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
    }
}
