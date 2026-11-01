package com.wellnessz.e07.device;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservationSink;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e07.exposure.ExposureService;

/**
 * {@code E07.IH_MEASUREMENT} routing adapter (contract section 11): exposure feeds (noise dosimeters, gas-detector docks,
 * vibration) become one industrial-hygiene measurement row through the existing {@link ExposureService} import – source
 * reference = observation id (duplicates ignored by E07), programme enrolment and action levels stay E07 logic. The bound
 * fields carry {@code value}, {@code segCode}, {@code hazardCode}, {@code measurementType} and {@code unit}.
 */
@Component
public class ExposureMeasurementSink implements DeviceObservationSink {

    private final ExposureService exposure;

    public ExposureMeasurementSink(ExposureService exposure) {
        this.exposure = exposure;
    }

    @Override
    public String target() {
        return "E07.IH_MEASUREMENT";
    }

    @Override
    public Set<DeviceCodes.Category> categories() {
        return Set.of(DeviceCodes.Category.EXPOSURE);
    }

    @Override
    public Delivery deliver(String actor, Routed routed) {
        Map<String, Object> f = routed.fields();
        Object value = f.get("value");
        String seg = text(f.get("segCode"));
        String hazard = text(f.get("hazardCode"));
        String type = text(f.get("measurementType"));
        String unit = text(f.get("unit"));
        if (!(value instanceof Number) || seg == null || hazard == null || type == null) {
            return Delivery.rejected("binding_field_unknown", "value, segCode, hazardCode and measurementType are required");
        }
        String ref = "device:" + routed.observation().observationId();
        LocalDate measuredOn = LocalDate.ofInstant(routed.observation().effectiveAt(), ZoneOffset.UTC);
        try {
            ExposureService.ImportResult r = exposure.importMeasurements(actor, List.of(new ExposureService.MeasurementRow(ref, seg, hazard, type,
                    new BigDecimal(value.toString()), unit == null ? "dB(A)" : unit, measuredOn)));
            return r.duplicates() > 0 ? Delivery.duplicate("IH_MEASUREMENT", ref) : Delivery.delivered("IH_MEASUREMENT", ref);
        } catch (ApiException e) {
            return Delivery.rejected(e.code(), e.getMessage());
        }
    }

    static String text(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
