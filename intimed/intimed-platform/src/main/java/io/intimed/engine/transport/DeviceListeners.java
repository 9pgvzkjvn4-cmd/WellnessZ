package io.intimed.engine.transport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.intimed.common.Json;
import io.intimed.engine.association.AssociationEngine;
import io.intimed.engine.parse.DelimitedRecords;
import io.intimed.engine.pipeline.Envelope;
import io.intimed.engine.pipeline.Pipeline;
import io.intimed.engine.pipeline.PipelineResult;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.endpoint.AnomalyService;
import io.intimed.security.endpoint.EndpointService;
import io.intimed.security.pki.LocalPki;
import io.intimed.store.DocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Device-facing listeners of the engine (SC-01, SC-12):
 * <ul>
 *   <li>MLLP over TLS ({@code intimed.device.mllp-port}, default 2575) for HL7 v2 (P4, P5 bridges, ADT/ORM in) – TLS 1.2/1.3,
 *       client certificate mandatory, endpoint identity re-checked per connection, plaintext refused.</li>
 *   <li>HTTPS device ingress ({@code intimed.device.ingress-port}, default 8443) for gateway / device-host / mobile
 *       envelopes (P1, P2, P3, P5 XML, P7, P9, P10, P11) – the same mTLS rules.</li>
 * </ul>
 * There is no plaintext device port. Handshake failures are signalled to anomaly detection.
 */
@Component
public class DeviceListeners implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceListeners.class);
    public static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};
    private final Pipeline pipeline;
    private final EndpointService endpoints;
    private final AnomalyService anomalies;
    private final AssociationEngine association;
    private final DocumentStore store;
    private final AuditService audit;
    private final int mllpPort;
    private final int ingressPort;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private SSLServerSocket mllp;
    private HttpsServer ingress;

    public DeviceListeners(Pipeline pipeline, EndpointService endpoints, AnomalyService anomalies, AssociationEngine association,
                           DocumentStore store, AuditService audit,
                           @Value("${intimed.device.mllp-port:2575}") int mllpPort, @Value("${intimed.device.ingress-port:8443}") int ingressPort) {
        this.pipeline = pipeline;
        this.endpoints = endpoints;
        this.anomalies = anomalies;
        this.association = association;
        this.store = store;
        this.audit = audit;
        this.mllpPort = mllpPort;
        this.ingressPort = ingressPort;
    }

    public int mllpPort() { return mllp == null ? mllpPort : mllp.getLocalPort(); }
    public int ingressPort() { return ingress == null ? ingressPort : ingress.getAddress().getPort(); }

    @Override
    public void start() {
        try {
            SSLContext ctx = LocalPki.get().serverSslContext();
            if (mllpPort >= 0) {
                mllp = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket();
                mllp.setReuseAddress(true);
                mllp.bind(new InetSocketAddress("0.0.0.0", mllpPort), 100);
                mllp.setNeedClientAuth(true);
                mllp.setEnabledProtocols(PROTOCOLS);
                pool.submit(this::acceptLoop);
                LOG.info("MLLP-TLS (mTLS required) listening on {}", mllp.getLocalPort());
            }
            if (ingressPort >= 0) {
                ingress = HttpsServer.create(new InetSocketAddress("0.0.0.0", ingressPort), 100);
                ingress.setHttpsConfigurator(new HttpsConfigurator(ctx) {
                    @Override
                    public void configure(HttpsParameters params) {
                        SSLParameters p = getSSLContext().getDefaultSSLParameters();
                        p.setNeedClientAuth(true);
                        p.setProtocols(PROTOCOLS);
                        params.setSSLParameters(p);
                    }
                });
                ingress.createContext("/ingest", this::ingest);
                ingress.createContext("/health", ex -> respond(ex, 200, Map.of("status", "UP")));
                ingress.setExecutor(pool);
                ingress.start();
                LOG.info("Device ingress HTTPS (mTLS required) listening on {}", ingress.getAddress().getPort());
            }
            running = true;
        } catch (IOException e) {
            throw new IllegalStateException("device listeners failed to start: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        running = false;
        try { if (mllp != null) mllp.close(); } catch (IOException ignored) { }
        if (ingress != null) ingress.stop(0);
    }

    @Override public boolean isRunning() { return running; }

    // ---------------------------------------------------------------- MLLP
    private void acceptLoop() {
        while (running || mllp != null && !mllp.isClosed()) {
            try {
                SSLSocket s = (SSLSocket) mllp.accept();
                pool.submit(() -> handle(s));
            } catch (IOException e) {
                if (mllp.isClosed()) return;
            }
        }
    }

    private void handle(SSLSocket s) {
        String remote = String.valueOf(s.getRemoteSocketAddress());
        try (s) {
            s.setSoTimeout(60_000);
            try {
                s.startHandshake();
            } catch (javax.net.ssl.SSLException e) {
                anomalies.signal(DocumentStore.GLOBAL, "unknown@" + host(remote), "PLAINTEXT_ATTEMPT", "MLLP handshake refused: " + e.getClass().getSimpleName());
                audit.record(DocumentStore.GLOBAL, "anonymous", "device.tls.refused", "mllp", Map.of("remote", host(remote)));
                return;
            }
            X509Certificate cert = (X509Certificate) s.getSession().getPeerCertificates()[0];
            Optional<EndpointService.Identity> id = endpoints.authenticate(cert);
            if (id.isEmpty() || !id.get().active()) {
                String tenant = id.map(EndpointService.Identity::tenant).orElse(DocumentStore.GLOBAL);
                anomalies.signal(tenant, id.map(EndpointService.Identity::endpointId).orElse("unknown@" + host(remote)), "UNKNOWN_IDENTITY",
                        "MLLP connection with " + id.map(EndpointService.Identity::status).orElse("unregistered") + " certificate");
                return;
            }
            EndpointService.Identity ident = id.get();
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String msg;
            while ((msg = Mllp.read(in)) != null) {
                endpoints.touch(ident.tenant(), ident.endpointId(), host(remote));
                String reply = onHl7(ident, msg, remote);
                Mllp.write(out, reply);
            }
        } catch (SocketTimeoutException e) {
            LOG.debug("MLLP idle timeout {}", remote);
        } catch (IOException e) {
            LOG.debug("MLLP connection error: {}", e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("MLLP handler error: {}", e.getClass().getSimpleName());
        }
    }

    String onHl7(EndpointService.Identity ident, String msg, String remote) {
        String type;
        try {
            DelimitedRecords r = new DelimitedRecords(msg, true);
            type = r.first("MSH-9.1") + "^" + r.first("MSH-9.2");
            if (type.startsWith("ADT^")) return Current.asSystem(ident.tenant(), "mllp", () -> adt(ident.tenant(), r, msg, type));
            if (type.startsWith("ORM^") || type.startsWith("OML^")) return Current.asSystem(ident.tenant(), "mllp", () -> order(ident.tenant(), r, msg));
            if ("HARNESS".equals(ident.endpoint().path("type").asString()) && "HARNESS".equals(r.first("MSH-6.1"))) {
                String runId = r.first("MSH-6.2"), cfgId = r.first("MSH-6.3");
                String subject = "NOSUBJECT".equals(r.first("MSH-6.4")) ? null : r.first("PID-3.1");
                PipelineResult hr = Current.asSystem(ident.tenant(), "harness", () -> pipeline.process(new Envelope(ident.tenant(), ident.endpointId(), cfgId, "P4", msg,
                        "TEST", subject, runId, host(remote), java.time.Instant.now())));
                return Mllp.ack(msg, hr.ackCode, hr.status + (hr.error == null ? "" : " " + hr.error));
            }
            String sendingApp = r.first("MSH-3.1");
            String cfg = resolveConfig(ident, sendingApp);
            if (cfg == null) return Mllp.ack(msg, "AR", "no published configuration bound to this endpoint for " + sendingApp);
            PipelineResult res = Current.asSystem(ident.tenant(), "mllp", () ->
                    pipeline.process(Envelope.live(ident.tenant(), ident.endpointId(), cfg, "P4", msg, host(remote))));
            return Mllp.ack(msg, res.ackCode, res.status + (res.error == null ? "" : " " + res.error));
        } catch (RuntimeException e) {
            return Mllp.ack(msg, "AE", "message rejected");
        }
    }

    private String resolveConfig(EndpointService.Identity ident, String sendingApp) {
        String first = null;
        for (JsonNode c : ident.endpoint().withArray("configs")) {
            ObjectNode cfg = store.get(ident.tenant(), "config", c.asString()).orElse(null);
            if (cfg == null || !"PUBLISHED".equals(cfg.path("status").asString())) continue;
            if (first == null) first = c.asString();
            if (sendingApp != null && sendingApp.equals(cfg.at("/params/sendingApplication").asString(null))) return c.asString();
        }
        return first;
    }

    private String adt(String tenant, DelimitedRecords r, String msg, String type) {
        String subject = r.first("PID-3.1");
        String bed = r.first("PV1-3.3");
        String prior = r.first("PV1-6.3");
        String encounter = r.first("PV1-19.1");
        switch (type) {
            case "ADT^A01", "ADT^A04" -> association.admit(tenant, bed, subject, encounter);
            case "ADT^A02" -> association.transfer(tenant, prior, bed);
            case "ADT^A03" -> association.discharge(tenant, bed == null || bed.isBlank() ? prior : bed);
            default -> { return Mllp.ack(msg, "AR", "ADT event not supported"); }
        }
        audit.record(tenant, "system:mllp", "association.adt", "bed/" + bed, Map.of("event", type));
        return Mllp.ack(msg, "AA", null);
    }

    private String order(String tenant, DelimitedRecords r, String msg) {
        String subject = r.first("PID-3.1");
        String station = r.first("PV1-3.1");
        String tpl = r.first("OBR-4.1");
        String placer = r.first("ORC-2.1");
        association.openWorklist(tenant, station, subject, tpl, placer, 240);
        return Mllp.ack(msg, "AA", null);
    }

    // ---------------------------------------------------------------- HTTPS ingress
    private void ingest(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) { respond(ex, 405, Map.of("error", "method not allowed")); return; }
            X509Certificate cert = (X509Certificate) ((HttpsExchange) ex).getSSLSession().getPeerCertificates()[0];
            Optional<EndpointService.Identity> id = endpoints.authenticate(cert);
            String remote = host(String.valueOf(ex.getRemoteAddress()));
            if (id.isEmpty() || !id.get().active()) {
                anomalies.signal(id.map(EndpointService.Identity::tenant).orElse(DocumentStore.GLOBAL),
                        id.map(EndpointService.Identity::endpointId).orElse("unknown@" + remote), "UNKNOWN_IDENTITY", "ingress with non-active certificate");
                respond(ex, 403, Map.of("error", "endpoint not active"));
                return;
            }
            byte[] body = ex.getRequestBody().readNBytes(2_000_001);
            if (body.length > 2_000_000) { respond(ex, 413, Map.of("error", "payload too large")); return; }
            JsonNode env;
            try { env = Json.parse(new String(body, StandardCharsets.UTF_8)); } catch (RuntimeException e) { respond(ex, 400, Map.of("error", "invalid envelope")); return; }
            EndpointService.Identity ident = id.get();
            String cfg = env.path("configId").asString(null);
            boolean bound = false;
            for (JsonNode c : ident.endpoint().withArray("configs")) bound |= c.asString().equals(cfg);
            boolean harness = "HARNESS".equals(ident.endpoint().path("type").asString());
            if (!bound && !harness) { respond(ex, 403, Map.of("error", "configuration not bound to this endpoint")); return; }
            endpoints.touch(ident.tenant(), ident.endpointId(), remote);
            String mode = harness ? env.path("mode").asString("TEST") : "LIVE";
            Envelope e = new Envelope(ident.tenant(), ident.endpointId(), cfg, env.path("option").asString(""), env.path("payload").asString(""),
                    mode, harness ? env.path("testSubject").asString(null) : null, harness ? env.path("runId").asString(null) : null, remote, java.time.Instant.now());
            PipelineResult r = Current.asSystem(ident.tenant(), "ingress", () -> pipeline.process(e));
            Map<String, Object> out = new java.util.LinkedHashMap<>(r.summary());
            out.put("tls", ((HttpsExchange) ex).getSSLSession().getProtocol() + " " + ((HttpsExchange) ex).getSSLSession().getCipherSuite());
            respond(ex, "REJECTED".equals(r.status) ? 422 : 200, out);
        } catch (RuntimeException e) {
            LOG.warn("ingress error {}", e.getClass().getSimpleName());
            try { respond(ex, 500, Map.of("error", "internal")); } catch (IOException ignored) { }
        }
    }

    private static void respond(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Strict-Transport-Security", "max-age=31536000");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    private static String host(String remote) {
        String r = remote.replace("/", "");
        int i = r.lastIndexOf(':');
        return i > 0 ? r.substring(0, i) : r;
    }

    static String[] protocols() { return Arrays.copyOf(PROTOCOLS, PROTOCOLS.length); }
}
