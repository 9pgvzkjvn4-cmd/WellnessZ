package com.wellnessz.e03.device;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.casekernel.device.DeviceObservationSink;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e03.results.ResultService;

/**
 * {@code E03.RESULT} routing adapter (contract section 11): files a verified device observation through the existing E03
 * device-result capture ({@link ResultService#deviceFromEngine}) – calibration check, {@code DeviceRules} (STS, spirometry %
 * predicted, vision, ECG, the increment-6 categories), abnormal worklist, letters, follow-up listeners and audit stay E03
 * logic. Idempotent on the observation id (V21 {@code device_observation_id}).
 */
@Component
public class ResultDeviceSink implements DeviceObservationSink {

    public static final String TARGET = "E03.RESULT";
    static final Set<DeviceCodes.Category> CATEGORIES = EnumSet.of(DeviceCodes.Category.AUDIOMETRY, DeviceCodes.Category.SPIROMETRY,
            DeviceCodes.Category.VISION, DeviceCodes.Category.ECG, DeviceCodes.Category.VITALS, DeviceCodes.Category.BODY_COMPOSITION,
            DeviceCodes.Category.ABPM_HOLTER, DeviceCodes.Category.POC, DeviceCodes.Category.FIT_TEST, DeviceCodes.Category.ERGONOMICS,
            DeviceCodes.Category.IMAGING);

    private final ResultService results;

    public ResultDeviceSink(ResultService results) {
        this.results = results;
    }

    @Override
    public String target() {
        return TARGET;
    }

    @Override
    public Set<DeviceCodes.Category> categories() {
        return CATEGORIES;
    }

    @Override
    public Delivery deliver(String actor, Routed routed) {
        DeviceObservation o = routed.observation();
        if (o.employeeId() == null) {
            return Delivery.rejected("employee_missing", "An unassociated observation cannot be filed");
        }
        Map<String, Object> measurements = measurements(routed.fields());
        if (o.category() == DeviceCodes.Category.POC) {
            Map<String, Object> interp = new LinkedHashMap<>();
            for (DeviceObservation.Component c : o.components()) {
                if (c.interpretation() != null) {
                    interp.put(c.key(), c.interpretation());
                }
            }
            if (!interp.isEmpty()) {
                measurements.put("interpretations", interp);
            }
        }
        if (o.category() == DeviceCodes.Category.ECG && measurements.get("interpretation") instanceof String text) {
            measurements.put("interpretation", ecgClass(text));
            if (!text.equalsIgnoreCase(ecgClass(text))) {
                measurements.put("statement", text.length() > 500 ? text.substring(0, 500) : text);
            }
        }
        DeviceObservation.Provenance p = o.provenance();
        UUID orderId = orderId(o.orderRef());
        String deviceId = o.equipmentDeviceId() != null ? o.equipmentDeviceId() : null;
        ResultService.DeviceRequest req = new ResultService.DeviceRequest(o.category().name(), deviceId, p == null ? null : p.model(),
                p == null ? null : p.calibrationId(), p == null ? null : p.calibrationDate(), o.effectiveAt(), o.caseId(), orderId, measurements);
        try {
            ResultService.EngineResult r = results.deviceFromEngine(o.employeeId(), req, new ResultService.EngineProvenance(o.observationId(),
                    o.captureMethod().name(), p == null || p.driverPackageId() == null ? null : truncate(p.driverPackageId() + "@" + p.driverVersion(), 60),
                    routed.verified() ? actor : null, routed.verified() && p != null ? p.verifiedAt() : null, actor));
            return r.duplicate() ? Delivery.duplicate("RESULT", r.result().id().toString()) : Delivery.delivered("RESULT", r.result().id().toString());
        } catch (ApiException e) {
            return Delivery.rejected(e.code(), e.getMessage());
        }
    }

    /** Bound form fields ({@code measurements.left.500}) → the nested E03 measurement map ({@code left → {500 → …}}). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> measurements(Map<String, Object> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String path = e.getKey().startsWith("measurements.") ? e.getKey().substring("measurements.".length()) : e.getKey();
            String[] parts = path.split("\\.");
            Map<String, Object> cur = out;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = cur.get(parts[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    cur.put(parts[i], next);
                }
                cur = (Map<String, Object>) next;
            }
            Object v = e.getValue();
            cur.put(parts[parts.length - 1], v instanceof BigDecimal b ? normalise(b) : v);
        }
        return out;
    }

    static Number normalise(BigDecimal b) {
        BigDecimal s = b.stripTrailingZeros();
        return s.scale() <= 0 && s.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) < 0 ? (Number) s.intValueExact() : (Number) s.doubleValue();
    }

    /**
     * Device interpretation text → the E03 ECG class (unverified until physician sign-off): ABNORMAL / BORDERLINE / NORMAL
     * keywords, "sinus rhythm" without findings → NORMAL, anything else ABNORMAL so that a physician reviews it.
     */
    static String ecgClass(String text) {
        String t = text.toUpperCase(java.util.Locale.ROOT);
        if (t.contains("ABNORMAL")) {
            return "ABNORMAL";
        }
        if (t.contains("BORDERLINE")) {
            return "BORDERLINE";
        }
        if (t.contains("NORMAL") || t.startsWith("SINUS RHYTHM")) {
            return "NORMAL";
        }
        return "ABNORMAL";
    }

    static UUID orderId(String orderRef) {
        if (orderRef == null || !orderRef.startsWith("ORD:")) {
            return null;
        }
        try {
            return UUID.fromString(orderRef.substring(4));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
