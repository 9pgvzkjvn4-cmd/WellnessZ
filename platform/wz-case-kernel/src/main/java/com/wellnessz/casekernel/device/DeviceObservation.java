package com.wellnessz.casekernel.device;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A verified-or-pending canonical observation as handed from the engine (E31) to a consuming module or to the capture
 * dialogue (E33). It mirrors the FHIR Observation / DiagnosticReport / Provenance the engine stores (contract section 4);
 * the raw payload never travels – only its archive id and SHA-256.
 *
 * @param observationId  canonical observation id (FHIR Observation.id of the panel / single observation)
 * @param reportId       DiagnosticReport id for composite results (audiogram, spirometry session, 12-lead ECG, POC panel,
 *                       fit test), {@code null} for a single value
 * @param category       measurement category (form family)
 * @param employeeId     associated employee ({@code null} while unmatched – never routed)
 * @param caseId         OH case, if the capture session or order names one
 * @param orderRef       order / worklist reference (E03 lab order id, E10 test id, E12 screening event id …)
 * @param effectiveAt    measurement time, clock-skew corrected, UTC
 * @param deviceInstanceId published device instance (E32), {@code null} for manual entry without a device
 * @param equipmentDeviceId E14 equipment-register device id used for the calibration check, if registered
 * @param captureMethod  DEVICE | OCR_VERIFIED | MANUAL_ATTESTED
 * @param components     the measured values with their binding keys
 * @param qualityFlags   result of every quality gate that ran
 * @param provenance     who / what / which software, calibration and driver (FHIR Provenance)
 * @param attachments    PDF, waveform, image or raw payload archived by the engine (hash-linked)
 * @param restricted     {@code true} for restricted-class programmes (E10 breath alcohol / drug screen): only the named
 *                       restricted roles may preview or read it
 */
public record DeviceObservation(UUID observationId, UUID reportId, DeviceCodes.Category category, String employeeId, UUID caseId,
                                String orderRef, Instant effectiveAt, String deviceInstanceId, String equipmentDeviceId,
                                DeviceCodes.CaptureMethod captureMethod, List<Component> components, List<QualityFlag> qualityFlags,
                                Provenance provenance, List<Attachment> attachments, boolean restricted) {

    public DeviceObservation {
        components = components == null ? List.of() : List.copyOf(components);
        qualityFlags = qualityFlags == null ? List.of() : List.copyOf(qualityFlags);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** {@code true} when any gate returned {@link DeviceCodes.GateOutcome#BLOCK}. */
    public boolean blocked() {
        return qualityFlags.stream().anyMatch(f -> f.outcome() == DeviceCodes.GateOutcome.BLOCK);
    }

    /**
     * One measured value.
     *
     * @param key       binding key from the driver package, e.g. {@code fev1L}, {@code left.4000}, {@code systolic}
     * @param mdcCode   IEEE 11073-10101 MDC reference id (e.g. {@code MDC_PRESS_BLD_NONINV_SYS}), {@code null} if none exists
     * @param loincCode LOINC code (mandatory for posted observations, E31-F04)
     * @param display   human-readable name
     * @param valueType QUANTITY | STRING | CODE | BOOLEAN
     * @param value     numeric value for QUANTITY
     * @param text      value for STRING / CODE / BOOLEAN
     * @param ucum      UCUM unit for QUANTITY (e.g. {@code L}, {@code mm[Hg]}, {@code dB[HL]})
     * @param bodySite  body site / laterality code where relevant (e.g. {@code LEFT_EAR})
     * @param method    method code (e.g. {@code AIR_CONDUCTION}, {@code OSCILLOMETRIC})
     * @param interpretation N | L | H | A | null – device or reference-range interpretation
     */
    public record Component(String key, String mdcCode, String loincCode, String display, String valueType, BigDecimal value,
                            String text, String ucum, String bodySite, String method, String interpretation) {
    }

    /**
     * Result of one quality gate.
     *
     * @param gate    gate code, e.g. {@code CALIBRATION}, {@code QC_LOT}, {@code ATS_ERS_GRADE}, {@code AUDIO_TEST_RETEST},
     *                {@code BP_CUFF_VALIDATION}, {@code PLAUSIBILITY}, {@code VERIFY_BEFORE_POST}
     * @param code    machine code of the finding, e.g. {@code device_out_of_calibration}, {@code grade_C}
     * @param message plain-language explanation shown to the clinician (no PHI)
     */
    public record QualityFlag(String gate, DeviceCodes.GateOutcome outcome, String code, String message) {
    }

    /**
     * Provenance (E31-HLR-14).
     *
     * @param associationMethod WORKLIST | SESSION | BADGE | VENDOR_ID_MATCH | MANUAL_RESOLUTION | ENTERED_IN_FORM
     * @param associationConfidence 0.00–1.00
     * @param clockSkewMillis   device clock minus engine clock at receipt (applied to {@code effectiveAt})
     */
    public record Provenance(String modelCode, String manufacturer, String model, String udi, String serial, String firmware,
                             String softwareVersion, String driverPackageId, String driverVersion, String calibrationId,
                             LocalDate calibrationDate, String calibrationVerdict, String operator, String verifiedBy, Instant verifiedAt,
                             String associationMethod, BigDecimal associationConfidence, long clockSkewMillis, String rawPayloadSha256) {
    }

    /**
     * An archived binary linked to the observation.
     *
     * @param kind PDF | WAVEFORM | IMAGE | RAW | DICOM_METADATA
     */
    public record Attachment(UUID archiveId, String kind, String mediaType, String sha256, long sizeBytes) {
    }
}
