package com.wellnessz.gateway.connector.mllp;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Original-mode HL7 v2 acknowledgements generated at the edge: AA once the message is durably queued (store-and-forward
 * accept semantics when the core is unreachable), AR when the gateway refuses the message (sender not allowed, queue
 * full). When the core is reachable its own ACK is relayed instead.
 */
public final class Hl7Ack {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final AtomicLong COUNTER = new AtomicLong();

    private Hl7Ack() {
    }

    public static byte[] build(Hl7Header h, String code, String text, Instant now) {
        char fs = h == null ? '|' : h.fieldSeparator();
        String enc = h == null || h.encodingCharacters().isEmpty() ? "^~\\&" : h.encodingCharacters();
        char cs = enc.charAt(0);
        String trigger = h == null ? "" : clean(h.triggerEvent(), fs);
        StringBuilder sb = new StringBuilder(256);
        sb.append("MSH").append(fs).append(enc)
                .append(fs).append("WZ-GATEWAY")
                .append(fs).append("WELLNESSZ")
                .append(fs).append(h == null ? "" : clean(h.sendingApplication(), fs))
                .append(fs).append(h == null ? "" : clean(h.sendingFacility(), fs))
                .append(fs).append(TS.format(now))
                .append(fs)
                .append(fs).append("ACK").append(trigger.isEmpty() ? "" : cs + trigger).append(cs).append("ACK")
                .append(fs).append("WZGW").append(now.toEpochMilli()).append('-').append(COUNTER.incrementAndGet())
                .append(fs).append(h == null || h.processingId().isEmpty() ? "P" : clean(h.processingId(), fs))
                .append(fs).append(h == null || h.version().isEmpty() ? "2.5" : clean(h.version(), fs))
                .append('\r')
                .append("MSA").append(fs).append(code)
                .append(fs).append(h == null ? "" : clean(h.controlId(), fs))
                .append(fs).append(clean(text, fs))
                .append('\r');
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** MSA-1 of an acknowledgement (AA / AE / AR / CA / CE / CR), or {@code null}. */
    public static String acknowledgementCode(byte[] ack) {
        String text = new String(ack, StandardCharsets.ISO_8859_1);
        int i = text.indexOf("MSA");
        if (i < 0 || i + 6 > text.length()) {
            return null;
        }
        return text.substring(i + 4, i + 6);
    }

    /** Removes separators and segment terminators from values copied into the ACK (no segment injection). */
    private static String clean(String v, char fs) {
        if (v == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (char c : v.toCharArray()) {
            if (c != fs && c != '\r' && c != '\n' && c >= 0x20) {
                sb.append(c);
            }
        }
        return sb.length() > 80 ? sb.substring(0, 80) : sb.toString();
    }
}
