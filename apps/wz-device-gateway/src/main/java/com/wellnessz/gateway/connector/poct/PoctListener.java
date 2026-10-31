package com.wellnessz.gateway.connector.poct;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.IngestSink;
import com.wellnessz.gateway.connector.TcpListenerConnector;
import com.wellnessz.gateway.core.CoreResponse;
import com.wellnessz.gateway.queue.ItemKind;
import com.wellnessz.gateway.queue.QueueFullException;
import com.wellnessz.gateway.queue.QueueItem;

/**
 * POCT1-A2 XML forward (P5): XML documents ({@code OBS.R01}, {@code EVS.R01}, {@code QCN.R01}, {@code DST.R01} …) over
 * TCP / TLS from the data manager are queued and forwarded to {@code POST /device-ingest/poct1a}; the core's
 * {@code ACK.R01} is relayed, or a local {@code ACK.R01 AA} is returned once the queue write is durable. The edge does
 * not parse the XML (no XXE surface): a document is delimited by the closing tag of its root element.
 */
public class PoctListener extends TcpListenerConnector {

    private static final Pattern CONTROL_ID = Pattern.compile("<HDR\\.control_id\\s+V=\"([^\"<>&]{1,64})\"");
    private static final Pattern ROOT = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,63}");
    private static final AtomicLong COUNTER = new AtomicLong();

    private final int maxMessageBytes;

    public PoctListener(GatewayProperties.PoctListener cfg, IngestSink sink, Clock clock, boolean allowPlaintext) {
        super(cfg.id(), cfg.deviceInstanceId(), sink, clock, cfg.bindAddress(), cfg.port(), cfg.tls(), allowPlaintext);
        this.maxMessageBytes = cfg.maxMessageBytes();
    }

    @Override
    public String type() {
        return "POCT1A";
    }

    @Override
    protected void handle(Socket socket) throws IOException {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        byte[] doc;
        while (running && (doc = readDocument(in, maxMessageBytes)) != null) {
            out.write(process(doc));
            out.flush();
        }
    }

    byte[] process(byte[] doc) {
        String controlId = controlId(doc);
        QueueItem item;
        try {
            item = sink.captureForImmediate(type(), ItemKind.POCT1A, deviceInstanceId, "application/xml", Map.of(), doc);
        } catch (QueueFullException e) {
            error("queue_full");
            return ack("AE", controlId, clock.instant());
        }
        seen();
        Optional<CoreResponse> core = sink.forwardNow(item);
        if (core.isPresent() && core.get().success() && core.get().text().contains("ACK.R01")) {
            return core.get().body();
        }
        if (core.isPresent()) {
            return ack("AE", controlId, clock.instant());
        }
        return ack("AA", controlId, clock.instant());
    }

    /** Local {@code ACK.R01}; the only copied value (control id) is restricted to a safe character set. */
    static byte[] ack(String type, String ackControlId, Instant now) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ACK.R01><HDR><HDR.control_id V=\"WZGW" + now.toEpochMilli() + "-" + COUNTER.incrementAndGet()
                + "\"/><HDR.version_id V=\"POCT1\"/><HDR.creation_dttm V=\"" + now + "\"/></HDR>"
                + "<ACK><ACK.type_cd V=\"" + ("AA".equals(type) ? "AA" : "AE") + "\"/>"
                + "<ACK.ack_control_id V=\"" + (ackControlId == null ? "" : ackControlId) + "\"/></ACK></ACK.R01>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    static String controlId(byte[] doc) {
        Matcher m = CONTROL_ID.matcher(new String(doc, StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : null;
    }

    /**
     * Reads one XML document: skips the prolog (declaration, comments, whitespace), takes the root element name and
     * returns once its closing tag (or a self-closing root) has been read. {@code null} on a clean end of stream.
     */
    static byte[] readDocument(InputStream in, int max) throws IOException {
        Buffer buf = new Buffer();
        String root = null;
        byte[] closing = null;
        int b;
        while ((b = in.read()) != -1) {
            if (buf.size() == 0 && Character.isWhitespace(b)) {
                continue;
            }
            buf.write(b);
            if (buf.size() > max) {
                throw new IOException("POCT1-A document larger than " + max + " bytes");
            }
            if (b != '>') {
                continue;
            }
            if (root == null) {
                byte[] sofar = buf.toByteArray();
                root = rootName(sofar);
                if (root != null) {
                    if (sofar[sofar.length - 2] == '/' && endsWithRootStart(sofar, root)) {
                        return sofar; // self-closing root
                    }
                    closing = ("</" + root + ">").getBytes(StandardCharsets.UTF_8);
                }
            } else if (buf.endsWith(closing)) {
                return buf.toByteArray();
            }
        }
        if (buf.size() == 0) {
            return null;
        }
        throw new EOFException("connection closed inside a POCT1-A document");
    }

    /** Growable buffer with a cheap suffix test (no copy per closing bracket). */
    private static final class Buffer extends ByteArrayOutputStream {
        boolean endsWith(byte[] suffix) {
            if (count < suffix.length) {
                return false;
            }
            for (int i = 0; i < suffix.length; i++) {
                if (buf[count - suffix.length + i] != suffix[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Name of the first element start tag after the prolog, once that tag is complete. */
    private static String rootName(byte[] sofar) {
        String s = new String(sofar, StandardCharsets.UTF_8);
        int i = 0;
        while (i < s.length()) {
            int lt = s.indexOf('<', i);
            if (lt < 0 || lt + 1 >= s.length()) {
                return null;
            }
            char c = s.charAt(lt + 1);
            if (c == '?' || c == '!') {
                int gt = s.indexOf('>', lt);
                if (gt < 0) {
                    return null;
                }
                i = gt + 1;
                continue;
            }
            int end = lt + 1;
            while (end < s.length() && !Character.isWhitespace(s.charAt(end)) && s.charAt(end) != '>'
                    && s.charAt(end) != '/') {
                end++;
            }
            String name = s.substring(lt + 1, end);
            return ROOT.matcher(name).matches() && s.indexOf('>', lt) >= 0 ? name : null;
        }
        return null;
    }

    private static boolean endsWithRootStart(byte[] sofar, String root) {
        String s = new String(sofar, StandardCharsets.UTF_8);
        int lt = s.lastIndexOf('<');
        return lt >= 0 && s.startsWith(root, lt + 1);
    }

}
