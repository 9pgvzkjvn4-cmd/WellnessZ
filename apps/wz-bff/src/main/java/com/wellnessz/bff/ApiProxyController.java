package com.wellnessz.bff;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
 *
 * <p>Responses are relayed as a stream, never buffered as a whole. Streaming responses – Server-Sent Events
 * ({@code text/event-stream}, e.g. the E33 capture-session stream), NDJSON and any response without a declared length –
 * are flushed to the browser after every chunk read from the core, with {@code X-Accel-Buffering: no} so that an nginx in
 * front does not buffer either. There is no read timeout on the response body (an open event stream may stay silent for
 * minutes); only connecting to the core and receiving its response headers are bounded. When the browser goes away the
 * write fails, the upstream exchange is closed and the core sees the disconnect on its next write. The BFF security
 * headers (CSP, frame options, HSTS, referrer policy) are written by Spring Security when the response is committed, so
 * they are present on streamed responses as well.</p>
 */
@RestController
public class ApiProxyController {

    private static final Logger log = LoggerFactory.getLogger(ApiProxyController.class);
    private static final Set<String> FORWARD_REQUEST_HEADERS = Set.of("content-type", "accept", "idempotency-key", "last-event-id");
    private static final Set<String> FORWARD_RESPONSE_HEADERS = Set.of("content-type", "location", "x-correlation-id",
            "idempotent-replayed", "content-disposition", "cache-control");
    /** Media types relayed chunk by chunk with a flush after each chunk. */
    static final List<MediaType> STREAMING_TYPES = List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_NDJSON,
            MediaType.parseMediaType("application/stream+json"), MediaType.parseMediaType("application/x-ndjson"));
    private static final long MAX_BODY = 11L * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final OAuth2AuthorizedClientManager clients;
    private final RestClient http;
    private final String coreBase;

    public ApiProxyController(OAuth2AuthorizedClientManager clients, RestClient.Builder builder,
                              @Value("${wellnessz.bff.core-api-url}") String coreBase) {
        this.clients = clients;
        // JDK client without a read timeout: the body of an open stream is read for as long as the core keeps it open
        // (a read timeout would cut the capture stream after a silent interval); connecting is bounded
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        // relay bytes as the core sends them: no transparent gzip (it would buffer a stream and falsify Content-Length)
        factory.enableCompression(false);
        this.http = builder.requestFactory(factory).build();
        this.coreBase = coreBase.endsWith("/") ? coreBase.substring(0, coreBase.length() - 1) : coreBase;
    }

    @GetMapping("/bff/api/**")
    public void read(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
        proxy(request, response, authentication);
    }

    /** State-changing verbs: Spring Security's CSRF filter has already validated the X-XSRF-TOKEN header. */
    @RequestMapping(value = "/bff/api/**", method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH,
            RequestMethod.DELETE})
    public void write(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
        proxy(request, response, authentication);
    }

    /** The browser closed the connection while the response was relayed (not an error of the proxy). */
    static final class ClientDisconnected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ClientDisconnected(IOException cause) {
            super(cause);
        }
    }

    void proxy(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
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
        try {
            http.method(HttpMethod.valueOf(request.getMethod()))
                    .uri(target)
                    .headers(h -> {
                        var names = request.getHeaderNames();
                        while (names.hasMoreElements()) {
                            String n = names.nextElement();
                            if (FORWARD_REQUEST_HEADERS.contains(n.toLowerCase(Locale.ROOT))) {
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
                        relay(res.getStatusCode().value(), res.getHeaders(), res.getBody(), response);
                        return null;
                    });
        } catch (ClientDisconnected gone) {
            // closing the exchange (done by RestClient when the relay failed) cancels the upstream stream
            log.debug("Browser disconnected from {} {}", request.getMethod(), path.length() > 80 ? path.substring(0, 80) : path);
        }
    }

    /** {@code true} for Server-Sent Events, NDJSON / JSON streams and any response without a declared length. */
    static boolean streaming(HttpHeaders headers) {
        MediaType type = headers.getContentType();
        if (type != null && STREAMING_TYPES.stream().anyMatch(t -> t.isCompatibleWith(type))) {
            return true;
        }
        return headers.getContentLength() < 0;
    }

    static boolean eventStream(HttpHeaders headers) {
        MediaType type = headers.getContentType();
        return type != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type);
    }

    /**
     * Copies status, allow-listed headers and the body. Streaming bodies are flushed after every chunk (the first flush
     * commits the response, so the browser sees the headers and the first event at once); a failed write means the browser
     * has gone and ends the relay with {@link ClientDisconnected}.
     */
    static void relay(int status, HttpHeaders upstream, InputStream body, HttpServletResponse response) throws IOException {
        response.setStatus(status);
        upstream.forEach((k, v) -> {
            if (FORWARD_RESPONSE_HEADERS.contains(k.toLowerCase(Locale.ROOT))) {
                v.forEach(value -> response.addHeader(k, value));
            }
        });
        boolean stream = streaming(upstream);
        if (stream) {
            response.setHeader("X-Accel-Buffering", "no");
            if (eventStream(upstream)) {
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store");
            }
        } else {
            response.setContentLengthLong(upstream.getContentLength());
        }
        OutputStream out;
        try {
            out = response.getOutputStream();
            if (stream) {
                response.flushBuffer(); // headers now – an event stream may stay silent for a while
            }
        } catch (IOException e) {
            throw new ClientDisconnected(e);
        }
        byte[] buffer = new byte[stream ? 4096 : 16384];
        try (InputStream in = body) {
            int n;
            while ((n = readChunk(in, buffer)) != -1) {
                try {
                    out.write(buffer, 0, n);
                    if (stream) {
                        out.flush();
                    }
                } catch (IOException e) {
                    throw new ClientDisconnected(e);
                }
            }
        }
        try {
            out.flush();
        } catch (IOException e) {
            throw new ClientDisconnected(e);
        }
    }

    /** One read: returns as soon as the core has delivered some bytes (never waits for the buffer to fill). */
    private static int readChunk(InputStream in, byte[] buffer) throws IOException {
        try {
            return in.read(buffer);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Core endpoints that natively live under {@code /api/v1/workspace/...} (increment 2 and 3 manager / HR views). */
    static final java.util.List<String> CORE_WORKSPACE_RESOURCES = java.util.List.of("/workspace/team-restrictions", "/workspace/duty-proposals",
            "/workspace/rtw-plans", "/workspace/documents",
            // increment 3: E06 absence / advice, E07 surveillance compliance / pre-employment, E14 team appointments
            "/workspace/absence", "/workspace/advice-requests", "/workspace/surveillance", "/workspace/pre-employment",
            // increment 4: E08 claim status for HR (status only)
            "/workspace/claims",
            // increment 5: E10 for-cause requests and duty status, E09 psychosocial risk register, E12 campaigns and ergonomic actions
            "/workspace/testing", "/workspace/psychosocial", "/workspace/campaigns", "/workspace/ergonomics",
            // increment 6: E32 site device-fleet summary for clinic managers (counts and states only, no person data)
            "/workspace/device-fleet");

    static boolean isCoreWorkspaceResource(String path) {
        return CORE_WORKSPACE_RESOURCES.stream().anyMatch(r -> path.equals(r) || path.startsWith(r + "/"));
    }
}
