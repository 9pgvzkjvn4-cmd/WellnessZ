package com.wellnessz.gateway.connector;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.ble.BleIntakeServer;
import com.wellnessz.gateway.connector.db.VendorDbPoller;
import com.wellnessz.gateway.connector.dicom.DicomDropConnector;
import com.wellnessz.gateway.connector.file.FileWatcher;
import com.wellnessz.gateway.connector.mllp.MllpListener;
import com.wellnessz.gateway.connector.poct.PoctListener;
import com.wellnessz.gateway.connector.serial.SerialConnector;
import com.wellnessz.gateway.core.SecretClient;
import com.wellnessz.gateway.support.TlsSupport;

/** Builds the connectors declared in {@code wz.gateway.*} and manages their life cycle. */
public class ConnectorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectorRegistry.class);

    private final List<Connector> connectors = new ArrayList<>();
    private final List<String> plaintextListeners = new ArrayList<>();
    private final BleIntakeServer ble;

    public ConnectorRegistry(GatewayProperties p, IngestSink sink, Clock clock, SecretClient secrets, String bleSalt) {
        this(p, sink, clock, secrets, bleSalt, null);
    }

    /**
     * @param simulatedSerial optional extra serial connector replaying a capture file ({@code --simulate-serial <file>})
     */
    public ConnectorRegistry(GatewayProperties p, IngestSink sink, Clock clock, SecretClient secrets, String bleSalt,
                             GatewayProperties.Serial simulatedSerial) {
        boolean plain = p.allowPlaintextListeners();
        Path data = p.dataDir();
        if (simulatedSerial != null) {
            connectors.add(new SerialConnector(simulatedSerial, SerialConnector.sourceFor(simulatedSerial), sink,
                    clock));
        }
        for (GatewayProperties.MllpListener m : p.mllp()) {
            connectors.add(new MllpListener(m, sink, clock, plain));
            notePlaintext(m.id(), m.bindAddress(), m.tls());
        }
        for (GatewayProperties.PoctListener m : p.poct()) {
            connectors.add(new PoctListener(m, sink, clock, plain));
            notePlaintext(m.id(), m.bindAddress(), m.tls());
        }
        p.watchers().forEach(w -> connectors.add(new FileWatcher(w, sink, clock, data)));
        p.dbPollers().forEach(d -> connectors.add(new VendorDbPoller(d, sink, clock, data, secrets)));
        for (GatewayProperties.Serial s : p.serial()) {
            if (s.mode() == GatewayProperties.SerialMode.TCP) {
                boolean loopback = TlsSupport.plaintextPermitted(s.host() == null ? "" : s.host(), null, false);
                if (!loopback && !plain) {
                    throw new IllegalStateException("serial-over-TCP connector " + s.id() + " to a non-loopback "
                            + "terminal server requires wz.gateway.allow-plaintext-listeners (segmented VLAN exception)");
                }
                if (!loopback) {
                    plaintextListeners.add(s.id());
                }
            }
            connectors.add(new SerialConnector(s, SerialConnector.sourceFor(s), sink, clock));
        }
        p.dicom().forEach(d -> connectors.add(new DicomDropConnector(d, sink, clock, data)));
        if (p.ble().enabled()) {
            ble = new BleIntakeServer(p.ble(), sink, clock, plain, bleSalt);
            connectors.add(ble);
            notePlaintext(ble.id(), p.ble().bindAddress(), p.ble().tls());
        } else {
            ble = null;
        }
        Set<String> ids = new HashSet<>();
        for (Connector c : connectors) {
            if (!ids.add(c.id())) {
                throw new IllegalArgumentException("duplicate connector id " + c.id());
            }
        }
    }

    private void notePlaintext(String id, String bind, GatewayProperties.ListenerTls tls) {
        boolean loopback = TlsSupport.plaintextPermitted(bind, null, false);
        if ((tls == null || !tls.enabled()) && !loopback) {
            plaintextListeners.add(id);
        }
    }

    public List<Connector> all() {
        return List.copyOf(connectors);
    }

    public Optional<BleIntakeServer> ble() {
        return Optional.ofNullable(ble);
    }

    /** Listeners running in plaintext on a non-loopback address under the documented exception. */
    public List<String> plaintextListeners() {
        return List.copyOf(plaintextListeners);
    }

    public Optional<Connector> byId(String id) {
        return connectors.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public void startAll() throws Exception {
        for (Connector c : connectors) {
            c.start();
            log.info("connector {} ({}) started", c.id(), c.type());
        }
    }

    public void stopAll() {
        for (Connector c : connectors) {
            try {
                c.stop();
            } catch (RuntimeException e) {
                log.warn("connector {} stop failed", c.id());
            }
        }
    }
}
