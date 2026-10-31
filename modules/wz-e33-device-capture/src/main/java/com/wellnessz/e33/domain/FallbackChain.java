package com.wellnessz.e33.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.wellnessz.common.error.ApiException;

/**
 * Graceful degradation (E33-HLR-10, E33-BL-002, contract 14.3): when the chosen device is unavailable, locked out or fails,
 * the form offers – in this order – (1) another certified, available device of the same category at the station, (2) image /
 * OCR with mandatory verification when an OCR package exists for the category, (3) manual entry with device attestation
 * (P10). Restricted categories have no manual path except for the collector (MRO-visible attestation). The session records
 * which path was used.
 */
public final class FallbackChain {

    public static final String ALTERNATE_DEVICE = "ALTERNATE_DEVICE";
    public static final String OCR_VERIFIED = "OCR_VERIFIED";
    public static final String MANUAL_ATTESTED = "MANUAL_ATTESTED";
    public static final String DEVICE = "DEVICE";

    /** Attestation statement code of a manual entry (contract 14.3). */
    public static final String ATTESTATION = "DEVICE_AND_CALIBRATION_ATTESTED";

    public static final Set<String> REASONS = Set.of("DEVICE_UNAVAILABLE", "DEVICE_LOCKED", "CONNECTION_FAILED", "DISPLAY_ONLY", "OTHER");

    /** An available alternate device (already filtered to the category and station). */
    public record Alternate(String deviceInstanceId, String displayName) {
    }

    /** One offered option, in chain order. */
    public record Option(int rank, String path, String deviceInstanceId, String label) {
    }

    /** The attestation of a manual entry. */
    public record Attestation(String model, String serial, String calibrationId, LocalDate calibrationDate) {
    }

    private FallbackChain() {
    }

    /**
     * @param alternates   available devices of the same category at the station, excluding the failed one
     * @param ocrAvailable an OCR (P9) package exists for the category
     * @param restricted   restricted category (E10)
     * @param collector    the operator is a collector (only role allowed the manual path for restricted categories)
     */
    public static List<Option> offer(List<Alternate> alternates, boolean ocrAvailable, boolean restricted, boolean collector) {
        List<Option> out = new ArrayList<>();
        int rank = 1;
        for (Alternate a : alternates) {
            out.add(new Option(rank++, ALTERNATE_DEVICE, a.deviceInstanceId(), a.displayName()));
        }
        if (ocrAvailable && !restricted) {
            out.add(new Option(rank++, OCR_VERIFIED, null, "Photo of the display – verify every value"));
        }
        if (!restricted || collector) {
            out.add(new Option(rank, MANUAL_ATTESTED, null, "Enter manually – attest device and calibration"));
        }
        return out;
    }

    /**
     * Validates a fall-back submission against the offered chain; 422 with a contract code when it does not fit.
     *
     * @return the chosen path
     */
    public static String validate(String path, String reasonCode, String alternateDeviceId, List<Option> offered, Map<String, Object> values,
                                  java.util.UUID imageArchiveId, Attestation attestation, LocalDate today) {
        if (path == null || reasonCode == null || !REASONS.contains(reasonCode)) {
            throw ApiException.badRequest("validation_failed", "A fall-back path and a reason code are required");
        }
        boolean offeredPath = offered.stream().anyMatch(o -> o.path().equals(path)
                && (!ALTERNATE_DEVICE.equals(path) || o.deviceInstanceId().equals(alternateDeviceId)));
        if (!offeredPath) {
            throw unprocessable("fallback_not_offered", "This fall-back option is not available for the session");
        }
        switch (path) {
            case ALTERNATE_DEVICE -> {
                // nothing else: the session listens on the alternate device
            }
            case OCR_VERIFIED -> {
                if (imageArchiveId == null) {
                    throw unprocessable("image_required", "An OCR fall-back keeps the photo as evidence – upload it first");
                }
                requireValues(values);
            }
            case MANUAL_ATTESTED -> {
                requireValues(values);
                if (attestation == null || blank(attestation.model()) || blank(attestation.serial()) || blank(attestation.calibrationId())
                        || attestation.calibrationDate() == null || attestation.calibrationDate().isAfter(today)) {
                    throw unprocessable("attestation_incomplete", "Attest the device model, serial number, calibration id and calibration date");
                }
            }
            default -> throw ApiException.badRequest("validation_failed", "Unknown fall-back path");
        }
        return path;
    }

    private static void requireValues(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            throw ApiException.badRequest("validation_failed", "At least one verified value is required");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
