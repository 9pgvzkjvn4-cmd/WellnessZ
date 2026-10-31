package com.wellnessz.gateway.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.wellnessz.gateway.TestClock;
import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.ble.BleIntakeServer;
import com.wellnessz.gateway.connector.dicom.DicomDropConnector;
import com.wellnessz.gateway.connector.dicom.DicomMetadata;
import com.wellnessz.gateway.connector.poct.PoctListenerAccess;
import com.wellnessz.gateway.connector.serial.SerialConnector;
import com.wellnessz.gateway.connector.serial.SerialFramer;
import com.wellnessz.gateway.crypto.GatewayKeys;
import com.wellnessz.gateway.queue.EncryptedJournal;
import com.wellnessz.gateway.queue.ItemKind;
import com.wellnessz.gateway.queue.QueueItem;
import com.wellnessz.gateway.support.Hashes;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ConnectorsTest {

    @TempDir
    Path tmp;

    private final TestClock clock = new TestClock(Instant.parse("2026-10-31T11:00:00Z"));
    private final JsonMapper json = JsonMapper.builder().build();
    private EncryptedJournal journal;
    private IngestSink sink;

    @BeforeEach
    void setUp() {
        journal = new EncryptedJournal(tmp.resolve("queue"), GatewayKeys.of(new byte[32]).cipher("j"),
                () -> "EP-TST-GW-01", 1 << 24, 1 << 20, clock);
        sink = new IngestSink(journal, null, new SimpleMeterRegistry());
    }

    static GatewayProperties props(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("wz.gateway",
                GatewayProperties.class);
    }

    // ------------------------------------------------------------------ serial

    @Test
    void serialFramerSupportsLineStxEtxAndTerminatorFraming() throws Exception {
        SerialFramer line = new SerialFramer(new ByteArrayInputStream("A=1\r\nB=2\n\nC=3".getBytes()),
                GatewayProperties.Framing.LINE, 0, 100);
        assertThat(new String(line.next())).isEqualTo("A=1");
        assertThat(new String(line.next())).isEqualTo("B=2");
        assertThat(new String(line.next())).isEqualTo("C=3");
        assertThat(line.next()).isNull();

        byte[] stx = {'x', 0x02, 'B', 'A', 'C', '0', '.', '0', '2', 0x03, 0x41, 0x02, 'O', 'K', 0x03, 0x02, 'Z'};
        SerialFramer f = new SerialFramer(new ByteArrayInputStream(stx), GatewayProperties.Framing.STX_ETX, 0, 100);
        assertThat(new String(f.next())).isEqualTo("BAC0.02");
        assertThat(new String(f.next())).isEqualTo("OK");
        assertThat(f.next()).isNull(); // incomplete trailing frame discarded

        SerialFramer t = new SerialFramer(new ByteArrayInputStream("a;b;".getBytes()),
                GatewayProperties.Framing.TERMINATOR, ';', 100);
        assertThat(new String(t.next())).isEqualTo("a");
        assertThat(new String(t.next())).isEqualTo("b");
    }

    @Test
    void serialSimulationFromFileQueuesOneRecordPerFrame() throws Exception {
        Path capture = tmp.resolve("breathalyser.txt");
        Files.writeString(capture, "BAC 0.000 g/210L\r\nBAC 0.012 g/210L\r\n");
        GatewayProperties p = props(Map.of("wz.gateway.serial[0].id", "bac-01",
                "wz.gateway.serial[0].device-instance-id", "DI-BAC-01", "wz.gateway.serial[0].mode", "FILE",
                "wz.gateway.serial[0].file", capture.toString(), "wz.gateway.serial[0].port-name", "COM3"));
        GatewayProperties.Serial cfg = p.serial().get(0);
        SerialConnector c = new SerialConnector(cfg, SerialConnector.sourceFor(cfg), sink, clock);
        c.start();
        c.join(5000);
        List<QueueItem> items = journal.pending(10, Long.MAX_VALUE);
        assertThat(items).hasSize(2);
        JsonNode rec = json.readTree(items.get(1).body()).path("records").get(0);
        assertThat(items.get(0).kind()).isEqualTo(ItemKind.SERIAL);
        assertThat(rec.path("port").asString()).isEqualTo("COM3");
        assertThat(rec.path("encoding").asString()).isEqualTo("TEXT");
        assertThat(rec.path("data").asString()).isEqualTo("BAC 0.012 g/210L");
        assertThat(rec.path("deviceInstanceId").asString()).isEqualTo("DI-BAC-01");
        assertThat(c.status().state()).isEqualTo(ConnectorStatus.State.IDLE);
    }

    // ------------------------------------------------------------------ BLE

    private BleIntakeServer ble(List<String> allow) {
        GatewayProperties p = props(Map.of("wz.gateway.ble.enabled", "true",
                "wz.gateway.ble.relay-key", "relay-key-0123456789abcdef"));
        BleIntakeServer s = new BleIntakeServer(p.ble(), sink, clock, false, "salt-1");
        s.allowList(allow);
        return s;
    }

    private static byte[] bleBody(String peripheral) {
        return ("{\"records\":[{\"peripheral\":" + peripheral + ",\"service\":\"0x1810\",\"characteristic\":\"0x2A35\","
                + "\"encoding\":\"HEX\",\"value\":\"1E7800500000\",\"receivedAt\":\"2026-10-31T10:59:00Z\","
                + "\"sessionId\":\"CS-1\"}]}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void bleRawMacAddressIsReplacedByItsSaltedHashBeforeQueuing() throws Exception {
        BleIntakeServer s = ble(List.of());
        BleIntakeServer.Result r = s.accept(bleBody("{\"address\":\"a4:c1:38:00:11:22\",\"name\":\"BP-cuff\"}"));
        assertThat(r.status()).isEqualTo(202);
        QueueItem item = journal.pending(1, Long.MAX_VALUE).get(0);
        String body = new String(item.body(), StandardCharsets.UTF_8);
        assertThat(body).doesNotContainIgnoringCase("a4:c1:38").doesNotContain("\"address\"");
        JsonNode rec = json.readTree(body).path("records").get(0);
        assertThat(rec.path("peripheral").path("addressHash").asString())
                .isEqualTo(Hashes.sha256Hex("A4:C1:38:00:11:22" + "salt-1"));
        assertThat(rec.path("sessionId").asString()).isEqualTo("CS-1");
        assertThat(item.kind()).isEqualTo(ItemKind.BLE);
    }

    @Test
    void bleAllowListAndValidationAreEnforced() {
        String hash = "ab".repeat(32);
        BleIntakeServer s = ble(List.of("BOND-7"));
        assertThat(s.accept(bleBody("{\"addressHash\":\"" + hash + "\"}")).status()).isEqualTo(403);
        assertThat(s.accept(bleBody("{\"addressHash\":\"" + hash + "\",\"bondId\":\"BOND-7\"}")).status())
                .isEqualTo(202);
        assertThat(s.accept("{\"records\":[]}".getBytes()).status()).isEqualTo(400);
        assertThat(s.accept(bleBody("{\"address\":\"not-a-mac\"}")).status()).isEqualTo(400);
        assertThat(s.accept("not json".getBytes()).status()).isEqualTo(400);
        assertThat(journal.stats().depth()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ POCT1-A

    @Test
    void poctDocumentsAreDelimitedByTheRootElementAndAcknowledgedLocallyWhenOffline() throws Exception {
        String obs = "<?xml version=\"1.0\"?>\n<OBS.R01><HDR><HDR.control_id V=\"4711\"/></HDR>"
                + "<SVC><PT><OBS><OBS.observation_id V=\"HbA1c\"/><OBS.value V=\"5.4\"/></OBS></PT></SVC></OBS.R01>";
        String dst = "<DST.R01><HDR><HDR.control_id V=\"4712\"/></HDR></DST.R01>";
        ByteArrayInputStream in = new ByteArrayInputStream((obs + "\r\n" + dst).getBytes(StandardCharsets.UTF_8));
        assertThat(new String(PoctListenerAccess.read(in), StandardCharsets.UTF_8)).isEqualTo(obs);
        assertThat(new String(PoctListenerAccess.read(in), StandardCharsets.UTF_8)).isEqualTo(dst);
        assertThat(PoctListenerAccess.read(in)).isNull();

        String ack = new String(PoctListenerAccess.process(sink, clock, obs.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        assertThat(ack).contains("<ACK.type_cd V=\"AA\"/>").contains("<ACK.ack_control_id V=\"4711\"/>");
        assertThat(journal.pending(1, Long.MAX_VALUE).get(0).kind()).isEqualTo(ItemKind.POCT1A);
    }

    // ------------------------------------------------------------------ DICOM

    @Test
    void dicomMetadataIsExtractedFromAPart10Header() {
        byte[] dcm = syntheticDicom();
        assertThat(DicomMetadata.isPart10(dcm)).isTrue();
        JsonNode ds = DicomMetadata.extract(dcm, json);
        assertThat(ds.path("00080018").path("vr").asString()).isEqualTo("UI");
        assertThat(ds.path("00080018").path("Value").get(0).asString()).isEqualTo("1.2.826.0.1.3680043.10.1.1");
        assertThat(ds.path("00080060").path("Value").get(0).asString()).isEqualTo("DX");
        assertThat(ds.path("00100010").path("Value").get(0).path("Alphabetic").asString()).isEqualTo("Synthetic^Pat");
        assertThat(ds.path("00200013").path("Value").get(0).asLong()).isEqualTo(7);
        assertThat(ds.has("7FE00010")).isFalse();
    }

    @Test
    void dicomDropQueuesInstanceAndMetadataAndQuarantinesNonDicom() throws Exception {
        Path drop = Files.createDirectories(tmp.resolve("drop"));
        Files.write(drop.resolve("cxr-1.dcm"), syntheticDicom());
        Files.writeString(drop.resolve("broken.dcm"), "nope");
        GatewayProperties p = props(Map.of("wz.gateway.dicom[0].id", "cxr", "wz.gateway.dicom[0].path",
                drop.toString(), "wz.gateway.dicom[0].archive", tmp.resolve("arch").toString(),
                "wz.gateway.dicom[0].quarantine", tmp.resolve("quar").toString(),
                "wz.gateway.dicom[0].stability-seconds", "0", "wz.gateway.dicom[0].device-instance-id", "DI-CXR-1"));
        DicomDropConnector c = new DicomDropConnector(p.dicom().get(0), sink, clock, tmp.resolve("data"));
        assertThat(c.poll()).isEqualTo(1);
        QueueItem item = journal.pending(1, Long.MAX_VALUE).get(0);
        assertThat(item.kind()).isEqualTo(ItemKind.DICOM);
        JsonNode env = json.readTree(item.body());
        assertThat(env.path("metadata").get(0).path("0020000D").path("Value").get(0).asString())
                .isEqualTo("1.2.826.0.1.3680043.10.1");
        assertThat(env.path("sha256").asString()).isEqualTo(item.idempotencyKey());
        assertThat(drop.resolve("cxr-1.dcm")).doesNotExist();
        assertThat(tmp.resolve("quar").resolve("broken.dcm")).exists();
    }

    /** Minimal explicit VR little endian Part 10 file with synthetic attributes and a pixel data stub. */
    static byte[] syntheticDicom() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[128]);
        out.writeBytes("DICM".getBytes(StandardCharsets.US_ASCII));
        element(out, 0x0002, 0x0010, "UI", "1.2.840.10008.1.2.1\0");
        element(out, 0x0008, 0x0016, "UI", "1.2.840.10008.5.1.4.1.1.1.1");
        element(out, 0x0008, 0x0018, "UI", "1.2.826.0.1.3680043.10.1.1");
        element(out, 0x0008, 0x0060, "CS", "DX");
        // an undefined-length sequence that must be skipped
        ByteBuffer sq = ByteBuffer.allocate(12 + 8 + 8).order(ByteOrder.LITTLE_ENDIAN);
        sq.putShort((short) 0x0008).putShort((short) 0x1110).put("SQ".getBytes()).putShort((short) 0).putInt(-1);
        sq.putShort((short) 0xFFFE).putShort((short) 0xE000).putInt(0);
        sq.putShort((short) 0xFFFE).putShort((short) 0xE0DD).putInt(0);
        out.writeBytes(sq.array());
        element(out, 0x0010, 0x0010, "PN", "Synthetic^Pat");
        element(out, 0x0010, 0x0020, "LO", "SYN-0001");
        element(out, 0x0020, 0x000D, "UI", "1.2.826.0.1.3680043.10.1");
        element(out, 0x0020, 0x0013, "IS", "7 ");
        element(out, 0x7FE0, 0x0010, "OB", "\0\0\0\0");
        return out.toByteArray();
    }

    private static void element(ByteArrayOutputStream out, int group, int elem, String vr, String value) {
        byte[] v = value.getBytes(StandardCharsets.ISO_8859_1);
        if (v.length % 2 == 1) {
            byte[] padded = new byte[v.length + 1];
            System.arraycopy(v, 0, padded, 0, v.length);
            padded[v.length] = "UI".equals(vr) ? 0 : (byte) ' ';
            v = padded;
        }
        boolean longVr = List.of("OB", "OW", "SQ", "UN", "UT").contains(vr);
        ByteBuffer b = ByteBuffer.allocate(8 + (longVr ? 4 : 0) + v.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) group).putShort((short) elem).put(vr.getBytes(StandardCharsets.US_ASCII));
        if (longVr) {
            b.putShort((short) 0).putInt(v.length);
        } else {
            b.putShort((short) v.length);
        }
        b.put(v);
        out.writeBytes(b.array());
    }
}
