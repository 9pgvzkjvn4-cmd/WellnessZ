package com.wellnessz.gateway.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the edge gateway / Device Host agent ({@code wz.gateway.*}). Secrets (key-store passwords, the
 * enrolment token, vendor database passwords, the BLE relay key) may be written as {@code ENC(...)} or {@code file:...}
 * references; wz-secrets resolves them before binding. Connector topology is local configuration; limits that the core
 * distributes through {@code GET /device-ingest/config} (heartbeat interval, queue limits, BLE allow-list, log level)
 * override the local values at run time.
 */
@ConfigurationProperties("wz.gateway")
public record GatewayProperties(
        Role role,
        String coreUrl,
        Path dataDir,
        String endpointKind,
        String hostname,
        boolean allowPlaintextListeners,
        boolean releaseQuarantine,
        @DefaultValue Enrolment enrolment,
        @DefaultValue ClientTls tls,
        @DefaultValue Queue queue,
        @DefaultValue Forwarder forwarder,
        @DefaultValue Heartbeat heartbeat,
        List<MllpListener> mllp,
        List<PoctListener> poct,
        List<Watcher> watchers,
        List<DbPoller> dbPollers,
        List<Serial> serial,
        @DefaultValue Ble ble,
        List<DicomDrop> dicom,
        @DefaultValue Worklist worklist) {

    public enum Role { GATEWAY, DEVICE_HOST_AGENT }

    public GatewayProperties {
        role = role == null ? Role.GATEWAY : role;
        coreUrl = blankToDefault(coreUrl, "http://localhost:8080");
        while (coreUrl.endsWith("/")) {
            coreUrl = coreUrl.substring(0, coreUrl.length() - 1);
        }
        dataDir = dataDir == null ? Path.of("data") : dataDir;
        endpointKind = blankToDefault(endpointKind, role == Role.GATEWAY ? "GATEWAY" : "DEVICE_HOST_AGENT");
        mllp = mllp == null ? List.of() : List.copyOf(mllp);
        poct = poct == null ? List.of() : List.copyOf(poct);
        watchers = watchers == null ? List.of() : List.copyOf(watchers);
        dbPollers = dbPollers == null ? List.of() : List.copyOf(dbPollers);
        serial = serial == null ? List.of() : List.copyOf(serial);
        dicom = dicom == null ? List.of() : List.copyOf(dicom);
    }

    /** Zero-touch enrolment: the one-time token handed out by the core (§15.2), inline or as a file reference. */
    public record Enrolment(String token, Path tokenFile, @DefaultValue("0") int maxAttempts,
                            @DefaultValue("PT5S") Duration initialBackoff, @DefaultValue("PT5M") Duration maxBackoff) {
    }

    /**
     * Client certificate for mTLS to the core ingress. {@code simulateIngress=true} (development only) makes the gateway
     * send the thumbprint header itself, as the dev stack has no ingress (contract §7 "Local / test").
     */
    public record ClientTls(String keyStore, String keyStorePassword, @DefaultValue("PKCS12") String keyStoreType,
                            String keyAlias, String trustStore, String trustStorePassword,
                            @DefaultValue("PKCS12") String trustStoreType, boolean simulateIngress,
                            @DefaultValue("X-Client-Cert-Sha256") String certificateHeader,
                            @DefaultValue("PT10S") Duration connectTimeout,
                            @DefaultValue("PT30S") Duration requestTimeout) {
    }

    /** Encrypted store-and-forward queue (§16.2): default 2 GB, 72 h retention, never below 48 h. */
    public record Queue(@DefaultValue("2147483648") long maxBytes, @DefaultValue("72") int retentionHours,
                        @DefaultValue("67108864") long segmentBytes) {
        public Queue {
            retentionHours = Math.max(48, retentionHours);
            segmentBytes = Math.max(4096, segmentBytes);
        }
    }

    public record Forwarder(@DefaultValue("100") int batchSize, @DefaultValue("10000000") long batchMaxBytes,
                            @DefaultValue("PT1S") Duration initialBackoff, @DefaultValue("PT5M") Duration maxBackoff,
                            @DefaultValue("PT5S") Duration immediateTimeout) {
        public Forwarder {
            batchSize = Math.min(100, Math.max(1, batchSize));
        }
    }

    public record Heartbeat(@DefaultValue("60") int intervalSeconds, @DefaultValue("CIS-L1") String baseline) {
    }

    /** Server-side TLS for a local listener (MLLP, POCT1-A, BLE relay intake). */
    public record ListenerTls(boolean enabled, String keyStore, String keyStorePassword,
                              @DefaultValue("PKCS12") String keyStoreType, String trustStore, String trustStorePassword,
                              boolean requireClientCertificate) {
    }

    public record MllpListener(String id, String deviceInstanceId, @DefaultValue("0.0.0.0") String bindAddress,
                               int port, @DefaultValue ListenerTls tls, List<String> sendingApplications,
                               @DefaultValue("2097152") int maxMessageBytes) {
        public MllpListener {
            sendingApplications = sendingApplications == null ? List.of() : List.copyOf(sendingApplications);
        }
    }

    public record PoctListener(String id, String deviceInstanceId, @DefaultValue("0.0.0.0") String bindAddress,
                               int port, @DefaultValue ListenerTls tls, @DefaultValue("4194304") int maxMessageBytes) {
    }

    public enum PostAction { COPY, MOVE }

    /** P2 folder watcher. The default post action COPY never touches the vendor's files (de-duplication by hash). */
    public record Watcher(String id, String deviceInstanceId, Path path, @DefaultValue("*") String pattern,
                          Path archive, Path quarantine, @DefaultValue("COPY") PostAction postAction,
                          @DefaultValue("10") int stabilitySeconds, @DefaultValue("ISO-8859-15") String gdtCharset,
                          @DefaultValue("52428800") long maxFileBytes, @DefaultValue("PT5S") Duration pollInterval) {
    }

    /** P2 vendor-database poller: read-only JDBC, one {@code ?} parameter bound to the high-water mark. */
    public record DbPoller(String id, String deviceInstanceId, String url, String username, String password,
                           String credentialRef, String query, String watermarkColumn,
                           @DefaultValue("0") String initialWatermark, @DefaultValue("PT60S") Duration pollInterval,
                           @DefaultValue("500") int maxRows) {
    }

    public enum SerialMode { TCP, STDIN, FILE }

    public enum Framing { LINE, STX_ETX, TERMINATOR }

    /** P1 serial connector; serial-over-TCP (terminal server / ser2net) or the stdin / file simulation. */
    public record Serial(String id, String deviceInstanceId, @DefaultValue("TCP") SerialMode mode, String host,
                         int port, Path file, @DefaultValue("COM1") String portName,
                         @DefaultValue("LINE") Framing framing, @DefaultValue("3") int terminator,
                         @DefaultValue("TEXT") String encoding, @DefaultValue("65536") int maxFrameBytes,
                         @DefaultValue("PT5S") Duration reconnectDelay) {
    }

    /** BLE relay intake for the nurse tablet / kiosk relay and the gateway's own BLE stack. */
    public record Ble(boolean enabled, @DefaultValue("127.0.0.1") String bindAddress, @DefaultValue("8097") int port,
                      String relayKey, @DefaultValue ListenerTls tls, List<String> peripheralAllowList,
                      String addressSalt) {
        public Ble {
            peripheralAllowList = peripheralAllowList == null ? List.of() : List.copyOf(peripheralAllowList);
        }
    }

    /** P7 DICOM drop folder: instance + metadata are forwarded through /attachments and /dicomweb. */
    public record DicomDrop(String id, String deviceInstanceId, Path path, Path archive, Path quarantine,
                            @DefaultValue("10") int stabilitySeconds, @DefaultValue("52428800") long maxFileBytes,
                            @DefaultValue("PT5S") Duration pollInterval) {
    }

    /** Worklist push (E31-HLR-08): GDT 6302 request files written into the vendor's import folder. */
    public record Worklist(Path gdtFolder, @DefaultValue("WZGW") String gdtSenderId,
                           @DefaultValue("DEVICE") String gdtReceiverId) {
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
