package com.wellnessz.e12.device;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservationSink;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e12.screening.ScreeningService;

/**
 * {@code E12.SCREENING} routing adapter (contract section 11): kiosk / screening-day vitals, body composition and point-of-care
 * values into the screening-result logic of the event named by {@code orderRef}; thresholds and follow-up cases stay E12
 * logic. Bound fields are {@code measures.<name>} (systolic, diastolic, bmi, glucoseMmol, cholesterolMmol, waistCm).
 */
@Component
public class ScreeningResultSink implements DeviceObservationSink {

    private final ScreeningService screening;

    public ScreeningResultSink(ScreeningService screening) {
        this.screening = screening;
    }

    @Override
    public String target() {
        return "E12.SCREENING";
    }

    @Override
    public Set<DeviceCodes.Category> categories() {
        return EnumSet.of(DeviceCodes.Category.VITALS, DeviceCodes.Category.BODY_COMPOSITION, DeviceCodes.Category.POC);
    }

    @Override
    public Delivery deliver(String actor, Routed routed) {
        UUID eventId = eventId(routed.observation().orderRef());
        if (eventId == null || routed.observation().employeeId() == null) {
            return Delivery.rejected("no_screening_event", "The result names no screening event");
        }
        Map<String, Object> f = routed.fields();
        ScreeningService.Measures m = new ScreeningService.Measures(dec(f, "systolic"), dec(f, "diastolic"), dec(f, "bmi"), dec(f, "glucoseMmol"),
                dec(f, "cholesterolMmol"), dec(f, "waistCm"), null, null);
        if (m.asMap().isEmpty()) {
            return Delivery.rejected("binding_field_unknown", "No screening measure is bound");
        }
        try {
            ScreeningService.DeviceResult r = screening.recordFromDevice(actor, eventId, routed.observation().employeeId(), m);
            return r.duplicate() ? Delivery.duplicate("SCREENING_RESULT", r.result().id().toString())
                    : Delivery.delivered("SCREENING_RESULT", r.result().id().toString());
        } catch (ApiException e) {
            return Delivery.rejected(e.code(), e.getMessage());
        }
    }

    static BigDecimal dec(Map<String, Object> f, String name) {
        Object v = f.get("measures." + name);
        if (v == null) {
            v = f.get(name);
        }
        return v instanceof Number n ? new BigDecimal(n.toString()) : null;
    }

    static UUID eventId(String orderRef) {
        if (orderRef == null) {
            return null;
        }
        String s = orderRef.startsWith("SCR:") ? orderRef.substring(4) : orderRef;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
