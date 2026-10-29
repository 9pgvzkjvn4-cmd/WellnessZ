package com.wellnessz.e08.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Transmission of a signed first report to the insurer / TPA or the reporting authority (E08-F02). The default
 * {@link DefaultClaimsSubmissionGateway} writes a reference-only outbox event to {@code insurer-gateway} /
 * {@code authority-gateway} and a content-free e-mail; a real connector (authority API, insurer FNOL API) replaces it by
 * declaring a {@code @Primary} bean. The document itself travels only through a consent-checked share (insurers) or the
 * connector's secure channel (authorities) – never inside the event.
 */
public interface ClaimsSubmissionGateway {

    record Submission(UUID reportId, String reportRef, String claimRef, String formCode, String jurisdiction, String recipientType,
                      String recipientCode, String recipientName, String payerCode, String employeeId, LocalDate incidentDate,
                      Instant submittedAt, Instant deadlineAt, String channel, UUID documentId, String documentNumber) {
    }

    /** @param messageId transmission id (outbox / connector message id) */
    record Receipt(String messageId) {
    }

    Receipt submit(Submission submission);
}
