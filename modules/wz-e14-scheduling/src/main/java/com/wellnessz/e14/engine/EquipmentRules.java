package com.wellnessz.e14.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.wellnessz.casekernel.support.DeviceCalibrationCheck;

/** Pure equipment rules (E14-F04): derived calibration state, device verdicts, next due dates and cold-chain excursions. */
public final class EquipmentRules {

    public static final int DUE_SOON_DAYS = 30;
    public static final BigDecimal DEFAULT_FRIDGE_MIN = new BigDecimal("2.0");
    public static final BigDecimal DEFAULT_FRIDGE_MAX = new BigDecimal("8.0");

    private EquipmentRules() {
    }

    public enum CalibrationState { VALID, DUE_SOON, OVERDUE }

    /** Cold-chain action for a reading given whether an excursion is currently in progress. */
    public enum ExcursionAction { NONE, OPEN, EXTEND, END }

    /** {@code calibrationDueOn < today} → OVERDUE; within {@code dueSoonDays} → DUE_SOON; no due date → VALID. */
    public static CalibrationState state(LocalDate dueOn, LocalDate today, int dueSoonDays) {
        if (dueOn == null) {
            return CalibrationState.VALID;
        }
        if (dueOn.isBefore(today)) {
            return CalibrationState.OVERDUE;
        }
        return dueOn.isAfter(today.plusDays(dueSoonDays)) ? CalibrationState.VALID : CalibrationState.DUE_SOON;
    }

    /** DeviceCalibrationCheck verdict: none → UNKNOWN; OUT_OF_SERVICE / RETIRED → OUT_OF_SERVICE; due before the date → OUT_OF_CALIBRATION. */
    public static DeviceCalibrationCheck.Verdict verdict(boolean registered, String status, LocalDate dueOn, LocalDate on) {
        if (!registered) {
            return DeviceCalibrationCheck.Verdict.UNKNOWN;
        }
        if (!"IN_SERVICE".equals(status)) {
            return DeviceCalibrationCheck.Verdict.OUT_OF_SERVICE;
        }
        if (dueOn != null && dueOn.isBefore(on)) {
            return DeviceCalibrationCheck.Verdict.OUT_OF_CALIBRATION;
        }
        return DeviceCalibrationCheck.Verdict.VALID;
    }

    /** Next calibration due date: explicit value, else performed + interval (default 12 months). */
    public static LocalDate nextDue(LocalDate performedOn, LocalDate explicitNext, Integer intervalMonths) {
        if (explicitNext != null) {
            return explicitNext;
        }
        return performedOn.plusMonths(intervalMonths == null || intervalMonths <= 0 ? 12 : intervalMonths);
    }

    /** Calibration-due alert: once per due date, when the due date is within {@code days} (or already passed). */
    public static boolean dueAlert(LocalDate dueOn, LocalDate today, int days, LocalDate alreadyAlertedFor) {
        return dueOn != null && !dueOn.isAfter(today.plusDays(days)) && !dueOn.equals(alreadyAlertedFor);
    }

    public static boolean outOfRange(BigDecimal temperature, BigDecimal min, BigDecimal max) {
        BigDecimal lo = min == null ? DEFAULT_FRIDGE_MIN : min;
        BigDecimal hi = max == null ? DEFAULT_FRIDGE_MAX : max;
        return temperature.compareTo(lo) < 0 || temperature.compareTo(hi) > 0;
    }

    public static ExcursionAction evaluate(boolean excursionInProgress, BigDecimal temperature, BigDecimal min, BigDecimal max) {
        boolean out = outOfRange(temperature, min, max);
        if (out) {
            return excursionInProgress ? ExcursionAction.EXTEND : ExcursionAction.OPEN;
        }
        return excursionInProgress ? ExcursionAction.END : ExcursionAction.NONE;
    }

    /** Excursion level for the content-free notification: HIGH (too warm) or LOW (too cold). */
    public static String level(BigDecimal temperature, BigDecimal max) {
        return temperature.compareTo(max == null ? DEFAULT_FRIDGE_MAX : max) > 0 ? "HIGH" : "LOW";
    }
}
