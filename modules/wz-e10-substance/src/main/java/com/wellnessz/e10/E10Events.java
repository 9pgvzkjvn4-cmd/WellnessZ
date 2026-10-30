package com.wellnessz.e10;

import com.wellnessz.common.events.DomainEvent;

/**
 * In-process events emitted by E10 – identifiers and duty status only, never the test reason or outcome. The crew roster and
 * HR feeds are published by the case kernel ({@code CaseService.publishFitness}) as status-only fitness messages.
 */
public final class E10Events {

    private E10Events() {
    }

    /** The duty status of an employee in the testing programme changed (AVAILABLE | REMOVED | RETURN_PENDING). */
    public record DutyStatusChanged(String employeeId, String dutyStatus, String since) implements DomainEvent {
        @Override
        public String eventType() {
            return "substance.duty-status.v1";
        }

        @Override
        public String aggregateType() {
            return "EMPLOYEE";
        }

        @Override
        public String aggregateId() {
            return employeeId;
        }
    }
}
