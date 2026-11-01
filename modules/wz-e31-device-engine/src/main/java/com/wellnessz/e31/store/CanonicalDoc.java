package com.wellnessz.e31.store;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wellnessz.casekernel.device.DeviceObservation;

/**
 * Canonical content of an observation (encrypted column {@code observation.canonical}): the components with codes, the
 * quality flags, provenance and attachments, plus the demographics used for predicted values and the fall-back attestation.
 */
public record CanonicalDoc(List<DeviceObservation.Component> components, List<DeviceObservation.QualityFlag> flags,
                           DeviceObservation.Provenance provenance, List<DeviceObservation.Attachment> attachments, Demographics demographics,
                           Map<String, Object> derived, Fallback fallback) {

    public CanonicalDoc {
        components = components == null ? List.of() : List.copyOf(components);
        flags = flags == null ? List.of() : List.copyOf(flags);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        derived = derived == null ? Map.of() : Map.copyOf(derived);
    }

    public record Demographics(String sex, Integer ageYears, BigDecimal heightCm, LocalDate dateOfBirth, String name) {
    }

    public record Fallback(String attestedModel, String attestedSerial, String attestedCalibrationId, LocalDate attestedCalibrationDate,
                           BigDecimal ocrConfidence, UUID imageArchiveId, String attestation) {
    }

    public CanonicalDoc withFlags(List<DeviceObservation.QualityFlag> newFlags) {
        return new CanonicalDoc(components, newFlags, provenance, attachments, demographics, derived, fallback);
    }

    public CanonicalDoc withProvenance(DeviceObservation.Provenance p) {
        return new CanonicalDoc(components, flags, p, attachments, demographics, derived, fallback);
    }

    public CanonicalDoc withAttachments(List<DeviceObservation.Attachment> a) {
        return new CanonicalDoc(components, flags, provenance, a, demographics, derived, fallback);
    }
}
