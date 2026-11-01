package com.wellnessz.e12.device;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservationSink;
import com.wellnessz.e12.wearable.WearableService;

/**
 * {@code E12.WEARABLE} routing adapter (contract section 11): consented wearable daily aggregates (bound fields
 * {@code STEPS}, {@code SLEEP_MINUTES}, {@code ACTIVE_MINUTES}) into the existing wearable-data logic – active enrolment and
 * {@code WEARABLE_DATA} consent, else {@code REJECTED consent_missing}. Upsert per metric and day, so re-delivery is harmless.
 */
@Component
public class WearableDailySink implements DeviceObservationSink {

    static final Set<String> METRICS = Set.of("STEPS", "SLEEP_MINUTES", "ACTIVE_MINUTES");

    private final WearableService wearables;

    public WearableDailySink(WearableService wearables) {
        this.wearables = wearables;
    }

    @Override
    public String target() {
        return "E12.WEARABLE";
    }

    @Override
    public Set<DeviceCodes.Category> categories() {
        return Set.of(DeviceCodes.Category.WEARABLE);
    }

    @Override
    public Delivery deliver(String actor, Routed routed) {
        String employee = routed.observation().employeeId();
        if (employee == null) {
            return Delivery.rejected("consent_missing", "Unassociated wearable data is not stored");
        }
        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        routed.fields().forEach((k, v) -> {
            if (METRICS.contains(k) && v instanceof Number n) {
                metrics.put(k, new BigDecimal(n.toString()));
            }
        });
        if (metrics.isEmpty()) {
            return Delivery.rejected("binding_field_unknown", "No wearable metric is bound");
        }
        LocalDate day = LocalDate.ofInstant(routed.observation().effectiveAt(), ZoneOffset.UTC);
        List<UUID> enrolments = wearables.ingestFromDevice(actor, employee, metrics, day);
        if (enrolments.isEmpty()) {
            return Delivery.rejected("consent_missing", "No active wearable enrolment with consent");
        }
        return Delivery.delivered("WEARABLE_DAILY", enrolments.get(0) + ":" + day);
    }
}
