package com.wellnessz.e31.driver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.e31.support.Ids;

/**
 * Generic FORM drivers for the fall-back chain (E33-HLR-10: OCR-verified values and manual entry with attestation) when the
 * device instance has no OCR / MANUAL package of its own – one per category, built from the submitted keys with the codes of
 * the E03 / E10 forms. The values still go through normalisation, gates (attestation, calibration, plausibility) and
 * provenance; verification is always mandatory.
 */
public final class FallbackDrivers {

    /** key → {loinc, display, ucum, min, max}; ucum {@code null} for text. */
    static final Map<String, String[]> CODES = new LinkedHashMap<>();

    static {
        code("systolic", "8480-6", "Systolic blood pressure", "mm[Hg]", "60", "260");
        code("diastolic", "8462-4", "Diastolic blood pressure", "mm[Hg]", "30", "160");
        code("pulse", "8867-4", "Heart rate", "/min", "25", "240");
        code("heartRate", "8867-4", "Heart rate", "/min", "25", "240");
        code("spo2", "59408-5", "Oxygen saturation by pulse oximetry", "%", "50", "100");
        code("temperatureC", "8310-5", "Body temperature", "Cel", "30", "43");
        code("weightKg", "29463-7", "Body weight", "kg", "20", "300");
        code("heightCm", "8302-2", "Body height", "cm", "100", "230");
        code("bmi", "39156-5", "Body mass index", "kg/m2", "10", "80");
        code("bodyFatPct", "41982-0", "Percentage of body fat", "%", "2", "75");
        code("fev1L", "20150-9", "FEV1", "L", "0.3", "8");
        code("fvcL", "19870-5", "FVC", "L", "0.5", "10");
        code("ratio", "19926-5", "FEV1/FVC", "%", "20", "100");
        code("pefLs", "33452-4", "Peak expiratory flow", "L/s", "0.5", "20");
        code("prMs", "8625-6", "PR interval", "ms", "60", "400");
        code("qrsMs", "8633-0", "QRS duration", "ms", "40", "250");
        code("qtcMs", "8636-3", "QTc interval", "ms", "250", "700");
        code("hba1c", "4548-4", "Hemoglobin A1c/Hemoglobin.total in Blood", "%", "3", "20");
        code("bll", "5671-3", "Lead [Mass/volume] in Blood", "ug/dL", "0", "65");
        code("glucose", "2345-7", "Glucose [Mass/volume] in Serum or Plasma", "mg/dL", "20", "600");
        code("cholesterol", "2093-3", "Cholesterol [Mass/volume] in Serum or Plasma", "mg/dL", "50", "500");
        code("brac", "75367-0", "Alcohol [Mass/volume] in Breath", "g/(210.L)", "0", "0.6");
        code("overallFitFactor", "LOCAL:FIT_FACTOR_OVERALL", "Respirator overall fit factor", "1", "1", "10000");
        code("acuityRight", "79880-1", "Visual acuity right eye", null, null, null);
        code("acuityLeft", "79881-9", "Visual acuity left eye", null, null, null);
        code("binocular", "98498-9", "Visual acuity both eyes", null, null, null);
        code("interpretation", "18844-1", "ECG interpretation", null, null, null);
        code("sessionGrade", "LOCAL:ATS_GRADE", "ATS/ERS session grade", null, null, null);
        code("qcLot", "LOCAL:QC_LOT", "Reagent / QC lot number", null, null, null);
        code("sequence", "LOCAL:BREATH_TEST_TYPE", "Breath test sequence", null, null, null);
        code("pass", "LOCAL:FIT_PASS", "Respirator fit test passed", null, null, null);
        code("protocol", "LOCAL:FIT_PROTOCOL", "Fit test protocol", null, null, null);
        code("exercises", "LOCAL:EXERCISES", "Fit test exercises", null, null, null);
        code("corrected", "LOCAL:CORRECTED", "Vision tested with correction", null, null, null);
        String[] ears = {"left", "right"};
        String[][] loinc = {{"89016-0", "89017-8", "89018-6", "89020-2", "89019-4", "89021-0", "89022-8"},
            {"89023-6", "89024-4", "89025-1", "89026-9", "89027-7", "89028-5", "89029-3"}};
        String[] freqs = {"500", "1000", "2000", "3000", "4000", "6000", "8000"};
        for (int e = 0; e < 2; e++) {
            for (int f = 0; f < freqs.length; f++) {
                code(ears[e] + "." + freqs[f], loinc[e][f], "Hearing threshold " + freqs[f] + " Hz " + ears[e] + " ear (air)", "dB[HL]", "-10", "120");
            }
        }
    }

    private FallbackDrivers() {
    }

    private static void code(String key, String loinc, String display, String ucum, String min, String max) {
        CODES.put(key, new String[] {loinc, display, ucum, min, max});
    }

    /**
     * @param values submitted values (key → value); numbers become quantities, booleans booleans, the rest text
     */
    public static DriverPackage forCategory(DeviceCodes.Category category, String adapter, Map<String, Object> values) {
        List<DriverPackage.FieldSpec> fields = new ArrayList<>();
        List<DriverPackage.Mapping> mappings = new ArrayList<>();
        Map<String, String> bound = new LinkedHashMap<>();
        boolean e10 = category == DeviceCodes.Category.BREATH_ALCOHOL || category == DeviceCodes.Category.DRUG_SCREEN;
        for (Map.Entry<String, Object> v : values.entrySet()) {
            String key = v.getKey();
            if (!key.matches("[A-Za-z][A-Za-z0-9_.]{0,59}")) {
                continue;
            }
            String[] c = CODES.get(key);
            String type = v.getValue() instanceof Number ? "QUANTITY" : v.getValue() instanceof Boolean ? "BOOLEAN" : "STRING";
            String ucum = c == null ? ("QUANTITY".equals(type) ? "1" : null) : c[2];
            fields.add(new DriverPackage.FieldSpec(key, key, type, "QUANTITY".equals(type) ? ucum : null, false, false));
            mappings.add(new DriverPackage.Mapping(key, null, c == null ? "LOCAL:" + key.toUpperCase(Locale.ROOT).replace('.', '_') : c[0],
                    c == null ? key : c[1], "QUANTITY".equals(type) ? ucum : null, null, side(key), null, null,
                    c == null || c[3] == null ? null : new BigDecimal(c[3]), c == null || c[4] == null ? null : new BigDecimal(c[4])));
            bound.put(key, e10 ? ("brac".equals(key) ? "breathScreenResult" : key) : "measurements." + key);
        }
        String target = e10 ? "E10.BREATH_ALCOHOL" : "E03.RESULT";
        String form = e10 ? "E10." + category.name() : "E03.DEVICE_RESULT." + category.name();
        List<String> gates = List.of("CALIBRATION", "ATTESTATION", "OCR_CONFIDENCE", "PLAUSIBILITY", "VERIFY_BEFORE_POST");
        DriverPackage.Binding binding = new DriverPackage.Binding(form, "1", category, target, bound, true, gates);
        DriverPackage.ParseSpec parse = new DriverPackage.ParseSpec("FORM", DriverPackage.IdentitySpec.EMPTY, null, Map.of(), fields);
        String id = "fallback." + category.name().toLowerCase(Locale.ROOT).replace('_', '-');
        String canonical = id + "|" + String.join(",", values.keySet());
        return new DriverPackage(id, "1.0.0", family(category), List.of("FALLBACK"), adapter, List.of("OCR".equals(adapter) ? "P9" : "P10"),
                null, DriverPackage.Capabilities.NONE, null, parse, mappings, List.of(), null, gates, List.of(binding), List.of(), null, canonical,
                Ids.sha256Hex(canonical));
    }

    static String side(String key) {
        if (key.startsWith("left.") || key.endsWith("Left")) {
            return key.startsWith("acuity") ? "LEFT_EYE" : "LEFT_EAR";
        }
        if (key.startsWith("right.") || key.endsWith("Right")) {
            return key.startsWith("acuity") ? "RIGHT_EYE" : "RIGHT_EAR";
        }
        return null;
    }

    static DeviceCodes.Family family(DeviceCodes.Category c) {
        return switch (c) {
            case AUDIOMETRY -> DeviceCodes.Family.AUDIOMETRY;
            case SPIROMETRY -> DeviceCodes.Family.SPIROMETRY;
            case VISION -> DeviceCodes.Family.VISION;
            case ECG, ABPM_HOLTER -> DeviceCodes.Family.CARDIOLOGY;
            case POC -> DeviceCodes.Family.POC;
            case BREATH_ALCOHOL, DRUG_SCREEN -> DeviceCodes.Family.DRUG_ALCOHOL;
            case FIT_TEST, ERGONOMICS -> DeviceCodes.Family.RESP_ERGONOMICS;
            case EXPOSURE -> DeviceCodes.Family.EXPOSURE;
            case WEARABLE -> DeviceCodes.Family.WEARABLE;
            case IMAGING -> DeviceCodes.Family.IMAGING;
            default -> DeviceCodes.Family.VITALS;
        };
    }
}
