package com.wellnessz.e14.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.support.DeviceCalibrationCheck;
import com.wellnessz.e14.equipment.EquipmentCalibrationCheck;
import com.wellnessz.e14.model.Equipment;
import com.wellnessz.e14.model.EquipmentRepository;

class EquipmentRulesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    @Test
    void calibrationStateIsDerivedFromTheDueDate() {
        assertThat(EquipmentRules.state(TODAY.plusDays(31), TODAY, 30)).isEqualTo(EquipmentRules.CalibrationState.VALID);
        assertThat(EquipmentRules.state(TODAY.plusDays(30), TODAY, 30)).isEqualTo(EquipmentRules.CalibrationState.DUE_SOON);
        assertThat(EquipmentRules.state(TODAY, TODAY, 30)).isEqualTo(EquipmentRules.CalibrationState.DUE_SOON);
        assertThat(EquipmentRules.state(TODAY.minusDays(1), TODAY, 30)).isEqualTo(EquipmentRules.CalibrationState.OVERDUE);
        assertThat(EquipmentRules.state(null, TODAY, 30)).isEqualTo(EquipmentRules.CalibrationState.VALID);
    }

    @Test
    void nextDueAndDueAlerts() {
        assertThat(EquipmentRules.nextDue(TODAY, null, 6)).isEqualTo(TODAY.plusMonths(6));
        assertThat(EquipmentRules.nextDue(TODAY, null, null)).isEqualTo(TODAY.plusMonths(12));
        assertThat(EquipmentRules.nextDue(TODAY, TODAY.plusDays(90), 12)).isEqualTo(TODAY.plusDays(90));
        assertThat(EquipmentRules.dueAlert(TODAY.plusDays(30), TODAY, 30, null)).isTrue();
        assertThat(EquipmentRules.dueAlert(TODAY.plusDays(31), TODAY, 30, null)).isFalse();
        assertThat(EquipmentRules.dueAlert(TODAY.plusDays(10), TODAY, 30, TODAY.plusDays(10))).isFalse();
        assertThat(EquipmentRules.dueAlert(TODAY.minusDays(3), TODAY, 30, null)).isTrue();
    }

    @Test
    void excursionDetection() {
        BigDecimal min = new BigDecimal("2.0");
        BigDecimal max = new BigDecimal("8.0");
        assertThat(EquipmentRules.evaluate(false, new BigDecimal("5.0"), min, max)).isEqualTo(EquipmentRules.ExcursionAction.NONE);
        assertThat(EquipmentRules.evaluate(false, new BigDecimal("8.0"), min, max)).isEqualTo(EquipmentRules.ExcursionAction.NONE);
        assertThat(EquipmentRules.evaluate(false, new BigDecimal("8.1"), min, max)).isEqualTo(EquipmentRules.ExcursionAction.OPEN);
        assertThat(EquipmentRules.evaluate(false, new BigDecimal("1.9"), min, max)).isEqualTo(EquipmentRules.ExcursionAction.OPEN);
        assertThat(EquipmentRules.evaluate(true, new BigDecimal("9.5"), min, max)).isEqualTo(EquipmentRules.ExcursionAction.EXTEND);
        assertThat(EquipmentRules.evaluate(true, new BigDecimal("6.0"), min, max)).isEqualTo(EquipmentRules.ExcursionAction.END);
        // default fridge thresholds 2–8 °C
        assertThat(EquipmentRules.outOfRange(new BigDecimal("8.5"), null, null)).isTrue();
        assertThat(EquipmentRules.outOfRange(new BigDecimal("2.0"), null, null)).isFalse();
        assertThat(EquipmentRules.level(new BigDecimal("9"), max)).isEqualTo("HIGH");
        assertThat(EquipmentRules.level(new BigDecimal("1"), max)).isEqualTo("LOW");
    }

    @Test
    void deviceVerdicts() {
        assertThat(EquipmentRules.verdict(false, null, null, TODAY)).isEqualTo(DeviceCalibrationCheck.Verdict.UNKNOWN);
        assertThat(EquipmentRules.verdict(true, "OUT_OF_SERVICE", TODAY.plusDays(10), TODAY)).isEqualTo(DeviceCalibrationCheck.Verdict.OUT_OF_SERVICE);
        assertThat(EquipmentRules.verdict(true, "RETIRED", TODAY.plusDays(10), TODAY)).isEqualTo(DeviceCalibrationCheck.Verdict.OUT_OF_SERVICE);
        assertThat(EquipmentRules.verdict(true, "IN_SERVICE", TODAY.minusDays(1), TODAY)).isEqualTo(DeviceCalibrationCheck.Verdict.OUT_OF_CALIBRATION);
        assertThat(EquipmentRules.verdict(true, "IN_SERVICE", TODAY, TODAY)).isEqualTo(DeviceCalibrationCheck.Verdict.VALID);
        assertThat(EquipmentRules.verdict(true, "IN_SERVICE", null, TODAY)).isEqualTo(DeviceCalibrationCheck.Verdict.VALID);
    }

    @Test
    void deviceCalibrationCheckLooksUpTheRegisterByDeviceId() {
        EquipmentRepository repo = mock(EquipmentRepository.class);
        Equipment overdue = Equipment.create();
        overdue.setDeviceId("AUD-2");
        overdue.setStatus("IN_SERVICE");
        overdue.setCalibrationDueOn(TODAY.minusDays(5));
        overdue.setLastCertificateNumber("CAL-9");
        when(repo.findByDeviceId("AUD-2")).thenReturn(Optional.of(overdue));
        when(repo.findByDeviceId("NEW-1")).thenReturn(Optional.empty());
        EquipmentCalibrationCheck check = new EquipmentCalibrationCheck(repo);
        DeviceCalibrationCheck.Status st = check.check("AUD-2", TODAY);
        assertThat(st.verdict()).isEqualTo(DeviceCalibrationCheck.Verdict.OUT_OF_CALIBRATION);
        assertThat(st.calibrationId()).isEqualTo("CAL-9");
        assertThat(st.calibrationDueOn()).isEqualTo(TODAY.minusDays(5));
        assertThat(check.check("AUD-2", TODAY.minusDays(10)).verdict()).isEqualTo(DeviceCalibrationCheck.Verdict.VALID);
        assertThat(check.check("NEW-1", TODAY).verdict()).isEqualTo(DeviceCalibrationCheck.Verdict.UNKNOWN);
        assertThat(check.check(null, TODAY).verdict()).isEqualTo(DeviceCalibrationCheck.Verdict.UNKNOWN);
    }
}
