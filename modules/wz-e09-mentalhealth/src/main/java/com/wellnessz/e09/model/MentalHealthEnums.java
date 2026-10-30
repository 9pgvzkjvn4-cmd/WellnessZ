package com.wellnessz.e09.model;

/** E09 vocabularies (contract §3). Stored as their names. */
public final class MentalHealthEnums {

    public enum SupportStatus { OPEN, SCREENED, ACTIVE, REFERRED, RELIEF, CLOSED }

    public enum Risk {
        LOW, MODERATE, HIGH, URGENT;

        public Risk max(Risk other) {
            return other != null && other.ordinal() > ordinal() ? other : this;
        }
    }

    public enum Sharing { NOTHING, FITNESS_ONLY, FITNESS_AND_ADJUSTMENTS }

    public enum Instrument { PHQ9, GAD7 }

    public enum Band { MINIMAL, MILD, MODERATE, MODERATELY_SEVERE, SEVERE }

    public enum Concern { WORK_STRESS, LOW_MOOD, ANXIETY, SLEEP, BEREAVEMENT, TRAUMA, SUBSTANCE, RELATIONSHIP, OTHER }

    public enum Source { SELF, CLINICIAN, PEER, CISM, PROGRAMME_REFERRAL }

    public enum PreferredContact { PHONE, APP_CHAT, VIDEO }

    public enum UrgentOutcome { REACHED_SAFE, REACHED_ESCALATED_EMERGENCY, NOT_REACHED_RETRY, NOT_REACHED_WELFARE_CHECK }

    public enum EapStatus { SENT, ACCEPTED, IN_PROGRESS, CLOSED }

    public enum ClosureReason { COMPLETED, DISENGAGED, REFERRED_ON }

    public enum PeerCategory { STRESS, FATIGUE, RELATIONSHIP, BEREAVEMENT, SUBSTANCE, TRAUMA, OTHER }

    public enum PeerOutcome { SUPPORTED, SIGNPOSTED, REFERRED_TO_TRUSTED_PROFESSIONAL, EMERGENCY }

    public enum CismCategory { FATALITY, ASSAULT, FUME_EVENT, AIRCRAFT_ACCIDENT, SERIOUS_INJURY, EMERGENCY_LANDING, SERIOUS_INCIDENT, OTHER }

    public enum Factor { WORKLOAD, SHIFT_FATIGUE, VIOLENCE_AGGRESSION, ISOLATION, CHANGE, ROLE_CLARITY, SUPPORT, RECOGNITION }

    public enum Rag { GREEN, AMBER, RED }

    public enum FitnessDecision { FIT, FIT_WITH_LIMITATIONS, TEMPORARILY_UNFIT, REFER_TO_AUTHORITY }

    private MentalHealthEnums() {
    }
}
