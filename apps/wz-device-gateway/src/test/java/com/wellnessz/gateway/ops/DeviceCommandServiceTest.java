package com.wellnessz.gateway.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.wellnessz.gateway.MockEngine;
import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.Connector;
import com.wellnessz.gateway.connector.IngestSink;
import com.wellnessz.gateway.connector.db.VendorDbPoller;
import com.wellnessz.gateway.connector.mllp.MllpClient;
import com.wellnessz.gateway.connector.mllp.MllpFraming;
import com.wellnessz.gateway.connector.serial.SerialConnector;
import com.wellnessz.gateway.connector.serial.SerialSource;
import com.wellnessz.gateway.core.CoreClient;
import com.wellnessz.gateway.crypto.GatewayKeys;
import com.wellnessz.gateway.forward.Forwarder;
import com.wellnessz.gateway.identity.ClientCertificate;
import com.wellnessz.gateway.identity.EndpointIdentity;
import com.wellnessz.gateway.queue.EncryptedJournal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Device-bound gateway commands (E31-HLR-08, E31-BL-004 / -016 / -019): HL7 worklist push over MLLP with the device's ACK,
 * start-from-form on a serial-over-TCP device (start bytes and ready reply) and over MLLP, historic retrieval over MLLP
 * (QRY^R02 → ORU frames → result batch) and from a vendor database (range query, CSV rows), and the refusals.
 */
class DeviceCommandServiceTest {

    static final String ORDER = "MSH|^~\\&|WELLNESSZ||MUSE||20261101090000||ORM^O01^ORM_O01|WZ1-0|P|2.5\rPID|1||E1001^^^WELLNESSZ^EI||Doe^Jane\r"
            + "ORC|NW|ORD-1^WELLNESSZ\rOBR|1|ORD-1^WELLNESSZ||ECG^ECG measurement^WZ\r";
    static final String ORU = "MSH|^~\\&|MUSE|LHR|WELLNESSZ||20261030101500||ORU^R01^ORU_R01|MUSE-%d|P|2.5\rPID|1||E1001^^^WZ\r"
            + "OBX|1|NM|8867-4^Heart rate^LN||%d|/min|||||F\r";

    @TempDir
    Path tmp;
    MockEngine engine;
    CoreClient core;
    final List<String> deviceReceived = new CopyOnWriteArrayList<>();
    final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        engine = new MockEngine();
        GatewayProperties props = new Binder(new MapConfigurationPropertySource(Map.of("wz.gateway.core-url", engine.url(),
                "wz.gateway.data-dir", tmp.toString()))).bindOrCreate("wz.gateway", GatewayProperties.class);
        EndpointIdentity id = new EndpointIdentity(MockEngine.ENDPOINT_ID, "device-token-initial-0123456789abcdef", null, Instant.now(), null);
        core = new CoreClient(props, ClientCertificate.none(props.tls()), () -> id, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
        engine.close();
    }

    GatewayProperties.MllpOutbound target(String instance, int port) {
        return new Binder(new MapConfigurationPropertySource(Map.of("t.id", "out-1", "t.device-instance-id", instance, "t.host", "127.0.0.1",
                "t.port", String.valueOf(port), "t.query-idle-timeout", "PT0.5S", "t.ack-timeout", "PT3S")))
                .bindOrCreate("t", GatewayProperties.MllpOutbound.class);
    }

    /** A device that acknowledges every message (AA, or AE for a message containing "REJECT"); for QRY it answers with results. */
    int fakeMllpDevice() throws Exception {
        ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        closeables.add(server);
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    byte[] msg = new MllpFraming.Reader(s.getInputStream(), 1 << 20).next();
                    String text = new String(msg, StandardCharsets.ISO_8859_1);
                    deviceReceived.add(text);
                    String control = text.split("\r")[0].split("\\|")[9];
                    OutputStream out = s.getOutputStream();
                    String code = text.contains("REJECT") ? "AE" : "AA";
                    out.write(MllpFraming.encode(("MSH|^~\\&|MUSE||WELLNESSZ||20261101090001||ACK^O01|A1|P|2.5\rMSA|" + code + "|" + control + "\r")
                            .getBytes(StandardCharsets.ISO_8859_1)));
                    if (MllpClient.messageType(msg).startsWith("QRY")) {
                        for (int i = 1; i <= 2; i++) {
                            out.write(MllpFraming.encode(String.format(ORU, i, 70 + i).getBytes(StandardCharsets.ISO_8859_1)));
                        }
                    }
                    out.flush();
                    Thread.sleep(800); // keep the connection open past the idle timeout
                } catch (Exception e) {
                    // test server closed
                }
            }
        });
        return server.getLocalPort();
    }

    DeviceCommandService service(List<GatewayProperties.MllpOutbound> targets, List<Connector> connectors) {
        GatewayProperties.Worklist wl = new GatewayProperties.Worklist(tmp.resolve("gdt"), "WZGW", "DEVICE");
        return new DeviceCommandService(core, new WorklistService(core, wl), targets, false, () -> connectors);
    }

    @Test
    void hl7WorklistIsPushedOverMllpAndEachAcknowledgementIsReported() throws Exception {
        int port = fakeMllpDevice();
        engine.outbound.put("cmd-1", "{\"transport\":\"MLLP\",\"messages\":[{\"controlId\":\"WZ1-0\",\"messageType\":\"ORM^O01\",\"er7\":\""
                + ORDER.replace("\\", "\\\\").replace("\r", "\\r") + "\"},{\"controlId\":\"WZ1-1\",\"messageType\":\"ORM^O01\",\"er7\":\""
                + ORDER.replace("ORD-1", "REJECT").replace("WZ1-0", "WZ1-1").replace("\\", "\\\\").replace("\r", "\\r") + "\"}]}");
        CommandProcessor.Result r = service(List.of(target("DEV-ECG", port)), List.of()).pushWorklist("cmd-1", "DEV-ECG", LocalDate.of(2026, 11, 1));
        assertThat(r.status()).isEqualTo("FAILED"); // one of two orders rejected by the device
        assertThat(r.detail()).isEqualTo("1/2 worklist messages acknowledged");
        assertThat(r.acks()).extracting(a -> a.get("controlId") + "=" + a.get("code")).containsExactly("WZ1-0=AA", "WZ1-1=AE");
        assertThat(deviceReceived).hasSize(2).allMatch(m -> m.startsWith("MSH|^~\\&|WELLNESSZ") && m.contains("ORC|NW"));
        // no outbound target configured for another device: refused at the edge (the core never names a host)
        CommandProcessor.Result none = service(List.of(target("DEV-ECG", port)), List.of()).pushWorklist("cmd-1", "DEV-OTHER", LocalDate.now());
        assertThat(none.detail()).isEqualTo("no_mllp_outbound_target");
    }

    @Test
    void gdtTransportKeepsTheImportFilePath() throws Exception {
        engine.outbound.put("cmd-g", "{\"transport\":\"GDT\",\"messages\":[]}");
        CommandProcessor.Result r = service(List.of(), List.of()).pushWorklist("cmd-g", "DEV-SPI", LocalDate.now());
        assertThat(r.status()).isEqualTo("DONE"); // the GDT path pulls /device-ingest/worklist itself and writes GDT 6302 files
        assertThat(r.detail()).isEqualTo("1 worklist entries written");
        assertThat(tmp.resolve("gdt").resolve("WZ_ORD-1.gdt")).exists();
    }

    @Test
    void startFromFormWritesTheStartBytesToTheSerialDeviceAndWaitsForItsReply() throws Exception {
        ServerSocket terminal = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        closeables.add(terminal);
        Thread.ofVirtual().start(() -> {
            try (Socket s = terminal.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                String line = in.readLine();
                deviceReceived.add(line);
                s.getOutputStream().write("READY\r\nTEST#0042\r\n".getBytes(StandardCharsets.ISO_8859_1));
                s.getOutputStream().flush();
                Thread.sleep(2000);
            } catch (Exception e) {
                // closed
            }
        });
        GatewayProperties.Serial cfg = new GatewayProperties.Serial("serial-1", "DEV-BRE", GatewayProperties.SerialMode.TCP, "127.0.0.1",
                terminal.getLocalPort(), null, "COM1", GatewayProperties.Framing.LINE, 3, "TEXT", 65536, java.time.Duration.ofSeconds(1));
        EncryptedJournal journal = new EncryptedJournal(tmp.resolve("queue"), GatewayKeys.of(new byte[32]).cipher("j"), () -> MockEngine.ENDPOINT_ID,
                1 << 24, 1 << 20, Clock.systemUTC());
        closeables.add(journal::close);
        IngestSink sink = new IngestSink(journal, new Forwarder(journal, core,
                new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("f", GatewayProperties.Forwarder.class), Clock.systemUTC(),
                new com.wellnessz.gateway.core.Backoff(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(5), new java.util.SplittableRandom(1)),
                new SimpleMeterRegistry()), new SimpleMeterRegistry());
        SerialConnector serial = new SerialConnector(cfg, SerialConnector.sourceFor(cfg), sink, Clock.systemUTC());
        serial.start();
        closeables.add(serial::stop);
        for (int i = 0; i < 50 && !"CONNECTED".equals(serial.status().state().name()); i++) {
            Thread.sleep(50);
        }
        engine.outbound.put("cmd-s", "{\"transport\":\"SERIAL\",\"controlId\":\"WZS-0\",\"payloadBase64\":\""
                + Base64.getEncoder().encodeToString("START\r\n".getBytes(StandardCharsets.ISO_8859_1)) + "\",\"expectPattern\":\"^(READY|BLOW)\"}");
        CommandProcessor.Result r = service(List.of(), List.of(serial)).startAcquisition("cmd-s", "DEV-BRE");
        assertThat(r.status()).isEqualTo("DONE");
        assertThat(r.acks()).singleElement().satisfies(a -> assertThat(a).containsEntry("controlId", "WZS-0").containsEntry("code", "CA"));
        assertThat(deviceReceived).containsExactly("START");
        // the READY reply is control traffic; the next frame is a reading and is queued for the core
        for (int i = 0; i < 40 && journal.stats().depth() == 0; i++) {
            Thread.sleep(50);
        }
        assertThat(journal.stats().depth()).isEqualTo(1);
        // the replay simulation has no transmit path: not supported by that connector
        GatewayProperties.Serial sim = new GatewayProperties.Serial("serial-sim", "DEV-SIM", GatewayProperties.SerialMode.FILE, null, 0,
                tmp.resolve("none.txt"), "SIM", GatewayProperties.Framing.LINE, 3, "TEXT", 65536, java.time.Duration.ofSeconds(1));
        SerialConnector simulated = new SerialConnector(sim, new SerialSource.Stream(tmp.resolve("none.txt")), sink, Clock.systemUTC());
        assertThat(service(List.of(), List.of(simulated)).startAcquisition("cmd-s", "DEV-SIM").detail()).isEqualTo("not_supported_by_connector");
    }

    @Test
    void startFromFormOverMllpSendsTheSessionOrder() throws Exception {
        int port = fakeMllpDevice();
        engine.outbound.put("cmd-m", "{\"transport\":\"MLLP\",\"messages\":[{\"controlId\":\"WZ1-0\",\"messageType\":\"ORM^O01\",\"er7\":\""
                + ORDER.replace("\\", "\\\\").replace("\r", "\\r") + "\"}]}");
        CommandProcessor.Result r = service(List.of(target("DEV-ECG", port)), List.of()).startAcquisition("cmd-m", "DEV-ECG");
        assertThat(r.status()).isEqualTo("DONE");
        assertThat(r.detail()).isEqualTo("1/1 start messages acknowledged");
    }

    @Test
    void historicQueryOverMllpReturnsTheResultFramesThroughTheRetrievalBatch() throws Exception {
        int port = fakeMllpDevice();
        String retrieval = "6f1c0f2e-0000-4000-8000-000000000001";
        String query = "MSH|^~\\&|WELLNESSZ||MUSE||20261101090000||QRY^R02^QRY_R02|WZQ1|P|2.5\rQRD|20261101090000|R|I|q1|||100^RD|MAC-1|RES|ALL\r"
                + "QRF|MAC-1|20261001000000|20261031000000\r";
        engine.outbound.put("cmd-h", "{\"retrievalId\":\"" + retrieval + "\",\"method\":\"HL7_QUERY\",\"transport\":\"MLLP\",\"messages\":[{\"controlId\":"
                + "\"WZQ1\",\"messageType\":\"QRY^R02\",\"er7\":\"" + query.replace("\\", "\\\\").replace("\r", "\\r") + "\"}]}");
        CommandProcessor.Result r = service(List.of(target("DEV-ECG", port)), List.of()).retrieveHistory("cmd-h", "DEV-ECG");
        assertThat(r.status()).isEqualTo("DONE");
        assertThat(r.detail()).startsWith("2 results returned (2 accepted");
        assertThat(engine.retrievalBatches).singleElement().satisfies(b -> {
            assertThat(b.path("last").asBoolean()).isTrue();
            assertThat(b.path("items")).hasSize(2).allMatch(i -> "HL7V2".equals(i.path("kind").asString())
                    && i.path("body").asString().contains("ORU^R01") && i.path("idempotencyKey").asString().startsWith("HQ:"));
        });
        assertThat(deviceReceived).singleElement().satisfies(q -> assertThat(q).contains("QRY^R02").contains("QRF|MAC-1|20261001000000"));
    }

    @Test
    void historicRetrievalFromTheVendorDatabaseIsBoundedByTheRange() throws Exception {
        String url = "jdbc:h2:mem:hist;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement st = c.createStatement()) {
            st.execute("create table v_wz_audiogram (id int, employee varchar(10), modified_at timestamp, l1000 int)");
            st.execute("insert into v_wz_audiogram values (1, 'E1001', timestamp '2026-10-01 10:00:00', 10), (2, 'E1002', timestamp '2026-10-15 10:00:00', 15),"
                    + " (3, 'E1003', timestamp '2026-10-31 10:00:00', 20)");
        }
        GatewayProperties.DbPoller cfg = new Binder(new MapConfigurationPropertySource(Map.of("d.id", "db-1", "d.device-instance-id", "DEV-AUD",
                "d.url", url, "d.username", "sa", "d.password", "", "d.query", "select * from v_wz_audiogram where modified_at > ? order by modified_at",
                "d.watermark-column", "MODIFIED_AT"))).bindOrCreate("d", GatewayProperties.DbPoller.class);
        VendorDbPoller db = new VendorDbPoller(cfg, null, Clock.systemUTC(), tmp, null);
        engine.outbound.put("cmd-d", "{\"retrievalId\":\"6f1c0f2e-0000-4000-8000-000000000002\",\"method\":\"VENDOR_DB\",\"transport\":\"VENDOR_DB\","
                + "\"from\":\"2026-10-02T00:00:00Z\",\"to\":\"2026-10-20T00:00:00Z\",\"messages\":[]}");
        CommandProcessor.Result r = service(List.of(), List.of(db)).retrieveHistory("cmd-d", "DEV-AUD");
        assertThat(r.status()).isEqualTo("DONE");
        assertThat(r.detail()).startsWith("1 results returned");
        assertThat(engine.retrievalBatches).singleElement().satisfies(b -> {
            assertThat(b.path("items")).singleElement().satisfies(i -> {
                assertThat(i.path("kind").asString()).isEqualTo("FILE");
                assertThat(new String(Base64.getDecoder().decode(i.path("body").asString()), StandardCharsets.UTF_8)).contains("E1002").doesNotContain("E1001");
                assertThat(i.path("headers").path("X-File-Format").asString()).isEqualTo("CSV");
            });
        });
        // the regular poll's watermark is untouched by a historic retrieval
        assertThat(db.watermark()).isEqualTo("0");
    }
}
