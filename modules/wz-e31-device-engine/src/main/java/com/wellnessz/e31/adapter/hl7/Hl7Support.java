package com.wellnessz.e31.adapter.hl7;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import ca.uhn.hl7v2.AcknowledgmentCode;
import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.util.idgenerator.InMemoryIDGenerator;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;

/**
 * HAPI HL7 v2 parsing and acknowledgement (IHE PCD-01, original acknowledgement mode). The generic parser maps versions
 * 2.3–2.6 onto the v2.5 structures ({@link CanonicalModelClassFactory}); semantic profile rules are the engine's own.
 * ACK codes: AA – stored durably; AE – parse / profile error (dead-lettered, the device must not resend); AR – rejected
 * (unknown / quarantined device, size), resend only after the cause is fixed.
 */
public final class Hl7Support {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssZ", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() & 0xffffffL);

    private final HapiContext context;

    public Hl7Support() {
        this.context = new DefaultHapiContext(new CanonicalModelClassFactory("2.5"));
        this.context.setValidationContext(ValidationContextFactory.noValidation());
        this.context.getParserConfiguration().setIdGenerator(new InMemoryIDGenerator());
    }

    /** Structural parse (throws {@link HL7Exception} for unparseable messages). */
    public Message parse(String er7) throws HL7Exception {
        String normalised = er7.replace("\r\n", "\r").replace('\n', '\r').strip();
        return context.getPipeParser().parse(normalised);
    }

    /** HAPI acknowledgement of a parsed message; falls back to {@link #ack(String, String, String, Clock)} on failure. */
    public String ack(Message message, String code, String text, String rawForFallback, Clock clock) {
        if (message != null) {
            try {
                AcknowledgmentCode ac = AcknowledgmentCode.valueOf(code);
                Message ack = message.generateACK(ac, "AA".equals(code) ? null : new HL7Exception(text == null ? code : text));
                return ack.encode();
            } catch (Exception e) {
                // fall through to the hand-built acknowledgement
            }
        }
        return ack(rawForFallback, code, text, clock);
    }

    /** Hand-built ACK for messages HAPI cannot parse (sender / receiver swapped from the MSH, MSA with the control id). */
    public static String ack(String raw, String code, String text, Clock clock) {
        String sendingApp = "";
        String sendingFac = "";
        String receivingApp = "";
        String receivingFac = "";
        String control = "";
        String version = "2.6";
        try {
            Hl7Segments s = new Hl7Segments(raw == null ? "" : raw);
            sendingApp = nz(s.value("MSH-3"));
            sendingFac = nz(s.value("MSH-4"));
            receivingApp = nz(s.value("MSH-5"));
            receivingFac = nz(s.value("MSH-6"));
            control = nz(s.value("MSH-10"));
            version = s.value("MSH-12") == null ? "2.6" : s.value("MSH-12");
        } catch (RuntimeException e) {
            // unparseable: acknowledge with empty identifiers
        }
        String now = TS.format(clock.instant());
        StringBuilder sb = new StringBuilder();
        sb.append("MSH|^~\\&|").append(receivingApp.isEmpty() ? "WELLNESSZ" : receivingApp).append('|').append(receivingFac).append('|')
                .append(sendingApp).append('|').append(sendingFac).append('|').append(now).append("||ACK^R01^ACK|")
                .append("WZ").append(SEQUENCE.incrementAndGet()).append("|P|").append(version).append('\r');
        sb.append("MSA|").append(code).append('|').append(control);
        if (!"AA".equals(code) && text != null) {
            sb.append('|').append(text.replace('|', ' ').replace('^', ' ').replace('\r', ' '));
        }
        sb.append('\r');
        if (!"AA".equals(code)) {
            sb.append("ERR|||").append("AE".equals(code) ? "100" : "207").append("|E\r");
        }
        return sb.toString();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
