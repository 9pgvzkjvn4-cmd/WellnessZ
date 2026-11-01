package io.intimed.engine.transport;

import io.intimed.common.Json;
import tools.jackson.databind.JsonNode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client side of the device transports – used by the exchange harness (XT-01, XT-13), the simulators and the SDK /
 * gateway CLI. Records the negotiated protocol and cipher for the evidence pack, and offers negative probes
 * (plaintext, no client certificate, legacy TLS) that must be refused.
 */
public final class TransportClient {
    private TransportClient() { }

    public record Sent(String response, String protocol, String cipher, long millis) { }

    public static Sent mllp(String host, int port, SSLContext ctx, String message) throws Exception {
        long t0 = System.nanoTime();
        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(15000);
            SSLParameters p = s.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            p.setProtocols(DeviceListeners.protocols());
            s.setSSLParameters(p);
            s.startHandshake();
            Mllp.write(s.getOutputStream(), message);
            String ack = Mllp.read(s.getInputStream());
            return new Sent(ack, s.getSession().getProtocol(), s.getSession().getCipherSuite(), (System.nanoTime() - t0) / 1_000_000);
        }
    }

    public static Sent ingress(String host, int port, SSLContext ctx, JsonNode envelope) throws Exception {
        long t0 = System.nanoTime();
        HttpClient c = HttpClient.newBuilder().sslContext(ctx).connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create("https://" + host + ":" + port + "/ingest"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(envelope))).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = Json.parse(r.body());
        String tls = body.path("tls").asString("");
        String[] parts = tls.split(" ", 2);
        return new Sent(r.body(), parts[0], parts.length > 1 ? parts[1] : "", (System.nanoTime() - t0) / 1_000_000);
    }

    public static HttpResponse<String> https(String url, SSLContext ctx, String method, String body, Map<String, String> headers) throws Exception {
        HttpClient c = HttpClient.newBuilder().sslContext(ctx).connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sends plaintext MLLP to a TLS port – a compliant listener must not return an HL7 ACK. */
    public static Map<String, Object> plaintextProbe(String host, int port) {
        Map<String, Object> r = new LinkedHashMap<>();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(3000);
            OutputStream o = s.getOutputStream();
            o.write(0x0B);
            o.write("MSH|^~\\&|PROBE|PROBE|INTIMED|INTIMED|20260101000000||ORU^R01|P1|P|2.6\r".getBytes(StandardCharsets.US_ASCII));
            o.write(new byte[]{0x1C, 0x0D});
            o.flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[512];
            int n = in.read(buf);
            String got = n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.ISO_8859_1);
            r.put("refused", !got.contains("MSA|"));
            r.put("detail", n <= 0 ? "connection closed without response" : "non-HL7 response (TLS alert)");
        } catch (Exception e) {
            r.put("refused", true);
            r.put("detail", e.getClass().getSimpleName());
        }
        return r;
    }

    /** TLS handshake without a client certificate (or with a legacy protocol) – must fail. */
    public static Map<String, Object> handshakeProbe(String host, int port, SSLContext ctx, String[] protocols) {
        Map<String, Object> r = new LinkedHashMap<>();
        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(3000);
            if (protocols != null) {
                try { s.setEnabledProtocols(protocols); }
                catch (IllegalArgumentException e) { r.put("refused", true); r.put("detail", "protocol disabled in client runtime (" + String.join(",", protocols) + ")"); return r; }
            }
            s.startHandshake();
            // TLS 1.3 reports missing client certificates after the handshake – try to use the channel
            Mllp.write(s.getOutputStream(), "MSH|^~\\&|PROBE|PROBE|INTIMED|INTIMED|20260101000000||ORU^R01|P2|P|2.6\r");
            String ack = Mllp.read(s.getInputStream());
            r.put("refused", ack == null);
            r.put("detail", ack == null ? "closed after handshake" : "ACCEPTED");
        } catch (Exception e) {
            r.put("refused", true);
            r.put("detail", e.getClass().getSimpleName());
        }
        return r;
    }
}
