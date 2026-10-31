package com.wellnessz.gateway.forward;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wellnessz.gateway.MockEngine;
import com.wellnessz.gateway.TestClock;
import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.core.Backoff;
import com.wellnessz.gateway.core.CoreClient;
import com.wellnessz.gateway.crypto.GatewayKeys;
import com.wellnessz.gateway.identity.ClientCertificate;
import com.wellnessz.gateway.identity.EndpointIdentity;
import com.wellnessz.gateway.queue.EncryptedJournal;
import com.wellnessz.gateway.queue.ItemKind;
import com.wellnessz.gateway.queue.QueueItem;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ForwarderTest {

    @TempDir
    Path tmp;

    private MockEngine engine;
    private final TestClock clock = new TestClock(Instant.parse("2026-10-31T10:00:00Z"));
    private EncryptedJournal journal;
    private Forwarder forwarder;

    @BeforeEach
    void setUp() throws Exception {
        engine = new MockEngine();
        GatewayProperties props = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of(
                        "wz.gateway.core-url", engine.url(), "wz.gateway.data-dir", tmp.toString())))
                .bindOrCreate("wz.gateway", GatewayProperties.class);
        EndpointIdentity id = new EndpointIdentity(MockEngine.ENDPOINT_ID, "device-token-initial-0123456789abcdef",
                null, Instant.now(), null);
        CoreClient core = new CoreClient(props, ClientCertificate.none(props.tls()), () -> id, clock);
        journal = new EncryptedJournal(tmp.resolve("queue"), GatewayKeys.of(new byte[32]).cipher("j"),
                () -> MockEngine.ENDPOINT_ID, 1 << 24, 1 << 20, clock);
        forwarder = new Forwarder(journal, core, props.forwarder(), clock,
                new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(60), new SplittableRandom(5)),
                new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    private QueueItem hl7(int n) {
        return journal.append(ItemKind.HL7V2, "DI-1", "application/hl7-v2", Map.of(),
                ("MSH|^~\\&|DEV|F|WZ|WZ|20261031||ORU^R01|C" + n + "|P|2.5\r").getBytes(StandardCharsets.ISO_8859_1),
                null);
    }

    @Test
    void offlineBacksOffThenReplaysTheBacklogThroughTheBatchApiWithOriginalKeys() {
        engine.mode = MockEngine.Mode.DOWN_503;
        hl7(1);
        hl7(2);
        hl7(3);
        assertThat(forwarder.drain()).isZero();
        assertThat(forwarder.online()).isFalse();
        int callsAfterFailure = engine.batchCalls.get();
        Instant next = forwarder.nextAttemptAt();
        assertThat(next).isAfter(clock.instant());
        // during back-off nothing is sent
        assertThat(forwarder.drain()).isZero();
        assertThat(engine.batchCalls.get()).isEqualTo(callsAfterFailure);

        engine.mode = MockEngine.Mode.UP;
        clock.advance(Duration.ofSeconds(2));
        assertThat(forwarder.drain()).isEqualTo(3);
        assertThat(journal.stats().depth()).isZero();
        assertThat(engine.uniqueKeys).containsExactly("EP-TST-GW-01:1", "EP-TST-GW-01:2", "EP-TST-GW-01:3");
        assertThat(engine.deliveries).allSatisfy(d -> {
            assertThat(d.viaBatch()).isTrue();
            assertThat(d.headers()).containsEntry("x-replayed", "true").containsKey("x-gateway-sequence")
                    .containsKey("x-captured-at").containsEntry("x-device-instance", "DI-1");
        });
        assertThat(engine.duplicates.get()).isZero();
    }

    @Test
    void lostReceiptIsResolvedByIdempotencyKeyWithoutLossOrDuplication() {
        engine.mode = MockEngine.Mode.STORE_THEN_503; // the engine stores the batch, but the receipt is lost
        hl7(1);
        hl7(2);
        assertThat(forwarder.drain()).isZero();
        assertThat(journal.stats().depth()).isEqualTo(2); // still held: no receipt, no deletion
        clock.advance(Duration.ofSeconds(2));
        assertThat(forwarder.drain()).isEqualTo(2);
        assertThat(journal.stats().depth()).isZero();
        assertThat(engine.uniqueKeys).hasSize(2);
        assertThat(engine.duplicates.get()).isEqualTo(2); // re-sent with the same keys → recognised as DUPLICATE
    }

    @Test
    void onlineSingleItemUsesTheKindSpecificPath() {
        hl7(1);
        forwarder.drain(); // first contact (batch) establishes the online state
        QueueItem item = hl7(2);
        assertThat(forwarder.forwardNow(item)).hasValueSatisfying(r -> assertThat(r.text()).contains("MSA|AA|C2"));
        assertThat(engine.deliveries("/device-ingest/hl7v2")).last()
                .satisfies(d -> assertThat(d.viaBatch()).isFalse());
        hl7(3);
        assertThat(forwarder.drain()).isEqualTo(1);
        assertThat(engine.deliveries).last().satisfies(d -> assertThat(d.viaBatch()).isFalse());
    }

    @Test
    void deterministicRejectionIsDeadLetteredAndDoesNotBlockTheQueue() {
        engine.rejectPath = "/device-ingest/fhir";
        journal.append(ItemKind.FHIR, "DI-2", "application/fhir+json", Map.of(),
                "{\"resourceType\":\"Observation\"}".getBytes(StandardCharsets.UTF_8), null);
        hl7(2);
        assertThat(forwarder.drain()).isEqualTo(2);
        assertThat(journal.stats().depth()).isZero();
        assertThat(journal.deadLetterReasons()).containsEntry(1L, "unknown_device");
        assertThat(engine.uniqueKeys).containsExactly("EP-TST-GW-01:2");
    }

    @Test
    void isolationVerdictQuarantinesTheQueueAndStopsForwarding() {
        engine.isolated = true;
        hl7(1);
        boolean[] notified = {false};
        forwarder.onIsolated(() -> notified[0] = true);
        assertThat(forwarder.drain()).isZero();
        assertThat(journal.quarantined()).isTrue();
        assertThat(notified[0]).isTrue();
        engine.isolated = false;
        clock.advance(Duration.ofMinutes(10));
        assertThat(forwarder.drain()).isZero();
        assertThat(journal.stats().depth()).isEqualTo(1); // kept locally as evidence
    }

    @Test
    void forwardNowDefersWhenOlderItemsOfTheSameDeviceArePending() {
        engine.mode = MockEngine.Mode.DOWN_503;
        hl7(1);
        forwarder.drain();
        engine.mode = MockEngine.Mode.UP;
        clock.advance(Duration.ofSeconds(5));
        QueueItem second = hl7(2);
        assertThat(forwarder.forwardNow(second)).isEmpty(); // keeps per-instance FIFO
        assertThat(forwarder.drain()).isEqualTo(2);
        assertThat(engine.keys()).containsExactly("EP-TST-GW-01:1", "EP-TST-GW-01:2");
    }
}
