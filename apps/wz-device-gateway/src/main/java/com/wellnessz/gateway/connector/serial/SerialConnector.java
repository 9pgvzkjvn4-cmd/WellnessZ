package com.wellnessz.gateway.connector.serial;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.AbstractConnector;
import com.wellnessz.gateway.connector.ConnectorStatus;
import com.wellnessz.gateway.connector.IngestSink;
import com.wellnessz.gateway.queue.ItemKind;
import com.wellnessz.gateway.queue.QueueFullException;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * P1 serial connector (Device Host agent): reads frames from a {@link SerialSource} and queues each as
 * {@code POST /device-ingest/serial} {@code {records:[{deviceInstanceId?, port, receivedAt, encoding, data}]}}.
 * Reconnects with a delay when the terminal server drops the connection; the simulation stops at end of input.
 */
public class SerialConnector extends AbstractConnector {

    private static final Logger log = LoggerFactory.getLogger(SerialConnector.class);

    private final GatewayProperties.Serial cfg;
    private final SerialSource source;
    private final JsonMapper json = JsonMapper.builder().build();
    private Thread worker;
    private final java.util.List<Waiter> waiters = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** A pending device response to a sent command (matched frames are control traffic, not forwarded as readings). */
    private record Waiter(java.util.regex.Pattern pattern, java.util.concurrent.CompletableFuture<String> reply) {
    }

    public SerialConnector(GatewayProperties.Serial cfg, SerialSource source, IngestSink sink, Clock clock) {
        super(cfg.id(), cfg.deviceInstanceId(), sink, clock);
        this.cfg = cfg;
        this.source = source;
    }

    public static SerialSource sourceFor(GatewayProperties.Serial cfg) {
        return switch (cfg.mode()) {
            case TCP -> new SerialSource.Tcp(cfg.host(), cfg.port());
            case STDIN -> new SerialSource.Stream(null);
            case FILE -> new SerialSource.Stream(cfg.file());
        };
    }

    @Override
    public String type() {
        return "SERIAL";
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofVirtual().name("wz-" + id + "-serial").start(this::run);
    }

    @Override
    public void stop() {
        running = false;
        try {
            source.close();
        } catch (IOException e) {
            log.debug("serial source close failed");
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** Whether the connector can transmit to the device (serial-over-TCP or a native port). */
    public boolean writable() {
        return source.writable();
    }

    /**
     * Sends a command to the device (start-from-form, E31-BL-019) and, when {@code expect} is given, waits up to {@code timeout}
     * for a frame matching it. Returns the matched frame, {@code ""} when nothing is expected.
     */
    public String command(byte[] bytes, java.util.regex.Pattern expect, java.time.Duration timeout) throws IOException {
        Waiter w = expect == null ? null : new Waiter(expect, new java.util.concurrent.CompletableFuture<>());
        if (w != null) {
            waiters.add(w);
        }
        try {
            source.write(bytes);
            if (w == null) {
                return "";
            }
            return w.reply().get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("no device response within " + timeout.toSeconds() + " s");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("device response failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } finally {
            if (w != null) {
                waiters.remove(w);
            }
        }
    }

    /** Waits for the simulation input to be consumed (tests). */
    public void join(long millis) throws InterruptedException {
        if (worker != null) {
            worker.join(millis);
        }
    }

    private void run() {
        while (running) {
            try {
                InputStream in = source.open();
                state(ConnectorStatus.State.CONNECTED, null);
                readAll(in);
                if (source.finite()) {
                    state(ConnectorStatus.State.IDLE, "end_of_input");
                    return;
                }
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                error("port_unavailable");
                log.warn("{}: serial source failed ({}); reconnecting", id, e.getClass().getSimpleName());
            }
            try {
                Thread.sleep(cfg.reconnectDelay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Frames and queues everything from the stream; public for tests of the simulation path. */
    public int readAll(InputStream in) throws IOException {
        SerialFramer framer = new SerialFramer(in, cfg.framing(), cfg.terminator(), cfg.maxFrameBytes());
        int n = 0;
        byte[] frame;
        while (running && (frame = framer.next()) != null) {
            if (frame.length == 0) {
                continue;
            }
            if (!waiters.isEmpty()) {
                String text = new String(frame, StandardCharsets.ISO_8859_1).strip();
                Waiter matched = waiters.stream().filter(x -> x.pattern().matcher(text).find()).findFirst().orElse(null);
                if (matched != null) {
                    matched.reply().complete(text);
                    continue;
                }
            }
            try {
                sink.capture(type(), ItemKind.SERIAL, deviceInstanceId, "application/json", Map.of(), body(frame), null);
                seen();
                n++;
            } catch (QueueFullException e) {
                error("queue_full"); // serial devices cannot be NAK-ed generically: the frame is lost and counted
                log.error("{}: queue full, serial frame dropped", id);
            }
        }
        return n;
    }

    byte[] body(byte[] frame) {
        String encoding = cfg.encoding().toUpperCase(Locale.ROOT);
        String data = switch (encoding) {
            case "HEX" -> HexFormat.of().formatHex(frame);
            case "BASE64" -> Base64.getEncoder().encodeToString(frame);
            default -> new String(frame, StandardCharsets.ISO_8859_1);
        };
        ObjectNode root = json.createObjectNode();
        ObjectNode rec = root.putArray("records").addObject();
        if (deviceInstanceId != null) {
            rec.put("deviceInstanceId", deviceInstanceId);
        }
        rec.put("port", cfg.portName());
        rec.put("receivedAt", clock.instant().toString());
        rec.put("encoding", switch (encoding) {
            case "HEX", "BASE64" -> encoding;
            default -> "TEXT";
        });
        rec.put("data", data);
        return json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    }
}
