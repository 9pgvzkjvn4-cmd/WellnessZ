package com.wellnessz.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.wellnessz.gateway.MockEngine;
import com.wellnessz.gateway.TestPki;
import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.core.Backoff;
import com.wellnessz.gateway.core.CoreClient;
import com.wellnessz.gateway.crypto.GatewayKeys;
import com.wellnessz.gateway.support.Hashes;

import tools.jackson.databind.JsonNode;

class EnrolmentClientTest {

    @TempDir
    Path tmp;

    private MockEngine engine;
    private Path keyStore;
    private GatewayProperties props;
    private ClientCertificate cert;
    private EnrolmentClient client;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        engine = new MockEngine();
        keyStore = TestPki.clientKeyStore(tmp.resolve("pki"), "EP-TST-GW-01");
        props = bind(Map.of(
                "wz.gateway.core-url", engine.url(),
                "wz.gateway.data-dir", tmp.resolve("data").toString(),
                "wz.gateway.hostname", "gw-test-01",
                "wz.gateway.tls.key-store", keyStore.toString(),
                "wz.gateway.tls.key-store-password", TestPki.PASSWORD,
                "wz.gateway.tls.simulate-ingress", "true"));
        cert = ClientCertificate.load(props.tls());
        CoreClient core = new CoreClient(props, cert, () -> null, Clock.systemUTC());
        client = new EnrolmentClient(core, cert, props, () -> Map.of("baseline", "CIS-L1", "status", "PASS"),
                Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    static GatewayProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("wz.gateway",
                GatewayProperties.class);
    }

    @Test
    void enrolmentSendsCertificateProofAndThumbprintAndReturnsTheIdentity() throws Exception {
        EndpointIdentity id = client.enrol(MockEngine.ENROLMENT_TOKEN);
        assertThat(id.endpointId()).isEqualTo(MockEngine.ENDPOINT_ID);
        assertThat(id.endpointToken()).isEqualTo("device-token-initial-0123456789abcdef");
        assertThat(id.tokenExpiresAt()).isNotNull();
        assertThat(id.certificateSha256()).isEqualTo(cert.thumbprint());
        assertThat(id.toString()).doesNotContain(id.endpointToken());

        JsonNode req = engine.enrolments.get(0);
        assertThat(req.path("endpointKind").asString()).isEqualTo("GATEWAY");
        assertThat(req.path("hostname").asString()).isEqualTo("gw-test-01");
        assertThat(req.path("certificatePem").asString()).startsWith("-----BEGIN CERTIFICATE-----");
        assertThat(req.path("hardening").path("status").asString()).isEqualTo("PASS");
        // ingress simulation: thumbprint header = SHA-256 of the DER certificate
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keyStore)) {
            ks.load(in, TestPki.PASSWORD.toCharArray());
        }
        X509Certificate x509 = (X509Certificate) ks.getCertificate("gw");
        String thumb = Hashes.sha256Hex(x509.getEncoded());
        assertThat(engine.enrolmentHeaders.get(0)).containsEntry("x-client-cert-sha256", thumb);
        // proof of possession verifies with the certificate's public key
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initVerify(x509.getPublicKey());
        sig.update(("enrol:" + MockEngine.ENROLMENT_TOKEN + ":" + thumb).getBytes(StandardCharsets.UTF_8));
        assertThat(sig.verify(Base64.getDecoder().decode(req.path("proof").asString()))).isTrue();
    }

    @Test
    void transientFailuresAreRetriedWithBackoff() throws Exception {
        engine.enrolFailuresBeforeSuccess = 3;
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30), new SplittableRandom(7));
        EndpointIdentity id = client.enrolWithRetry(MockEngine.ENROLMENT_TOKEN, backoff, 0, sleeps::add);
        assertThat(id.endpointId()).isEqualTo(MockEngine.ENDPOINT_ID);
        assertThat(engine.enrolments).hasSize(4);
        assertThat(sleeps).hasSize(3);
        assertThat(sleeps.get(0)).isBetween(Duration.ofMillis(500), Duration.ofSeconds(1));
        assertThat(sleeps.get(2)).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void deterministicRefusalIsNotRetried() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30), new SplittableRandom(7));
        assertThatThrownBy(() -> client.enrolWithRetry("wrong-token", backoff, 0, sleeps::add))
                .isInstanceOf(EnrolmentException.class)
                .satisfies(e -> {
                    EnrolmentException ee = (EnrolmentException) e;
                    assertThat(ee.code()).isEqualTo("enrolment_token_invalid");
                    assertThat(ee.status()).isEqualTo(401);
                    assertThat(ee.retryable()).isFalse();
                });
        assertThat(engine.enrolments).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void maxAttemptsBoundsTheRetryLoop() {
        engine.enrolFailuresBeforeSuccess = 10;
        Backoff backoff = new Backoff(Duration.ofMillis(10), Duration.ofMillis(50), new SplittableRandom(1));
        assertThatThrownBy(() -> client.enrolWithRetry(MockEngine.ENROLMENT_TOKEN, backoff, 3, sleeps::add))
                .isInstanceOf(EnrolmentException.class);
        assertThat(engine.enrolments).hasSize(3);
    }

    @Test
    void unreachableCoreIsRetryable() {
        engine.close();
        assertThatThrownBy(() -> client.enrol(MockEngine.ENROLMENT_TOKEN))
                .isInstanceOf(EnrolmentException.class)
                .satisfies(e -> assertThat(((EnrolmentException) e).retryable()).isTrue());
    }

    @Test
    void identityIsStoredEncryptedAndSurvivesRestart() throws Exception {
        GatewayKeys keys = GatewayKeys.load(n -> null, tmp.resolve("data"));
        IdentityStore store = new IdentityStore(tmp.resolve("data"), keys.cipher(GatewayKeys.IDENTITY_PURPOSE));
        assertThat(store.current()).isNull();
        EndpointIdentity id = client.enrol(MockEngine.ENROLMENT_TOKEN);
        store.save(id);
        String raw = Files.readString(tmp.resolve("data").resolve("identity.enc"));
        assertThat(raw).doesNotContain(id.endpointToken()).doesNotContain(MockEngine.ENDPOINT_ID);
        // local key file is owner-only
        Path keyFile = tmp.resolve("data").resolve("keys").resolve("gateway.key");
        if (Files.getFileStore(keyFile).supportsFileAttributeView("posix")) {
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(keyFile)))
                    .isEqualTo("rw-------");
        }
        GatewayKeys reloaded = GatewayKeys.load(n -> null, tmp.resolve("data"));
        IdentityStore again = new IdentityStore(tmp.resolve("data"), reloaded.cipher(GatewayKeys.IDENTITY_PURPOSE));
        assertThat(again.current().endpointToken()).isEqualTo(id.endpointToken());
        again.save(again.current().withToken("rotated-token-0123456789abcdef", null));
        assertThat(new IdentityStore(tmp.resolve("data"), reloaded.cipher(GatewayKeys.IDENTITY_PURPOSE)).current()
                .endpointToken()).isEqualTo("rotated-token-0123456789abcdef");
    }
}
