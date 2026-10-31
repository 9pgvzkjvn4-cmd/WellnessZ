package com.wellnessz.e32.catalogue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wellnessz.casekernel.device.DeviceCodes.Category;
import com.wellnessz.casekernel.device.DeviceCodes.Family;
import com.wellnessz.e32.support.Docs;

/**
 * Maps an inventory row (Appendix A of the solution document, {@code e32/device-inventory.json}) onto the catalogue model
 * record (contract §5): family code, variants, guideline drivers, typical units, OH forms fed, per-option notes, regulatory
 * status per market and the initial security posture. Pure functions, unit-tested against the shipped inventory.
 */
public final class InventoryMapping {

    public static final List<String> OPTION_CODES = List.of("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9", "P10");
    public static final List<String> MARKETS = List.of("US", "EU", "UK", "CA", "AU", "JP", "CN", "IN", "SA", "AE", "QA", "BH", "OTHER");

    private static final Map<String, Family> LABELS = Map.ofEntries(
            Map.entry("Spirometry & lung function", Family.SPIROMETRY),
            Map.entry("Audiometry & hearing", Family.AUDIOMETRY),
            Map.entry("Vital signs & body composition", Family.VITALS),
            Map.entry("Cardiology", Family.CARDIOLOGY),
            Map.entry("Vision & ophthalmic", Family.VISION),
            Map.entry("Point-of-care laboratory", Family.POC),
            Map.entry("Drug & alcohol testing", Family.DRUG_ALCOHOL),
            Map.entry("Respiratory protection & ergonomics", Family.RESP_ERGONOMICS),
            Map.entry("Exposure & environment feeds", Family.EXPOSURE),
            Map.entry("Wearables & personal health", Family.WEARABLE),
            Map.entry("Imaging & other", Family.IMAGING));

    private static final Pattern FDA_CODE = Pattern.compile("\\b([A-Z]{3})\\b");

    private InventoryMapping() {
    }

    public static Family family(String label) {
        Family f = LABELS.get(label == null ? "" : label.strip());
        if (f == null) {
            throw new IllegalArgumentException("Unknown inventory family");
        }
        return f;
    }

    public static String label(Family f) {
        return LABELS.entrySet().stream().filter(e -> e.getValue() == f).map(Map.Entry::getKey).findFirst().orElse(f.name());
    }

    public static boolean highConfidence(String confidence) {
        return confidence != null && confidence.strip().startsWith("High");
    }

    /** Measurement categories (form families) a family feeds (contract §3). */
    public static List<Category> categories(Family f) {
        return switch (f) {
            case SPIROMETRY -> List.of(Category.SPIROMETRY);
            case AUDIOMETRY -> List.of(Category.AUDIOMETRY);
            case VITALS -> List.of(Category.VITALS, Category.BODY_COMPOSITION);
            case CARDIOLOGY -> List.of(Category.ECG, Category.ABPM_HOLTER);
            case VISION -> List.of(Category.VISION);
            case POC -> List.of(Category.POC);
            case DRUG_ALCOHOL -> List.of(Category.BREATH_ALCOHOL, Category.DRUG_SCREEN);
            case RESP_ERGONOMICS -> List.of(Category.FIT_TEST, Category.ERGONOMICS);
            case EXPOSURE -> List.of(Category.EXPOSURE);
            case WEARABLE -> List.of(Category.WEARABLE);
            case IMAGING -> List.of(Category.IMAGING, Category.VISION);
        };
    }

    /** OH forms fed (form codes of contract §11). */
    public static List<String> formsFed(Family f) {
        return switch (f) {
            case SPIROMETRY -> List.of("E03.DEVICE_RESULT.SPIROMETRY");
            case AUDIOMETRY -> List.of("E03.DEVICE_RESULT.AUDIOMETRY");
            case VITALS -> List.of("E03.DEVICE_RESULT.VITALS", "E03.DEVICE_RESULT.BODY_COMPOSITION", "E12.SCREENING_RESULT");
            case CARDIOLOGY -> List.of("E03.DEVICE_RESULT.ECG", "E03.DEVICE_RESULT.ABPM_HOLTER");
            case VISION -> List.of("E03.DEVICE_RESULT.VISION");
            case POC -> List.of("E03.DEVICE_RESULT.POC", "E12.SCREENING_RESULT");
            case DRUG_ALCOHOL -> List.of("E10.BREATH_ALCOHOL", "E10.DRUG_SCREEN");
            case RESP_ERGONOMICS -> List.of("E03.DEVICE_RESULT.FIT_TEST", "E03.DEVICE_RESULT.ERGONOMICS");
            case EXPOSURE -> List.of("E07.IH_MEASUREMENT");
            case WEARABLE -> List.of("E12.WEARABLE_DAILY");
            case IMAGING -> List.of("E03.DEVICE_RESULT.IMAGING", "E03.DEVICE_RESULT.VISION");
        };
    }

    public static List<String> units(Family f) {
        return switch (f) {
            case SPIROMETRY -> List.of("L", "L/s", "%");
            case AUDIOMETRY -> List.of("dB[HL]", "Hz");
            case VITALS -> List.of("mm[Hg]", "/min", "%", "Cel", "kg", "cm", "kg/m2");
            case CARDIOLOGY -> List.of("/min", "ms", "mm[Hg]");
            case VISION -> List.of("{acuity}", "[diop]");
            case POC -> List.of("%", "mmol/L", "mg/dL", "g/dL", "ug/dL");
            case DRUG_ALCOHOL -> List.of("g/(210.L)", "{result}");
            case RESP_ERGONOMICS -> List.of("1", "{score}", "kg");
            case EXPOSURE -> List.of("dB(A)", "[ppm]", "m/s2", "Cel");
            case WEARABLE -> List.of("{steps}", "min", "/min");
            case IMAGING -> List.of("{study}");
        };
    }

    public static String guidelineDrivers(Family f) {
        return switch (f) {
            case SPIROMETRY -> "ATS/ERS 2019; ISO 26782; OSHA 3637 / NIOSH; daily 3-L syringe check";
            case AUDIOMETRY -> "ANSI/ASA S3.6-2018; IEC 60645-1; ISO 8253-1; ANSI S3.1; OSHA 1910.95 App. C–E; HSE L108";
            case VITALS -> "ISO 81060-2; ISO 80601-2-61; ISO 80601-2-56";
            case CARDIOLOGY -> "IEC 60601-2-25; IEC 60601-2-4";
            case VISION -> "ISO 8596 / ISO 10938 visual acuity; occupational vision standards";
            case POC -> "ISO 15197; ISO 22870 point-of-care testing; CLIA-style QC";
            case DRUG_ALCOHOL -> "DOT 49 CFR Part 40; NHTSA Conforming Products Lists; EU 2018/1042; ICAO Doc 9654";
            case RESP_ERGONOMICS -> "OSHA 1910.134 App. A; ISO 16975-3";
            case EXPOSURE -> "OSHA 1910.95; ISO 9612; EU 2002/44/EC vibration; ISO 7243 WBGT";
            case WEARABLE -> "Consent-scoped wellbeing data (WEARABLE_DATA purpose)";
            case IMAGING -> "ILO classification of radiographs; OSHA 1910.1053 silica surveillance";
        };
    }

    /** Variants: the model column lists several models separated by '/'. */
    public static List<String> variants(String model) {
        List<String> out = new ArrayList<>();
        if (model == null) {
            return out;
        }
        for (String p : model.split("\\s/\\s")) {
            if (!p.isBlank()) {
                out.add(p.strip());
            }
        }
        return out;
    }

    /** Per-option notes extracted from the connectivity column ("P3 BLE / P1 USB to EasyOne Connect; P4 HL7"). */
    public static List<Map<String, Object>> optionNotes(String connectivity, List<String> options) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String code : OPTION_CODES) {
            boolean supported = options.contains(code);
            String note = null;
            if (connectivity != null) {
                Matcher m = Pattern.compile("(?:^|[;/,(\\s])" + code + "(?![0-9])\\s*([^;/]*)").matcher(connectivity);
                if (m.find()) {
                    note = m.group(1).strip();
                    if (note.isEmpty()) {
                        note = null;
                    }
                }
            }
            out.add(Docs.of("option", code, "supported", supported, "notes", supported ? note : null));
        }
        return out;
    }

    /** FDA product codes in the inventory column ("DXN;DQA;FLL", "CCK (breath CO analyser – verify)", "n/a (verify)"). */
    public static List<String> fdaCodes(String fdaColumn) {
        List<String> codes = new ArrayList<>();
        if (fdaColumn == null) {
            return codes;
        }
        for (String token : fdaColumn.split(";")) {
            Matcher m = FDA_CODE.matcher(token.strip());
            if (m.lookingAt() && !codes.contains(m.group(1))) {
                codes.add(m.group(1));
            }
        }
        return codes;
    }

    /**
     * Regulatory status per market. The inventory carries US FDA product codes (openFDA, 19 Oct 2026); other markets start
     * {@code UNKNOWN} and are completed through the UDI / regulatory import (E32-BL-002).
     */
    public static List<Map<String, Object>> regulatory(String fdaColumn) {
        List<Map<String, Object>> out = new ArrayList<>();
        String col = fdaColumn == null ? "" : fdaColumn.toLowerCase(Locale.ROOT);
        List<String> codes = fdaCodes(fdaColumn);
        String usStatus;
        if (col.contains("not a medical device")) {
            usStatus = "NOT_REQUIRED";
        } else if (!codes.isEmpty() && !col.contains("verify")) {
            usStatus = "CLEARED";
        } else {
            usStatus = "UNKNOWN";
        }
        out.add(Docs.of("market", "US", "regime", "FDA 21 CFR 862–892 (510(k) / De Novo / PMA)", "status", usStatus,
                "productCodes", codes, "reference", col.contains("verify") ? "inventory flag: verify product code" : null,
                "source", "INVENTORY"));
        out.add(Docs.of("market", "EU", "regime", "EU MDR 2017/745 / IVDR 2017/746 (CE, EUDAMED)", "status", "UNKNOWN", "productCodes", List.of(),
                "source", "INVENTORY"));
        out.add(Docs.of("market", "UK", "regime", "UKCA / MHRA", "status", "UNKNOWN", "productCodes", List.of(), "source", "INVENTORY"));
        out.add(Docs.of("market", "AE", "regime", "MOHAP registration", "status", "UNKNOWN", "productCodes", List.of(), "source", "INVENTORY"));
        return out;
    }

    /** Initial security block: nothing assessed yet (MDS2 / SBOM intake happens in the console, E32-F07). */
    public static Map<String, Object> initialSecurity() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("mds2", Docs.of("received", false));
        s.put("sbom", Docs.of("available", false));
        s.put("supportStatus", "UNKNOWN");
        s.put("posture", "NOT_ASSESSED");
        return s;
    }

    /** Vendor software named in the protocol column (only meaningful where the model offers P2). */
    public static List<Map<String, Object>> vendorSoftware(String protocol, List<String> options) {
        if (protocol == null || protocol.isBlank() || !options.contains("P2")) {
            return List.of();
        }
        String name = protocol.split("[(;]")[0].strip();
        if (name.isEmpty() || "n/a".equalsIgnoreCase(name)) {
            return List.of();
        }
        return List.of(Docs.of("name", name, "versions", List.of()));
    }

    /** Normalises the inventory options (keeps the P1..P10 order). */
    public static List<String> options(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String code : OPTION_CODES) {
            if (raw != null && raw.contains(code)) {
                out.add(code);
            }
        }
        return out;
    }

    /** The full detail document stored with a seeded model. */
    public static Map<String, Object> detail(Map<String, Object> row) {
        Family f = family(Docs.str(row.get("family")));
        List<String> options = options(Docs.strings(row.get("options")));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("variants", variants(Docs.str(row.get("model"))));
        d.put("guidelineDrivers", guidelineDrivers(f));
        d.put("units", units(f));
        d.put("formsFed", formsFed(f));
        d.put("optionNotes", optionNotes(Docs.str(row.get("connectivity")), options));
        d.put("vendorSoftware", vendorSoftware(Docs.str(row.get("protocol")), options));
        d.put("regulatory", regulatory(Docs.str(row.get("fdaCodes"))));
        d.put("security", initialSecurity());
        return d;
    }
}
