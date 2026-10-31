package com.wellnessz.gateway.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.identity.ClientCertificate;
import com.wellnessz.gateway.identity.EndpointIdentity;

/**
 * HTTP client for the core's machine API ({@code /api/v1/device-ingest/**}, {@code /api/v1/device-enrolment/**}).
 * Mutual TLS uses the JDK {@code SSLContext} built from the client certificate; in development without an ingress the
 * gateway sets the certificate thumbprint header itself ({@code tls.simulate-ingress}). Every authenticated call carries
 * {@code X-Device-Endpoint}, {@code Authorization: DeviceToken …} and {@code X-Edge-Clock}. Requests never follow
 * redirects and do not use a proxy unless the JVM is told to.
 */
public class CoreClient {

    public static final String API_PREFIX = "/api/v1";
    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade");

    private final String baseUrl;
    private final GatewayProperties.ClientTls tls;
    private final ClientCertificate certificate;
    private final Supplier<EndpointIdentity> identity;
    private final Clock clock;
    private final HttpClient http;

    public CoreClient(GatewayProperties props, ClientCertificate certificate, Supplier<EndpointIdentity> identity,
                      Clock clock) {
        this.baseUrl = props.coreUrl() + API_PREFIX;
        this.tls = props.tls();
        this.certificate = certificate;
        this.identity = identity;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(tls.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(certificate.sslContext())
                .build();
    }

    /** Authenticated call with the endpoint identity. */
    public CoreResponse send(String method, String path, String contentType, Map<String, String> headers, byte[] body)
            throws IOException {
        return exchange(method, path, contentType, headers, body, true);
    }

    /** Unauthenticated call (enrolment): only the thumbprint header of the simulated ingress is added. */
    public CoreResponse sendAnonymous(String method, String path, String contentType, Map<String, String> headers,
                                      byte[] body) throws IOException {
        return exchange(method, path, contentType, headers, body, false);
    }

    private CoreResponse exchange(String method, String path, String contentType, Map<String, String> headers,
                                  byte[] body, boolean authenticated) throws IOException {
        if (!path.startsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException("invalid API path");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(tls.requestTimeout());
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (v != null && !RESTRICTED.contains(k.toLowerCase(Locale.ROOT))) {
                    b.header(k, v);
                }
            });
        }
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        b.header("Accept", "application/json, application/problem+json, text/plain, application/xml");
        b.header("X-Edge-Clock", clock.instant().toString());
        if (tls.simulateIngress() && certificate.present()) {
            b.header(tls.certificateHeader(), certificate.thumbprint());
        }
        if (authenticated) {
            EndpointIdentity id = identity.get();
            if (id == null) {
                throw new IllegalStateException("gateway is not enrolled");
            }
            b.header("X-Device-Endpoint", id.endpointId());
            b.header("Authorization", "DeviceToken " + id.endpointToken());
        }
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        b.method(method, publisher);
        try {
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new CoreResponse(r.statusCode(), r.headers().map(), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
