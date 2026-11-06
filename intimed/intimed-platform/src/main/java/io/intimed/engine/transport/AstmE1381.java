package io.intimed.engine.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ASTM E1381 / CLSI LIS1-A low-level protocol (IM-E01-BL-007, protocol part) as a transport-independent codec: it is fed
 * bytes from any byte stream (serial port in the device host agent, TCP socket, a capture file) and answers the bytes to
 * write back. The reassembled message text (records separated by CR) is exactly what {@link io.intimed.engine.parse.RecordParser}
 * (ASTM E1394 / LIS2-A2) parses.
 *
 * <pre>
 *   establishment   sender ENQ            → receiver ACK (or NAK = busy)
 *   transfer        STX FN text ETB|ETX C1 C2 CR LF → ACK (next frame) | NAK (retransmit, max 6 times)
 *   termination     sender EOT
 * </pre>
 * <ul>
 *   <li>FN is the frame number 1…7,0,1… (modulo 8, first frame of a message is 1).</li>
 *   <li>C1 C2 = checksum: sum of all bytes after STX up to and including ETB/ETX, modulo 256, two upper-case hex digits.</li>
 *   <li>Each record goes into its own frame; a record longer than 240 characters is split into intermediate frames (ETB),
 *       its last frame ends with ETX.</li>
 *   <li>Receiver: a frame with a bad checksum, bad frame number or bad structure is answered with NAK; the retransmission
 *       of an already accepted frame (same FN as the previous frame) is acknowledged again and discarded.</li>
 *   <li>Hard limits (SC-12): frame ≤ 247 bytes, message ≤ 1 MiB, ≤ 6 retransmissions per frame.</li>
 * </ul>
 */
public final class AstmE1381 {
    public static final int ENQ = 0x05, ACK = 0x06, NAK = 0x15, EOT = 0x04, STX = 0x02, ETX = 0x03, ETB = 0x17, CR = 0x0D, LF = 0x0A;
    public static final int MAX_TEXT = 240;
    public static final int MAX_FRAME = MAX_TEXT + 7; // STX FN text ETx C1 C2 CR LF
    public static final int MAX_RETRANSMIT = 6;
    public static final int MAX_MESSAGE = 1_048_576;

    private AstmE1381() { }

    /** A decoded frame. */
    public record Frame(int number, String text, boolean last) { }

    /** Checksum over FN … ETB/ETX inclusive as two upper-case hex digits. */
    public static String checksum(byte[] data, int from, int toInclusive) {
        int sum = 0;
        for (int i = from; i <= toInclusive; i++) sum = (sum + (data[i] & 0xFF)) & 0xFF;
        return String.format("%02X", sum);
    }

    /** Builds one frame. */
    public static byte[] frame(int number, String text, boolean last) {
        byte[] t = text.getBytes(StandardCharsets.ISO_8859_1);
        if (t.length > MAX_TEXT) throw new IllegalArgumentException("frame text exceeds " + MAX_TEXT + " characters");
        byte[] f = new byte[t.length + 7];
        f[0] = STX;
        f[1] = (byte) ('0' + (number % 8));
        System.arraycopy(t, 0, f, 2, t.length);
        f[2 + t.length] = (byte) (last ? ETX : ETB);
        String cs = checksum(f, 1, 2 + t.length);
        f[3 + t.length] = (byte) cs.charAt(0);
        f[4 + t.length] = (byte) cs.charAt(1);
        f[5 + t.length] = CR;
        f[6 + t.length] = LF;
        return f;
    }

    /** Splits a message (records separated by CR / CRLF / LF) into frames: one record per frame, records over 240 chars split with ETB. */
    public static List<byte[]> frames(String message) {
        List<byte[]> out = new ArrayList<>();
        int fn = 1;
        for (String rec : message.replace("\r\n", "\r").replace('\n', '\r').split("\r")) {
            if (rec.isEmpty()) continue;
            String text = rec + "\r";
            for (int i = 0; i < text.length(); i += MAX_TEXT) {
                int end = Math.min(text.length(), i + MAX_TEXT);
                out.add(frame(fn, text.substring(i, end), end == text.length()));
                fn = (fn + 1) % 8;
            }
        }
        return out;
    }

    /** Parses and validates one complete frame (STX … LF). */
    public static Frame parseFrame(byte[] f) {
        int n = f.length;
        if (n < 7 || n > MAX_FRAME) throw new IllegalArgumentException("frame length " + n + " outside 7…" + MAX_FRAME);
        if (f[0] != STX) throw new IllegalArgumentException("frame does not start with STX");
        if (f[n - 2] != CR || f[n - 1] != LF) throw new IllegalArgumentException("frame not terminated with CR LF");
        int term = n - 5;
        if (f[term] != ETX && f[term] != ETB) throw new IllegalArgumentException("frame without ETX/ETB");
        int fn = f[1] - '0';
        if (fn < 0 || fn > 7) throw new IllegalArgumentException("invalid frame number");
        String expect = checksum(f, 1, term);
        String got = new String(f, term + 1, 2, StandardCharsets.ISO_8859_1).toUpperCase();
        if (!expect.equals(got)) throw new IllegalArgumentException("checksum mismatch (expected " + expect + ", got " + got + ")");
        for (int i = 2; i < term; i++) {
            int b = f[i] & 0xFF;
            if (b == STX || b == ETX || b == ETB || b == ENQ || b == EOT || b == ACK || b == NAK || b == LF) throw new IllegalArgumentException("control character inside frame text");
        }
        return new Frame(fn, new String(f, 2, term - 2, StandardCharsets.ISO_8859_1), f[term] == ETX);
    }

    /**
     * Strips E1381 framing from a captured byte stream (ENQ, frames, ACK/NAK, EOT) and returns the message text. Frames
     * with bad checksums are rejected; a retransmitted duplicate frame is dropped. Used by the ASTM parser so that raw
     * serial captures can be replayed into a driver.
     */
    public static String unframe(String capture) {
        Receiver r = new Receiver();
        String message = null;
        for (byte b : capture.getBytes(StandardCharsets.ISO_8859_1)) {
            Receiver.Step s = r.accept(b & 0xFF);
            if (s.reply() == NAK) throw new IllegalArgumentException("invalid ASTM frame in capture: " + r.lastError());
            if (s.message() != null) message = message == null ? s.message() : message + s.message();
        }
        if (message == null) {
            // capture without the closing EOT: deliver what was received
            message = r.pending();
        }
        return message;
    }

    public static boolean looksFramed(String payload) {
        return payload != null && !payload.isEmpty() && (payload.indexOf(STX) >= 0 || payload.charAt(0) == ENQ);
    }

    // ------------------------------------------------------------------------------------------------ receiver
    /** Receiver state machine. Feed one byte at a time; each step says what to answer (ACK/NAK or -1 = nothing) and the completed message. */
    public static final class Receiver {
        public enum State { IDLE, ESTABLISHED, IN_FRAME }
        public record Step(int reply, String message) { }

        private State state = State.IDLE;
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        private final StringBuilder message = new StringBuilder();
        private int expected = 1;
        private int lastAccepted = -1;
        private String lastError = "";
        private int naks;

        public State state() { return state; }
        public String lastError() { return lastError; }
        String pending() { return message.toString(); }

        public Step accept(int b) {
            switch (state) {
                case IDLE -> {
                    if (b == ENQ) { reset(); state = State.ESTABLISHED; return new Step(ACK, null); }
                    if (b == STX) { reset(); state = State.IN_FRAME; frame.write(b); return new Step(-1, null); } // capture without ENQ
                    return new Step(-1, null); // line noise ignored while idle
                }
                case ESTABLISHED -> {
                    if (b == STX) { frame.reset(); frame.write(b); state = State.IN_FRAME; return new Step(-1, null); }
                    if (b == EOT) {
                        state = State.IDLE;
                        String m = message.toString();
                        message.setLength(0);
                        return new Step(-1, m.isEmpty() ? null : m);
                    }
                    if (b == ENQ) return new Step(ACK, null);
                    return new Step(-1, null);
                }
                case IN_FRAME -> {
                    frame.write(b);
                    if (frame.size() > MAX_FRAME) { state = State.ESTABLISHED; frame.reset(); return nak("frame exceeds " + MAX_FRAME + " bytes"); }
                    if (b != LF) return new Step(-1, null);
                    byte[] f = frame.toByteArray();
                    frame.reset();
                    state = State.ESTABLISHED;
                    Frame fr;
                    try { fr = parseFrame(f); } catch (IllegalArgumentException e) { return nak(e.getMessage()); }
                    if (fr.number() == lastAccepted && fr.number() != expected) return new Step(ACK, null); // retransmission of an accepted frame
                    if (fr.number() != expected) return nak("frame number " + fr.number() + ", expected " + expected);
                    if (message.length() + fr.text().length() > MAX_MESSAGE) { state = State.IDLE; message.setLength(0); return nak("message exceeds 1 MiB"); }
                    message.append(fr.text());
                    lastAccepted = fr.number();
                    expected = (expected + 1) % 8;
                    naks = 0;
                    return new Step(ACK, null);
                }
                default -> { return new Step(-1, null); }
            }
        }

        private Step nak(String why) { lastError = why; naks++; return new Step(NAK, null); }

        public int consecutiveNaks() { return naks; }

        private void reset() { message.setLength(0); frame.reset(); expected = 1; lastAccepted = -1; naks = 0; }

        /** Drives the receiver over a stream (serial / socket) until one message is complete; null at end of stream. */
        public String receive(InputStream in, OutputStream out) throws IOException {
            int b;
            while ((b = in.read()) != -1) {
                Step s = accept(b);
                if (s.reply() >= 0) { out.write(s.reply()); out.flush(); }
                if (s.message() != null) return s.message();
            }
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ sender
    /** Sender state machine: ENQ, frames with retransmission on NAK (max 6), EOT. Transport-independent. */
    public static final class Sender {
        public enum State { IDLE, AWAIT_ENQ_ACK, AWAIT_FRAME_ACK, DONE, FAILED }

        private final List<byte[]> frames;
        private int index;
        private int retries;
        private State state = State.IDLE;
        private String failure;

        public Sender(String message) { this.frames = frames(message); }

        public State state() { return state; }
        public String failure() { return failure; }
        public int framesTotal() { return frames.size(); }

        /** Bytes to send to start: ENQ. */
        public byte[] start() { state = State.AWAIT_ENQ_ACK; return new byte[]{ENQ}; }

        /** Handles one byte from the receiver; returns the bytes to write next (possibly empty). */
        public byte[] onReply(int b) {
            switch (state) {
                case AWAIT_ENQ_ACK -> {
                    if (b == ACK) { state = State.AWAIT_FRAME_ACK; index = 0; retries = 0; return frames.isEmpty() ? finish() : frames.get(0); }
                    if (b == NAK) { if (++retries > MAX_RETRANSMIT) return fail("receiver busy"); return new byte[]{ENQ}; }
                    return new byte[0];
                }
                case AWAIT_FRAME_ACK -> {
                    if (b == ACK || b == EOT) { // EOT = receiver interrupt request: finish the current frame, then terminate
                        index++;
                        retries = 0;
                        return index >= frames.size() ? finish() : frames.get(index);
                    }
                    if (b == NAK) {
                        if (++retries > MAX_RETRANSMIT) return fail("frame " + (index + 1) + " refused " + MAX_RETRANSMIT + " times");
                        return frames.get(index);
                    }
                    return new byte[0];
                }
                default -> { return new byte[0]; }
            }
        }

        /** Receiver did not answer in time (15 s in the standard): abort with EOT. */
        public byte[] onTimeout() { return fail("timeout"); }

        private byte[] finish() { state = State.DONE; return new byte[]{EOT}; }

        private byte[] fail(String why) { state = State.FAILED; failure = why; return new byte[]{EOT}; }
    }
}
