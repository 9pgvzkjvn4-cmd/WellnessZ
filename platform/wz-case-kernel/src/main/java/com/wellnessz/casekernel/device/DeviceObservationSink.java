package com.wellnessz.casekernel.device;

import java.util.Map;
import java.util.Set;

/**
 * Routing SPI (E31-HLR-07): a consuming OH module accepts a canonical, gate-checked observation into its own record and
 * runs its own clinical logic (STS, ATS/ERS-based follow-up, abnormal worklist, fitness, programme rules). Implemented by
 * small adapters inside the consuming modules – E03 ({@code E03.RESULT}: extends the existing device-result capture),
 * E07 ({@code E07.IH_MEASUREMENT}), E10 ({@code E10.BREATH_ALCOHOL}), E12 ({@code E12.SCREENING}, {@code E12.WEARABLE}) –
 * and called only by the E31 router. Contract section 11.
 *
 * <p>Implementations must be idempotent on {@link DeviceObservation#observationId()}: a second delivery returns
 * {@link Delivery.Status#DUPLICATE} with the record created the first time. They must never re-run the engine's gates
 * but may reject with a module rule ({@link Delivery.Status#REJECTED} + code).</p>
 */
public interface DeviceObservationSink {

    /** Routing target this sink owns, e.g. {@code E03.RESULT}. Unique across the application. */
    String target();

    /** Measurement categories accepted by this target. */
    Set<DeviceCodes.Category> categories();

    /**
     * Files the observation.
     *
     * @param actor  user who verified the result (clinician), or {@code device-engine} for auto-posting forms
     * @param routed observation plus the bound form fields
     */
    Delivery deliver(String actor, Routed routed);

    /**
     * @param formCode    form code from the binding, e.g. {@code E03.DEVICE_RESULT.SPIROMETRY}
     * @param formVersion form version the binding was certified for
     * @param fields      bound values keyed by form field path (from the driver binding: component key → field)
     * @param verified    {@code true} when a clinician accepted the preview (verify-before-post), {@code false} for auto-post
     */
    record Routed(DeviceObservation observation, String formCode, String formVersion, Map<String, Object> fields, boolean verified) {
        public Routed {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }

    /**
     * @param recordType module record type, e.g. {@code RESULT}, {@code SCREENING_RESULT}, {@code IH_MEASUREMENT}
     * @param recordId   id of the module record created (or found, for DUPLICATE)
     * @param code       rejection code for REJECTED (e.g. {@code no_open_test}, {@code consent_missing})
     */
    record Delivery(Status status, String recordType, String recordId, String code, String message) {

        public enum Status { DELIVERED, DUPLICATE, REJECTED }

        public static Delivery delivered(String recordType, String recordId) {
            return new Delivery(Status.DELIVERED, recordType, recordId, null, null);
        }

        public static Delivery duplicate(String recordType, String recordId) {
            return new Delivery(Status.DUPLICATE, recordType, recordId, null, null);
        }

        public static Delivery rejected(String code, String message) {
            return new Delivery(Status.REJECTED, null, null, code, message);
        }
    }
}
