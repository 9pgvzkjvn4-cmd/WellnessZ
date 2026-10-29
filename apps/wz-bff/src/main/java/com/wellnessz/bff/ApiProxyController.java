package com.wellnessz.bff;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.web.CorrelationIdFilter;

/**
 * Token-relay reverse proxy: {@code /bff/api/x} → {@code ${core}/api/v1/x} with the user's access token
 * (refreshed transparently by the authorized-client manager). Only an allow-list of headers is forwarded;
 * cookies are never forwarded upstream and upstream Set-Cookie is never returned.
 */
@RestController
public class ApiProxyController {

    private static final Set<String> FORWARD_REQUEST_HEADERS = Set.of("content-type", "accept", "idempotency-key");
    private static final Set<String> FORWARD_RESPONSE_HEADERS = Set.of("content-type", "location", "x-correlation-id",
            "idempotent-replayed", "content-disposition", "cache-control");
    private static final long MAX_BODY = 11L * 1024 * 1024;

    private final OAuth2AuthorizedClientManager clients;
    private final RestClient http;
    private final String coreBase;

    public ApiProxyController(OAuth2AuthorizedClientManager clients, RestClient.Builder builder,
                              @Value("${wellnessz.bff.core-api-url}") String coreBase) {
        this.clients = clients;
        this.http = builder.build();
        this.coreBase = coreBase.endsWith("/") ? coreBase.substring(0, coreBase.length() - 1) : coreBase;
    }

    @GetMapping("/bff/api/**")
    public ResponseEntity<byte[]> read(HttpServletRequest request, Authentication authentication) throws IOException {
        return proxy(request, authentication);
    }

    /** State-changing verbs: Spring Security's CSRF filter has already validated the X-XSRF-TOKEN header. */
    @RequestMapping(value = "/bff/api/**", method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH,
            RequestMethod.DELETE})
    public ResponseEntity<byte[]> write(HttpServletRequest request, Authentication authentication) throws IOException {
        return proxy(request, authentication);
    }

    private ResponseEntity<byte[]> proxy(HttpServletRequest request, Authentication authentication) throws IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length() + "/bff/api".length());
        if (path.contains("..") || path.contains("//")) {
            throw ApiException.badRequest("invalid_path", "Invalid path");
        }
        if (path.startsWith("/workspace/") && !isCoreWorkspaceResource(path)) {
            path = path.substring("/workspace".length()); // role-gated alias; the core API enforces ABAC again
        }
        OAuth2AuthorizedClient client = clients.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("wellnessz")
                .principal(authentication).attribute(HttpServletRequest.class.getName(), request).build());
        if (client == null) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "session_expired", "Session expired");
        }
        String query = request.getQueryString();
        URI target = URI.create(coreBase + "/api/v1" + path + (query != null ? "?" + query : ""));
        byte[] body = request.getInputStream().readNBytes((int) MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            throw new ApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", "Request too large");
        }
        return http.method(HttpMethod.valueOf(request.getMethod()))
                .uri(target)
                .headers(h -> {
                    var names = request.getHeaderNames();
                    while (names.hasMoreElements()) {
                        String n = names.nextElement();
                        if (FORWARD_REQUEST_HEADERS.contains(n.toLowerCase(java.util.Locale.ROOT))) {
                            h.add(n, request.getHeader(n));
                        }
                    }
                    h.setBearerAuth(client.getAccessToken().getTokenValue());
                    String cid = CorrelationIdFilter.current();
                    if (cid != null) {
                        h.set(CorrelationIdFilter.HEADER, cid);
                    }
                })
                .body(body.length == 0 ? new byte[0] : body)
                .exchange((req, res) -> {
                    HttpHeaders out = new HttpHeaders();
                    res.getHeaders().forEach((k, v) -> {
                        if (FORWARD_RESPONSE_HEADERS.contains(k.toLowerCase(java.util.Locale.ROOT))) {
                            out.put(k, v);
                        }
                    });
                    return new ResponseEntity<>(res.getBody().readAllBytes(), out, HttpStatusCode.valueOf(res.getStatusCode().value()));
                });
    }

    /** Core endpoints that natively live under {@code /api/v1/workspace/...} (increment 2 manager / HR views). */
    static final java.util.List<String> CORE_WORKSPACE_RESOURCES = java.util.List.of("/workspace/team-restrictions", "/workspace/duty-proposals",
            "/workspace/rtw-plans", "/workspace/documents");

    static boolean isCoreWorkspaceResource(String path) {
        return CORE_WORKSPACE_RESOURCES.stream().anyMatch(r -> path.equals(r) || path.startsWith(r + "/"));
    }
}
