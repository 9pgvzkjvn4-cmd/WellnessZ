package io.intimed.engine.parse;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** One parser per grammar type; the driver package's {@code grammar} section configures it (configuration, not code). */
public interface PayloadParser {
    String type();
    List<Parsed> parse(JsonNode grammar, String payload);

    int MAX_PAYLOAD = 1_048_576;

    static void guard(String payload, int max) {
        if (payload == null || payload.isBlank()) throw new ParseException("empty payload");
        if (payload.length() > max) throw new ParseException("payload exceeds " + max + " characters");
    }
}
