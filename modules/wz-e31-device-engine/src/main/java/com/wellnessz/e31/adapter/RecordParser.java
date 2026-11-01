package com.wellnessz.e31.adapter;

import java.util.List;

/** One parse type of the adapter library ({@code parse.type} of a driver package, contract section 6.1). */
public interface RecordParser {

    /** e.g. {@code HL7_SEGMENTS}, {@code GATT_STRUCT}. */
    String parseType();

    /**
     * Parses a raw payload with the driver's parse template.
     *
     * @throws com.wellnessz.e31.support.EngineException {@code parse_failed} / {@code profile_violation} (deterministic)
     */
    List<ParsedRecord> parse(ParseContext ctx);
}
