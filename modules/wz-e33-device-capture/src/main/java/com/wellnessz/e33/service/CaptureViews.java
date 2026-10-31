package com.wellnessz.e33.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.e33.domain.Comparison;
import com.wellnessz.e33.domain.FallbackChain;

/** Response records of the capture API (contract 14.1 / 14.2). Restricted readings carry no values for other OH users. */
public final class CaptureViews {

    private CaptureViews() {
    }

    /**
     * Session record of the contract plus what the dialogue needs: allowed actions, the fall-back chain on offer, the preview.
     *
     * @param restrictedReading {@code true} for a restricted reading the caller may not open (values are absent)
     */
    public record SessionView(UUID sessionId, String state, String mode, String employeeId, String employeeName, UUID caseId, String orderRef,
                              String formCode, String formVersion, String fieldGroup, String category, String deviceInstanceId, String deviceName,
                              String path, String operator, String siteCode, String stationCode, Instant openedAt, Instant expiresAt,
                              UUID observationId, String observationStatus, String routedRecordType, String routedRecordId,
                              String fallbackReason, String rejectReason, int retestCount, String interpretationStatus, String signedBy,
                              Instant signedAt, String contextPush, boolean restricted, boolean restrictedReading, UUID batchId,
                              Integer queuePosition, List<String> actions, List<FallbackChain.Option> fallbackOptions, List<FlagView> flags,
                              Preview preview, List<AnnotationView> annotations) {
    }

    /**
     * @param marking the provenance marking shown next to the filled fields ("device-sourced · model · time", "photo verified",
     *                "entered manually – attested")
     */
    public record Preview(UUID observationId, String status, String captureMethod, Instant effectiveAt, List<ValueView> values,
                          List<FlagView> flags, boolean acceptEnabled, boolean verifyBeforePost, List<AttachmentView> attachments,
                          Map<String, Object> fields, ProvenanceView provenance, String marking, String interpretationStatus,
                          List<SequenceItem> sequence, Comparison.Summary comparison) {
    }

    public record ValueView(UUID observationId, String key, String display, BigDecimal value, String text, String unit, String bodySite,
                            String method, String interpretation, String loinc, String mdc) {

        static ValueView of(UUID observationId, DeviceObservation.Component c) {
            return new ValueView(observationId, c.key(), c.display(), c.value(), c.text(), c.ucum(), c.bodySite(), c.method(),
                    c.interpretation(), c.loincCode(), c.mdcCode());
        }
    }

    public record FlagView(String gate, String outcome, String code, String message) {

        static FlagView of(DeviceObservation.QualityFlag f) {
            return new FlagView(f.gate(), f.outcome().name(), f.code(), f.message());
        }
    }

    /** @param link engine endpoint that streams the attachment (authorised there) */
    public record AttachmentView(UUID archiveId, String kind, String mediaType, String sha256, long sizeBytes, String link) {

        static AttachmentView of(UUID observationId, DeviceObservation.Attachment a) {
            return new AttachmentView(a.archiveId(), a.kind(), a.mediaType(), a.sha256(), a.sizeBytes(),
                    "/device-engine/observations/" + observationId + "/attachments/" + a.archiveId());
        }
    }

    public record ProvenanceView(String captureMethod, String model, String manufacturer, String serial, String udi, String firmware,
                                 String driver, String calibrationId, LocalDate calibrationDate, String calibrationVerdict, String operator,
                                 String verifiedBy, Instant verifiedAt, String associationMethod, BigDecimal associationConfidence,
                                 long clockSkewMillis, String rawPayloadSha256) {

        static ProvenanceView of(DeviceObservation o) {
            DeviceObservation.Provenance p = o.provenance();
            String method = o.captureMethod() == null ? null : o.captureMethod().name();
            if (p == null) {
                return new ProvenanceView(method, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0, null);
            }
            String driver = p.driverPackageId() == null ? null : p.driverPackageId() + "@" + p.driverVersion();
            return new ProvenanceView(method, p.model(), p.manufacturer(), p.serial(), p.udi(), p.firmware(), driver, p.calibrationId(),
                    p.calibrationDate(), p.calibrationVerdict(), p.operator(), p.verifiedBy(), p.verifiedAt(), p.associationMethod(),
                    p.associationConfidence(), p.clockSkewMillis(), p.rawPayloadSha256());
        }
    }

    public record SequenceItem(UUID observationId, String category, String status, String decision, int flags) {
    }

    public record AnnotationView(UUID id, String author, Instant createdAt, String text) {
    }

    /** {@code device-sourced · EasyOne Air · 31 Oct 2026 09:14 UTC} – or the fall-back marking. */
    public static String marking(String captureMethod, String model, Instant at) {
        if ("OCR_VERIFIED".equals(captureMethod)) {
            return "photo verified";
        }
        if ("MANUAL_ATTESTED".equals(captureMethod)) {
            return "entered manually – attested";
        }
        String time = at == null ? "" : DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'", java.util.Locale.UK).withZone(ZoneOffset.UTC).format(at);
        return "device-sourced · " + (model == null ? "device" : model) + (time.isEmpty() ? "" : " · " + time);
    }
}
