package com.wellnessz.e06.model;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** E06 enumerations (API contract §3). Stored as their names; request records validate them with matching patterns. */
public final class AbsenceEnums {

    private AbsenceEnums() {
    }

    public enum AbsenceSource { HRIS, TNA, CREW, MANUAL }

    public enum AbsenceType { SICKNESS, INJURY_WORK, INJURY_NON_WORK, MATERNITY_RELATED, OTHER_AUTHORISED }

    /** OH only; analytics at category level. */
    public enum ReasonCategory { MSK, MENTAL_HEALTH, RESPIRATORY, GASTRO, INJURY, SURGERY, INFECTION, OTHER, UNDISCLOSED }

    public enum RuleType { DURATION, FREQUENCY, PATTERN, POST_INCIDENT, SAFETY_CRITICAL, CREW_SICKNESS }

    public enum RuleAction { EARLY_INTERVENTION, LONG_TERM, AME_REVIEW }

    public enum QueueStatus { NEW, ACCEPTED, CONTACTED, CLOSED }

    public enum CertificateStatus { RECEIVED, VALID, INVALID, SUSPICIOUS, VERIFIED_GENUINE, VERIFIED_FRAUDULENT }

    /** What HR, managers and the employee see of a certificate. */
    public enum CertificateValidity { PENDING, VALID, INVALID }

    public enum MilestoneType { CONTACT, ADVICE_REPORT, CASE_CONFERENCE, HR_REVIEW, RTW_REVIEW, IHR_CONSIDERATION }

    public enum MilestoneStatus { PENDING, DONE, OVERDUE, SKIPPED }

    public enum HrStage { NONE, INFORMAL_REVIEW, FORMAL_REVIEW_1, FORMAL_REVIEW_2, CAPABILITY_HEARING, CLOSED }

    public enum AdviceStatus { SUBMITTED, ACCEPTED, REPORT_DRAFTED, AWAITING_EMPLOYEE, RELEASED, WITHHELD, WITHDRAWN }

    public enum AdviceFitness { FIT, FIT_WITH_ADJUSTMENTS, TEMPORARILY_UNFIT, UNFIT_LONG_TERM }

    public enum Prognosis { RTW_WITHIN_2_WEEKS, RTW_WITHIN_4_WEEKS, RTW_WITHIN_12_WEEKS, UNCERTAIN, RTW_UNLIKELY }

    /** Absence types the trigger rules and the Bradford factor count (maternity-related and authorised leave never trigger). */
    public static final Set<String> TRIGGERABLE_TYPES = Set.of(AbsenceType.SICKNESS.name(), AbsenceType.INJURY_WORK.name(),
            AbsenceType.INJURY_NON_WORK.name());

    public static <E extends Enum<E>> Set<String> names(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }

    /** Maps the internal certificate status onto the validity HR / managers / the employee see. */
    public static CertificateValidity validity(String status) {
        if (status == null) {
            return CertificateValidity.PENDING;
        }
        return switch (CertificateStatus.valueOf(status)) {
            case VALID, VERIFIED_GENUINE -> CertificateValidity.VALID;
            case INVALID, VERIFIED_FRAUDULENT -> CertificateValidity.INVALID;
            case RECEIVED, SUSPICIOUS -> CertificateValidity.PENDING;
        };
    }
}
