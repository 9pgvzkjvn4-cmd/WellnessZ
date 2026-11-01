package com.wellnessz.e31.adapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ca.uhn.fhir.context.FhirContext;

import com.wellnessz.e31.adapter.ble.GattStructParser;
import com.wellnessz.e31.adapter.dicom.DicomJsonParser;
import com.wellnessz.e31.adapter.fhir.FhirObservationParser;
import com.wellnessz.e31.adapter.file.CsvParser;
import com.wellnessz.e31.adapter.file.GdtParser;
import com.wellnessz.e31.adapter.form.FormParser;
import com.wellnessz.e31.adapter.hl7.Hl7Support;
import com.wellnessz.e31.adapter.hl7.Hl7V2Parser;
import com.wellnessz.e31.adapter.json.JsonPointerParser;
import com.wellnessz.e31.adapter.serial.RegexLinesParser;
import com.wellnessz.e31.adapter.xml.XPathParser;
import com.wellnessz.e31.support.EngineException;

import tools.jackson.databind.json.JsonMapper;

/** The adapter library (E31-F02): one parser per driver {@code parse.type}. */
public final class Adapters {

    /** Ingest kinds each driver adapter accepts (a GDT driver cannot parse a BLE upload). */
    static final Map<String, Set<String>> KINDS = Map.ofEntries(
            Map.entry("HL7V2", Set.of("HL7V2")), Map.entry("FHIR", Set.of("FHIR")), Map.entry("POCT1A", Set.of("POCT1A")),
            Map.entry("FILE_CSV", Set.of("FILE")), Map.entry("FILE_XML", Set.of("FILE")), Map.entry("FILE_GDT", Set.of("FILE")),
            Map.entry("FILE_PDF", Set.of("FILE")), Map.entry("BLE_GATT", Set.of("BLE")), Map.entry("SERIAL", Set.of("SERIAL")),
            Map.entry("WEBHOOK_JSON", Set.of("WEBHOOK")), Map.entry("CLOUD_POLL_JSON", Set.of("WEBHOOK")), Map.entry("DICOMWEB", Set.of("DICOMWEB")),
            Map.entry("OCR", Set.of("FALLBACK")), Map.entry("MANUAL", Set.of("FALLBACK")));

    private final Map<String, RecordParser> parsers = new HashMap<>();

    public Adapters(FhirContext fhir, JsonMapper json) {
        Hl7Support hl7 = new Hl7Support();
        for (RecordParser p : List.of(new Hl7V2Parser(hl7), new FhirObservationParser(fhir, json), new XPathParser("XPATH"),
                new XPathParser("POCT1A_XPATH"), new CsvParser(), new GdtParser(), new GattStructParser(json), new RegexLinesParser(json),
                new JsonPointerParser(json), new DicomJsonParser(json), new FormParser(json))) {
            parsers.put(p.parseType(), p);
        }
    }

    public RecordParser parser(String parseType) {
        RecordParser p = parsers.get(parseType);
        if (p == null) {
            throw new EngineException("no_driver_for_option", "No parser for " + parseType, true);
        }
        return p;
    }

    /** {@code true} when a driver adapter can parse an ingest kind (FALLBACK forms are accepted by any driver). */
    public static boolean accepts(String adapter, String kind) {
        if ("FALLBACK".equals(kind) || "SIMULATOR".equals(kind)) {
            return true;
        }
        return KINDS.getOrDefault(adapter, Set.of()).contains(kind);
    }

    public List<ParsedRecord> parse(ParseContext ctx) {
        return parser(ctx.pkg().parse().type()).parse(ctx);
    }
}
