package com.wellnessz.e14;

import com.wellnessz.common.events.DomainEvent;

/** Events emitted by E14 (contract §6) – identifiers and status only, in-process. */
public final class E14Events {

    private E14Events() {
    }

    public record AppointmentStatusChanged(String appointmentId, String employeeId, String status) implements DomainEvent {
        @Override
        public String eventType() {
            return "appointment.status.v1";
        }

        @Override
        public String aggregateType() {
            return "APPOINTMENT";
        }

        @Override
        public String aggregateId() {
            return appointmentId;
        }
    }

    public record EquipmentCalibration(String equipmentId, String calibrationState) implements DomainEvent {
        @Override
        public String eventType() {
            return "equipment.calibration.v1";
        }

        @Override
        public String aggregateType() {
            return "EQUIPMENT";
        }

        @Override
        public String aggregateId() {
            return equipmentId;
        }
    }

    public record ColdChainExcursion(String equipmentId, String excursionId) implements DomainEvent {
        @Override
        public String eventType() {
            return "coldchain.excursion.v1";
        }

        @Override
        public String aggregateType() {
            return "EQUIPMENT";
        }

        @Override
        public String aggregateId() {
            return equipmentId;
        }
    }
}
