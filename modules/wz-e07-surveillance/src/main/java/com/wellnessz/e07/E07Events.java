package com.wellnessz.e07;

import java.time.LocalDate;

import com.wellnessz.common.events.DomainEvent;

/** Events emitted by E07 (contract §6) – identifiers and status only. */
public final class E07Events {

    public static final String HR_DESTINATION = "hr-systems";

    private E07Events() {
    }

    /** In-process: an enrolment entered its due window. */
    public record EnrolmentDue(String enrolmentId, String employeeId, String programmeCode, LocalDate nextDueOn) implements DomainEvent {
        @Override
        public String eventType() {
            return "surveillance.enrolment.due.v1";
        }

        @Override
        public String aggregateType() {
            return "ENROLMENT";
        }

        @Override
        public String aggregateId() {
            return enrolmentId;
        }
    }

    /** In-process: a follow-up was raised from an abnormal surveillance result. */
    public record FollowUpCreated(String followUpId, String employeeId, String caseId) implements DomainEvent {
        @Override
        public String eventType() {
            return "surveillance.followup.created.v1";
        }

        @Override
        public String aggregateType() {
            return "FOLLOW_UP";
        }

        @Override
        public String aggregateId() {
            return followUpId;
        }
    }

    /** HR onboarding feed ({@code hr-systems}): clearance status only, never the questionnaire or examination. */
    public record PreEmploymentStatus(String messageId, String offerRef, String candidateId, String preEmploymentStatus,
                                      LocalDate effectiveFrom) implements DomainEvent {
        @Override
        public String eventType() {
            return "preemployment.status.v1";
        }

        @Override
        public String aggregateType() {
            return "PRE_EMPLOYMENT";
        }

        @Override
        public String aggregateId() {
            return offerRef;
        }
    }

    /** In-process: crew medical certificate status (VALID / EXPIRING / EXPIRED / SUSPENDED). */
    public record CrewCertificateStatus(String employeeId, String certificateType, LocalDate validUntil, String status)
            implements DomainEvent {
        @Override
        public String eventType() {
            return "crew.certificate.status.v1";
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
