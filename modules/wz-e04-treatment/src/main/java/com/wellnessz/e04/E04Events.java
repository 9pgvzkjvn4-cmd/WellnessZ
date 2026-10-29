package com.wellnessz.e04;

import com.wellnessz.common.events.DomainEvent;

/** Events emitted by E04 (contract §5) – identifiers and status only. */
public final class E04Events {

    private E04Events() {
    }

    public record ReferralStatus(String referralId, String caseId, String status) implements DomainEvent {
        @Override
        public String eventType() {
            return "referral.status.v1";
        }

        @Override
        public String aggregateType() {
            return "REFERRAL";
        }

        @Override
        public String aggregateId() {
            return referralId;
        }
    }

    public record PreauthStatus(String preauthId, String caseId, String status) implements DomainEvent {
        @Override
        public String eventType() {
            return "preauth.status.v1";
        }

        @Override
        public String aggregateType() {
            return "PREAUTH";
        }

        @Override
        public String aggregateId() {
            return preauthId;
        }
    }

    public record TreatmentVariance(String planId, String caseId) implements DomainEvent {
        @Override
        public String eventType() {
            return "treatment.variance.v1";
        }

        @Override
        public String aggregateType() {
            return "TREATMENT_PLAN";
        }

        @Override
        public String aggregateId() {
            return planId;
        }
    }
}
