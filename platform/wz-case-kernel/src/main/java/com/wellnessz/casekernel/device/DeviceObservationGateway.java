package com.wellnessz.casekernel.device;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Commands on canonical observations, implemented by the engine (E31) and used by capture (E33): preview, accept (route),
 * reject, and submitting fall-back values (image/OCR verified, manual with attestation) into the same pipeline so that
 * they get the same normalisation, gates and provenance. Contract sections 12 and 14.
 */
public interface DeviceObservationGateway {

    /** The observation with its bound form fields and status; empty when unknown. Callers authorise the viewer first. */
    Optional<ObservationPreview> preview(UUID observationId);

    /**
     * Clinician verification: routes the observation to its target sink. Device values are immutable – a wrong value is
     * rejected and re-measured, never edited. 422 {@code observation_blocked} when a gate blocks, 409 when not pending.
     */
    DeviceObservationSink.Delivery accept(String actor, UUID observationId, UUID sessionId, String note);

    /** @param reasonCode RETEST | WRONG_EMPLOYEE | ARTEFACT | DEVICE_FAULT | OTHER */
    void reject(String actor, UUID observationId, UUID sessionId, String reasonCode, String note);

    /** Submits fall-back values; returns the new observation id (status PENDING_VERIFICATION – OCR and manual always verify). */
    UUID submitFallback(String actor, FallbackSubmission submission);

    /**
     * @param status PENDING_VERIFICATION | BLOCKED | ROUTED | REJECTED | UNMATCHED | DEAD_LETTER
     * @param fields bound values keyed by form field path
     */
    record ObservationPreview(DeviceObservation observation, String status, String formCode, String formVersion,
                              Map<String, Object> fields, boolean verifyBeforePost) {

        public ObservationPreview {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }

    /**
     * @param method          OCR_VERIFIED | MANUAL_ATTESTED
     * @param deviceInstanceId the (unavailable or display-only) device, {@code null} for an unregistered manual instrument
     * @param values          component key → value as confirmed by the clinician
     * @param imageArchiveId  photo of the display / printout (OCR), archived through the engine's upload endpoint
     * @param ocrConfidence   lowest OCR field confidence 0.00–1.00 (OCR only)
     * @param attestation     attestation statement code (MANUAL: {@code DEVICE_AND_CALIBRATION_ATTESTED})
     */
    record FallbackSubmission(UUID sessionId, DeviceCodes.CaptureMethod method, String deviceInstanceId, String attestedModel,
                              String attestedSerial, String attestedCalibrationId, LocalDate attestedCalibrationDate,
                              DeviceCodes.Category category, String employeeId, String formCode, Map<String, Object> values,
                              UUID imageArchiveId, BigDecimal ocrConfidence, String attestation) {

        public FallbackSubmission {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }
}
