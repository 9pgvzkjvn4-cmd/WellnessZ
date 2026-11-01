package com.wellnessz.e31.gate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.e31.driver.DriverPackage;

/**
 * Everything a quality gate looks at.
 *
 * @param sessionMode      FORM | BATCH | KIOSK when a capture session bound the result, else {@code null}
 * @param attestedModel    manual-entry attestation (P10)
 * @param ocrConfidence    lowest OCR field confidence (P9)
 */
public record GateContext(DriverPackage pkg, DeviceCodes.Category category, List<DeviceObservation.Component> components,
                          String deviceInstanceId, String equipmentDeviceId, String modelCode, Map<String, Object> instanceParameters,
                          String employeeId, Instant effectiveAt, long clockSkewMillis, long maxSkewSeconds,
                          DeviceCodes.CaptureMethod captureMethod, String sessionMode, String attestedModel, String attestedSerial,
                          String attestedCalibrationId, LocalDate attestedCalibrationDate, BigDecimal ocrConfidence,
                          BigDecimal ocrMinConfidence, boolean verifyBeforePost, GateEnvironment env) {

    public GateContext {
        components = components == null ? List.of() : List.copyOf(components);
        instanceParameters = instanceParameters == null ? Map.of() : Map.copyOf(instanceParameters);
    }

    public Optional<DeviceObservation.Component> component(String key) {
        return components.stream().filter(c -> c.key().equals(key)).findFirst();
    }

    public BigDecimal number(String key) {
        return component(key).map(DeviceObservation.Component::value).orElse(null);
    }

    public String text(String key) {
        return component(key).map(c -> c.text() != null ? c.text() : c.value() == null ? null : c.value().toPlainString()).orElse(null);
    }

    public LocalDate day() {
        return LocalDate.ofInstant(effectiveAt, ZoneOffset.UTC);
    }
}
