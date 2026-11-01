package com.wellnessz.e31.driver;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.wellnessz.casekernel.device.DeviceCodes;

import tools.jackson.databind.JsonNode;

/**
 * A declarative driver package ({@code wellnessz.device-driver/1}, contract section 6.1) – parsing template, mappings,
 * reference ranges, predicted-value equation, quality gates, default bindings and test vectors. No code per model.
 *
 * @param canonicalJson canonical JSON without the {@code signature} member (what the signature covers)
 * @param sha256        SHA-256 of {@code canonicalJson}
 */
public record DriverPackage(String packageId, String version, DeviceCodes.Family family, List<String> models, String adapter,
                            List<String> options, String engineCompatibility, Capabilities capabilities, JsonNode protocol,
                            ParseSpec parse, List<Mapping> mappings, List<RefRange> referenceRanges, Predicted predicted,
                            List<String> qualityGates, List<Binding> bindings, List<TestVector> testVectors, Signature signature,
                            String canonicalJson, String sha256) {

    public static final String SCHEMA = "wellnessz.device-driver/1";

    public String ref() {
        return packageId + "@" + version;
    }

    public Optional<Mapping> mapping(String key) {
        return mappings.stream().filter(m -> m.key().equals(key)).findFirst();
    }

    public Optional<Binding> binding(String formCode) {
        return bindings.stream().filter(b -> b.formCode().equals(formCode)).findFirst();
    }

    /** Text value of a protocol member, {@code null} when absent. */
    public String protocolText(String member) {
        JsonNode n = protocol == null ? null : protocol.get(member);
        return n == null || n.isNull() || !n.isValueNode() ? null : n.asString();
    }

    /**
     * @param worklistPush NONE | HL7_ORM | HL7_ADT | GDT_6302 | DICOM_MWL | VENDOR_IMPORT_FILE
     */
    public record Capabilities(String worklistPush, boolean startFromForm, boolean historicRetrieval) {
        public static final Capabilities NONE = new Capabilities("NONE", false, false);
    }

    /**
     * @param records    record selector (XPath node set, GDT test id field, …) or {@code null}
     * @param namespaces XPath namespace prefixes
     */
    public record ParseSpec(String type, IdentitySpec identity, String records, Map<String, String> namespaces, List<FieldSpec> fields) {
    }

    /**
     * Identity selectors. Beyond the contract's {@code employee}, {@code badge}, {@code deviceSerial}, {@code orderRef} and
     * {@code effectiveTime}, drivers may declare vendor demographics used for association and predicted values
     * ({@code name}, {@code dateOfBirth}, {@code sex}, {@code ageYears}, {@code heightCm}) and the {@code operator}.
     */
    public record IdentitySpec(String employee, String badge, String deviceSerial, String orderRef, String effectiveSelector,
                               String effectiveFormat, String timeZone, String name, String dateOfBirth, String sex, String ageYears,
                               String heightCm, String operator) {
        public static final IdentitySpec EMPTY = new IdentitySpec(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * @param type      QUANTITY | STRING | CODE | BOOLEAN
     * @param technical {@code true} when the field declares no type: a transport field (flags, time stamp, unit flag, serial)
     *                  that is used by the parser or the identity but is not a measured component
     */
    public record FieldSpec(String key, String selector, String type, String unit, boolean required, boolean technical) {
    }

    public record Convert(String fromUnit, BigDecimal factor, BigDecimal offset) {
    }

    public record Mapping(String key, String mdc, String loinc, String display, String ucum, Convert convert, String bodySite,
                          String method, Integer decimals, BigDecimal plausibleMin, BigDecimal plausibleMax) {
    }

    public record RefRange(String key, String sex, int ageMin, int ageMax, BigDecimal low, BigDecimal high, String text) {
    }

    /** @param keys component key → GLI parameter (FEV1 | FVC | FEV1FVC) */
    public record Predicted(String equation, Map<String, String> keys) {
    }

    /**
     * Default result-to-form binding. Field keys may also be {@code param:<name>} (an instance parameter, e.g. the exposure
     * group of a dosimeter) or {@code const:<value>} (a constant, e.g. the measurement type).
     */
    public record Binding(String formCode, String formVersion, DeviceCodes.Category category, String target, Map<String, String> fields,
                          boolean verifyBeforePost, List<String> qualityGates) {
    }

    public record TestVector(String name, String input, JsonNode expect) {
    }

    public record Signature(String keyId, String algorithm, String value) {
    }
}
