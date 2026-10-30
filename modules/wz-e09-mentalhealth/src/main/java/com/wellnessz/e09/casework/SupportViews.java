package com.wellnessz.e09.casework;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response shapes of the confidential support pathway (contract §3.1 / §3.2). Null fields are omitted. */
public final class SupportViews {

    private SupportViews() {
    }

    public record EapSummary(UUID id, String ref, String partnerCode, String partnerName, String status, Instant sentAt,
                             LocalDate firstAppointmentDueBy, int sessionsAuthorised, String urgency, String closureReason) {
    }

    public record ReliefSummary(UUID id, LocalDate from, LocalDate expectedUntil, String status, String decision) {
    }

    public record SupportCaseView(UUID id, String ref, String employeeId, String displayName, String status, String risk, String concern,
                                  String source, Instant openedAt, Instant lastContactAt, String sharing, EapSummary eapReferral,
                                  ReliefSummary relief, Boolean urgentOpen) {
    }

    public record NoteView(UUID id, String text, String author, Instant createdAt) {
    }

    public record ScreenView(UUID id, String instrument, int score, String band, boolean urgent, String administeredBy, Instant createdAt) {
    }

    public record ContactView(UUID id, String type, String outcome, String note, String actor, Instant occurredAt) {
    }

    public record TimelineEntry(String type, String summary, String actor, Instant at) {
    }

    public record UrgentView(UUID id, UUID caseId, String caseRef, String employeeId, String displayName, String risk, String trigger,
                             Instant callBackBy, boolean overdue, String status, String outcome, Instant createdAt) {
    }

    public record SupportCaseDetail(UUID id, String ref, String kernelCaseRef, String employeeId, String displayName, String status, String risk,
                                    String concern, String source, String sourceType, String sourceRef, Instant openedAt, String openedBy,
                                    Instant lastContactAt, String sharing, String sharingAdjustments, Instant sharingChangedAt,
                                    String preferredContact, String contactPhone, String bestTime, String message, Instant nextContactBy,
                                    Instant closedAt, String closeReason, EapSummary eapReferral, List<EapSummary> eapReferrals,
                                    ReliefSummary relief, List<NoteView> notes, List<ScreenView> screens, List<ContactView> contacts,
                                    List<UrgentView> urgent, List<TimelineEntry> timeline, String accessBasis) {
    }

    public record SelfReferralResult(String caseRef, Instant nextContactBy) {
    }

    public record Placeholder(long restrictedCases) {
    }

    public record SharingView(UUID caseId, String caseRef, String choice, String adjustments, Instant changedAt) {
    }

    public record SharedOutcome(String employeeId, String displayName, String fitness, String adjustments, Instant sharedAt) {
    }

    public record ScreenDefinition(String instrument, String title, List<Question> questions, List<Option> options) {
    }

    public record Question(String code, String text) {
    }

    public record Option(int value, String label) {
    }

    public record ScreenOutcome(UUID id, String instrument, int score, String band, boolean urgent, List<String> nextSteps, String caseRef) {
    }

    public record Signpost(String code, String title, String summary, String url, String phone, boolean available24h) {
    }

    public record Emergency(String text, String phone) {
    }

    public record Signposting(List<Signpost> items, Emergency emergency) {
    }

    public record Protocol(String code, String title, String version, String status, List<String> steps, String signedOffBy, Instant signedOffAt) {
    }

    public record InstrumentInfo(String code, String title, String version, String licence, String licenceStatus, int items, int maxScore) {
    }

    public record Governance(List<InstrumentInfo> instruments, Protocol protocol, String signOff, String signOffNote, int namedClinicians,
                             long signpostingItems) {
    }
}
