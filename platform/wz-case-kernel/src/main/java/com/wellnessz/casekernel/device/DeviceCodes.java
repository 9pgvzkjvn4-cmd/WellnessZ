package com.wellnessz.casekernel.device;

/** Shared code lists of the device integration engine (contract section 3). */
public final class DeviceCodes {

    private DeviceCodes() {
    }

    /** Catalogue families – the eleven families of the device inventory (Appendix A, 133 models). */
    public enum Family {
        SPIROMETRY, AUDIOMETRY, VITALS, CARDIOLOGY, VISION, POC, DRUG_ALCOHOL, RESP_ERGONOMICS, EXPOSURE, WEARABLE, IMAGING
    }

    /**
     * Measurement categories – the form families a result is bound to (E31-F07). The first four are the categories the
     * E03 device-result capture already accepts; the others extend it (contract section 11).
     */
    public enum Category {
        AUDIOMETRY, SPIROMETRY, VISION, ECG, VITALS, BODY_COMPOSITION, ABPM_HOLTER, BREATH_ALCOHOL, DRUG_SCREEN, POC, FIT_TEST,
        ERGONOMICS, EXPOSURE, WEARABLE, IMAGING
    }

    /** Connectivity options P1–P10 (solution document section 5). */
    public enum Option {
        P1_SERIAL_USB, P2_VENDOR_SOFTWARE, P3_BLUETOOTH, P4_HL7_MLLP, P5_POCT1A, P6_CLOUD_API, P7_DICOM, P8_FHIR, P9_IMAGE_OCR, P10_MANUAL;

        /** {@code P4} → {@link #P4_HL7_MLLP}. */
        public static Option ofCode(String code) {
            for (Option o : values()) {
                if (o.name().startsWith(code + "_")) {
                    return o;
                }
            }
            throw new IllegalArgumentException("Unknown connectivity option");
        }

        public String code() {
            return name().substring(0, name().indexOf('_'));
        }
    }

    /** How a value was obtained (E33-HLR-10: the record says which path was used). */
    public enum CaptureMethod { DEVICE, OCR_VERIFIED, MANUAL_ATTESTED }

    /** Outcome of one quality gate (contract section 10). {@code BLOCK} prevents posting. */
    public enum GateOutcome { PASS, INFO, WARN, BLOCK }

    /** Kinds of engine-facing endpoint identities (E34-HLR-01). */
    public enum EndpointKind { GATEWAY, DEVICE_HOST_AGENT, MOBILE_APP, KIOSK, DATA_MANAGER, VENDOR_CLOUD, DEVICE }

    /** Life-cycle of an endpoint identity (E34). */
    public enum EndpointStatus { PENDING_ENROLMENT, ACTIVE, ISOLATED, REVOKED }

    /** Severity of a device security event (SIEM mapping in contract section 15.9). */
    public enum Severity { INFO, LOW, MEDIUM, HIGH, CRITICAL }
}
