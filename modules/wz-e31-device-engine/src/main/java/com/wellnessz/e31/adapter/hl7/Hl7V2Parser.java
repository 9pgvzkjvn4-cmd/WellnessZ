package com.wellnessz.e31.adapter.hl7;

import java.nio.charset.StandardCharsets;
import java.util.List;

import ca.uhn.hl7v2.HL7Exception;

import com.wellnessz.e31.adapter.ParseContext;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.adapter.RecordAssembler;
import com.wellnessz.e31.adapter.RecordParser;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.support.EngineException;

/**
 * {@code HL7_SEGMENTS}: HL7 v2 ORU^R01 (IHE PCD-01 / data-manager ORU). HAPI validates the structure; the engine checks the
 * profile (message type declared by the driver, PID, OBR and at least one OBX for PCD-01) and evaluates the selectors.
 * One message = one record (one observation set).
 */
public class Hl7V2Parser implements RecordParser {

    private final Hl7Support hapi;

    public Hl7V2Parser(Hl7Support hapi) {
        this.hapi = hapi;
    }

    @Override
    public String parseType() {
        return "HL7_SEGMENTS";
    }

    @Override
    public List<ParsedRecord> parse(ParseContext ctx) {
        String er7 = new String(ctx.body(), ctx.charset(StandardCharsets.ISO_8859_1));
        try {
            hapi.parse(er7);
        } catch (HL7Exception | RuntimeException e) {
            throw EngineException.parse("HL7 message could not be parsed");
        }
        Hl7Segments segs = new Hl7Segments(er7);
        checkProfile(ctx.pkg(), segs);
        RecordAssembler.SelectorSource src = new RecordAssembler.SelectorSource() {
            @Override
            public String value(String selector) {
                return segs.value(selector);
            }

            @Override
            public String unit(String selector) {
                return segs.unit(selector);
            }
        };
        return List.of(RecordAssembler.assemble(0, ctx.pkg(), src, ctx.zone(), ctx.receivedAt()));
    }

    static void checkProfile(DriverPackage pkg, Hl7Segments segs) {
        String type = segs.value("MSH-9.1") + "^" + segs.value("MSH-9.2");
        List<String> allowed = new java.util.ArrayList<>();
        if (pkg.protocol() != null && pkg.protocol().get("messageTypes") != null) {
            pkg.protocol().get("messageTypes").forEach(n -> allowed.add(n.asString()));
        }
        if (!allowed.isEmpty() && !allowed.contains(type)) {
            throw EngineException.profile("Message type " + type + " is not accepted by the driver");
        }
        String profile = pkg.protocolText("profile");
        if ("ORU^R01".equals(type) || "IHE_PCD_01".equals(profile)) {
            if (!segs.has("PID")) {
                throw EngineException.parse("ORU^R01 without PID segment");
            }
            if (!segs.has("OBR")) {
                throw EngineException.profile("ORU^R01 without OBR segment");
            }
            if (!segs.has("OBX")) {
                throw EngineException.profile("ORU^R01 without OBX segment");
            }
        }
    }
}
