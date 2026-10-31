package com.wellnessz.gateway.connector.mllp;

import java.nio.charset.StandardCharsets;

/**
 * The MSH fields the gateway needs to filter and acknowledge a message (no full parse at the edge – parsing and profile
 * validation happen centrally). {@code MSH-n} = n-th field with MSH-1 being the field separator itself.
 */
public record Hl7Header(char fieldSeparator, String encodingCharacters, String sendingApplication,
                        String sendingFacility, String receivingApplication, String receivingFacility,
                        String messageType, String controlId, String processingId, String version) {

    /** Parses the MSH segment; returns {@code null} when the message does not start with a valid MSH. */
    public static Hl7Header parse(byte[] message) {
        String text = new String(message, StandardCharsets.ISO_8859_1);
        if (text.length() < 8 || !text.startsWith("MSH")) {
            return null;
        }
        char fs = text.charAt(3);
        if (Character.isLetterOrDigit(fs) || fs == '\r' || fs == '\n') {
            return null;
        }
        int end = indexOfSegmentEnd(text);
        String msh = text.substring(0, end);
        String[] f = msh.split(java.util.regex.Pattern.quote(String.valueOf(fs)), -1);
        // f[0] = "MSH", f[1] = MSH-2 (encoding characters), f[n-1] = MSH-n
        return new Hl7Header(fs, field(f, 2), field(f, 3), field(f, 4), field(f, 5), field(f, 6), field(f, 9),
                field(f, 10), field(f, 11), field(f, 12));
    }

    /** First component of MSH-3 (namespace id), used by the sending-application allow-list. */
    public String sendingApplicationId() {
        return firstComponent(sendingApplication);
    }

    /** Trigger event of MSH-9 (e.g. {@code R01} of {@code ORU^R01^ORU_R01}). */
    public String triggerEvent() {
        if (messageType == null || encodingCharacters == null || encodingCharacters.isEmpty()) {
            return "";
        }
        String[] parts = messageType.split(java.util.regex.Pattern.quote(String.valueOf(encodingCharacters.charAt(0))), -1);
        return parts.length > 1 ? parts[1] : "";
    }

    private String firstComponent(String value) {
        if (value == null || encodingCharacters == null || encodingCharacters.isEmpty()) {
            return value;
        }
        int i = value.indexOf(encodingCharacters.charAt(0));
        return i < 0 ? value : value.substring(0, i);
    }

    private static String field(String[] f, int mshIndex) {
        int i = mshIndex - 1;
        return i < f.length ? f[i] : "";
    }

    private static int indexOfSegmentEnd(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                return i;
            }
        }
        return text.length();
    }
}
