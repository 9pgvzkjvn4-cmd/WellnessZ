package com.wellnessz.casekernel.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DeviceContractTypesTest {

    @Test
    void optionCodesMapBothWays() {
        for (DeviceCodes.Option o : DeviceCodes.Option.values()) {
            assertThat(DeviceCodes.Option.ofCode(o.code())).isEqualTo(o);
        }
        assertThat(DeviceCodes.Option.ofCode("P10")).isEqualTo(DeviceCodes.Option.P10_MANUAL);
        assertThat(DeviceCodes.Option.ofCode("P1")).isEqualTo(DeviceCodes.Option.P1_SERIAL_USB);
        assertThatThrownBy(() -> DeviceCodes.Option.ofCode("P11")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anyBlockingGateBlocksTheObservation() {
        DeviceObservation.QualityFlag ok = new DeviceObservation.QualityFlag("PLAUSIBILITY", DeviceCodes.GateOutcome.PASS, "ok", "Plausible");
        DeviceObservation.QualityFlag cal = new DeviceObservation.QualityFlag("CALIBRATION", DeviceCodes.GateOutcome.BLOCK,
                "device_out_of_calibration", "Calibration expired");
        DeviceObservation.Provenance prov = new DeviceObservation.Provenance("MD-001", "ndd", "EasyOne Air", null, "SN1", "1.0", "2.3",
                "spiro.ndd.easyone", "1.0.0", "CAL-1", LocalDate.of(2026, 10, 1), "VALID", "nurse1", null, null, "SESSION",
                BigDecimal.ONE, 0, "00");
        DeviceObservation o = new DeviceObservation(UUID.randomUUID(), null, DeviceCodes.Category.SPIROMETRY, "E1001", null, null,
                Instant.now(), "DEV-000001", "SPI-001", DeviceCodes.CaptureMethod.DEVICE, null, List.of(ok), prov, null, false);
        assertThat(o.blocked()).isFalse();
        assertThat(o.components()).isEmpty();
        DeviceObservation blocked = new DeviceObservation(o.observationId(), null, o.category(), o.employeeId(), null, null, o.effectiveAt(),
                o.deviceInstanceId(), o.equipmentDeviceId(), o.captureMethod(), List.of(), List.of(ok, cal), prov, List.of(), false);
        assertThat(blocked.blocked()).isTrue();
    }

    @Test
    void defaultsAreSafe() {
        assertThat(DeviceCatalogueLookup.EMPTY.instance("DEV-1")).isEmpty();
        assertThat(DeviceCatalogueLookup.EMPTY.qcStatus("DEV-1", LocalDate.now(), null).blocks()).isFalse();
        assertThat(new DeviceCatalogueLookup.QcStatus("DUE", "SPIRO_SYRINGE_3L", null, null, null, null).blocks()).isTrue();
        assertThat(DeviceCaptureSessions.NONE.activeSession("DEV-1", Instant.now())).isEmpty();
        assertThat(DeviceEndpointAuthenticator.DENY_ALL.authenticate(
                new DeviceEndpointAuthenticator.Credentials("EP", "x", "t", "127.0.0.1", "POST", "/")).authenticated()).isFalse();
        assertThat(DeviceEquipmentRegister.NONE.list()).isEmpty();
        assertThat(new DeviceEndpointProvisioning.EndpointIssue("EP", DeviceCodes.EndpointStatus.PENDING_ENROLMENT, "one-time-secret", null)
                .toString()).doesNotContain("one-time-secret");
        assertThat(DeviceObservationSink.Delivery.rejected("no_open_test", "x").status()).isEqualTo(DeviceObservationSink.Delivery.Status.REJECTED);
    }
}
