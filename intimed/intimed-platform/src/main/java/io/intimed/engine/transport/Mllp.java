package io.intimed.engine.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** MLLP framing (0x0B … 0x1C 0x0D) with a hard frame-size limit (SC-12) and HL7 ACK construction. */
public final class Mllp {
    public static final int SB = 0x0B, EB = 0x1C, CR = 0x0D;
    public static final int MAX_FRAME = 1_048_576;

    private Mllp() { }

    public static void write(OutputStream out, String msg) throws IOException {
        out.write(SB);
        out.write(msg.getBytes(StandardCharsets.UTF_8));
        out.write(EB);
        out.write(CR);
        out.flush();
    }

    /** Reads one frame; null at end of stream. Bytes before SB are discarded; oversize frames raise IOException. */
    public static String read(InputStream in) throws IOException {
        int b;
        while ((b = in.read()) != -1 && b != SB) { /* skip noise */ }
        if (b == -1) return null;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while ((b = in.read()) != -1) {
            if (b == EB) {
                int cr = in.read();
                if (cr != CR) throw new IOException("MLLP frame not terminated with CR");
                return buf.toString(StandardCharsets.UTF_8);
            }
            buf.write(b);
            if (buf.size() > MAX_FRAME) throw new IOException("MLLP frame exceeds limit");
        }
        throw new IOException("connection closed inside MLLP frame");
    }

    public static String ack(String request, String code, String text) {
        String ctl = "", app = "", fac = "", ver = "2.6";
        for (String seg : request.replace("\r\n", "\r").replace('\n', '\r').split("\r")) {
            if (seg.startsWith("MSH|")) {
                String[] f = seg.split("\\|", -1);
                if (f.length > 9) ctl = f[9];
                if (f.length > 2) app = f[2];
                if (f.length > 3) fac = f[3];
                if (f.length > 11) ver = f[11];
            }
        }
        String ts = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "+0000";
        String safe = text == null ? "" : text.replaceAll("[|^~\\\\&\r\n]", " ");
        return "MSH|^~\\&|INTIMED|INTIMED|" + app + "|" + fac + "|" + ts + "||ACK^R01^ACK|ACK" + ctl + "|P|" + ver + "\r"
                + "MSA|" + code + "|" + ctl + (safe.isEmpty() ? "" : "|" + safe) + "\r";
    }
}
