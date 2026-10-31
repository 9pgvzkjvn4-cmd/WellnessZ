package com.wellnessz.gateway.connector.mllp;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.IngestSink;
import com.wellnessz.gateway.connector.TcpListenerConnector;
import com.wellnessz.gateway.core.CoreResponse;
import com.wellnessz.gateway.queue.ItemKind;
import com.wellnessz.gateway.queue.QueueFullException;
import com.wellnessz.gateway.queue.QueueItem;

/**
 * HL7 v2 MLLP listener (P4). Each message is queued (encrypted, fsync-ed), then forwarded to
 * {@code POST /device-ingest/hl7v2}; the core's ACK is relayed to the device. When the core cannot be reached the
 * gateway answers AA itself on the strength of the durable queue write and replays later with the same
 * {@code Idempotency-Key} (store-and-forward accept semantics, documented per device). Logs carry sequence numbers only.
 */
public class MllpListener extends TcpListenerConnector {

    private static final Logger log = LoggerFactory.getLogger(MllpListener.class);
    public static final String CONTENT_TYPE = "application/hl7-v2";

    private final List<String> allowedSenders;
    private final int maxMessageBytes;

    public MllpListener(GatewayProperties.MllpListener cfg, IngestSink sink, Clock clock, boolean allowPlaintext) {
        super(cfg.id(), cfg.deviceInstanceId(), sink, clock, cfg.bindAddress(), cfg.port(), cfg.tls(), allowPlaintext);
        this.allowedSenders = cfg.sendingApplications();
        this.maxMessageBytes = cfg.maxMessageBytes();
    }

    @Override
    public String type() {
        return "MLLP";
    }

    @Override
    protected void handle(Socket socket) throws IOException {
        MllpFraming.Reader reader = new MllpFraming.Reader(new BufferedInputStream(socket.getInputStream()),
                maxMessageBytes);
        OutputStream out = socket.getOutputStream();
        byte[] message;
        while (running && (message = reader.next()) != null) {
            byte[] ack = process(message);
            out.write(MllpFraming.encode(ack));
            out.flush();
        }
    }

    /** Handles one de-framed message and returns the acknowledgement to send back. */
    byte[] process(byte[] message) {
        Hl7Header h = Hl7Header.parse(message);
        if (h == null) {
            error("not_hl7");
            return Hl7Ack.build(null, "AR", "Message does not start with a valid MSH segment", clock.instant());
        }
        if (!allowedSenders.isEmpty() && !allowedSenders.contains(h.sendingApplicationId())) {
            error("sender_not_allowed");
            return Hl7Ack.build(h, "AR", "Sending application not allowed on this listener", clock.instant());
        }
        QueueItem item;
        try {
            item = sink.captureForImmediate(type(), ItemKind.HL7V2, deviceInstanceId, CONTENT_TYPE, Map.of(), message);
        } catch (QueueFullException e) {
            error("queue_full");
            return Hl7Ack.build(h, "AR", "Gateway queue full - resend later", clock.instant());
        }
        seen();
        Optional<CoreResponse> core = sink.forwardNow(item);
        if (core.isPresent()) {
            CoreResponse r = core.get();
            if (r.success() && r.text().startsWith("MSH")) {
                log.debug("seq={} relayed core ACK {}", item.sequence(), Hl7Ack.acknowledgementCode(r.body()));
                return r.body();
            }
            // deterministic refusal (dead-lettered locally): 400 parse error → AE (do not resend),
            // other refusals (unknown / quarantined device, size) → AR (resend only after the cause is fixed)
            return Hl7Ack.build(h, r.status() == 400 ? "AE" : "AR",
                    "Rejected by the engine: " + r.problemCode().orElse("http_" + r.status()), clock.instant());
        }
        log.debug("seq={} stored for forwarding; local AA", item.sequence());
        return Hl7Ack.build(h, "AA", "Stored for forwarding", clock.instant());
    }
}
