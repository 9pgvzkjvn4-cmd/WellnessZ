package com.wellnessz.e10.device;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.casekernel.device.DeviceObservationSink;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e10.collection.CollectionService;
import com.wellnessz.e10.model.DaTest;
import com.wellnessz.e10.model.E10TestRepository;
import com.wellnessz.e10.support.TestingViews.Requirement;

/**
 * {@code E10.BREATH_ALCOHOL} routing adapter (restricted class, contract section 11): records the device breath reading
 * against the open test named by {@code orderRef} (E10 test id, from the capture session or worklist) through the existing
 * collection logic – custody form, MRO pathway. No open test → {@code REJECTED no_open_test}. Instrumented drug-screen
 * readers ({@code E10.DRUG_SCREEN}) are not wired in this increment and are rejected.
 */
@Component
public class BreathAlcoholSink implements DeviceObservationSink {

    private final CollectionService collection;
    private final E10TestRepository tests;

    public BreathAlcoholSink(CollectionService collection, E10TestRepository tests) {
        this.collection = collection;
        this.tests = tests;
    }

    @Override
    public String target() {
        return "E10.BREATH_ALCOHOL";
    }

    @Override
    public Set<DeviceCodes.Category> categories() {
        return EnumSet.of(DeviceCodes.Category.BREATH_ALCOHOL, DeviceCodes.Category.DRUG_SCREEN);
    }

    @Override
    public Delivery deliver(String actor, Routed routed) {
        DeviceObservation o = routed.observation();
        if (o.category() == DeviceCodes.Category.DRUG_SCREEN) {
            return Delivery.rejected("instrumented_reader_not_supported", "Instrumented drug-screen readers are not connected in this increment");
        }
        UUID testId = testId(o.orderRef());
        DaTest test = testId == null ? null : tests.findById(testId).orElse(null);
        if (test == null || o.employeeId() == null || !o.employeeId().equals(test.getEmployeeId())) {
            return Delivery.rejected("no_open_test", "No open test for this employee and reference");
        }
        Object brac = routed.fields().get("breathScreenResult");
        if (!(brac instanceof Number)) {
            return Delivery.rejected("binding_field_unknown", "breathScreenResult is required");
        }
        String serial = o.provenance() == null ? null : o.provenance().serial();
        String remarks = "Device reading" + (serial == null ? "" : " – serial " + serial)
                + (routed.fields().get("sequence") == null ? "" : ", " + routed.fields().get("sequence"))
                + (routed.fields().get("lastCalibration") == null ? "" : ", calibrated " + routed.fields().get("lastCalibration"));
        try {
            Optional<Requirement> r = collection.collectDeviceBreath(actor, testId, o.observationId(), new BigDecimal(brac.toString()), o.effectiveAt(),
                    null, remarks.length() > 1000 ? remarks.substring(0, 1000) : remarks);
            return r.isPresent() ? Delivery.delivered("DA_TEST_READING", testId.toString()) : Delivery.duplicate("DA_TEST_READING", testId.toString());
        } catch (ApiException e) {
            return Delivery.rejected("already_collected".equals(e.code()) || "specimen_not_required".equals(e.code()) ? "no_open_test" : e.code(),
                    e.getMessage());
        }
    }

    static UUID testId(String orderRef) {
        if (orderRef == null) {
            return null;
        }
        String s = orderRef.startsWith("DA:") ? orderRef.substring(3) : orderRef;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
