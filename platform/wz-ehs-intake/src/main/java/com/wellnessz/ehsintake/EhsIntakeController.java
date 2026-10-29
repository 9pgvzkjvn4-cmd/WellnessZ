package com.wellnessz.ehsintake;

import java.io.IOException;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.ehsintake.mailbox.MailboxIngestionJob;
import com.wellnessz.ehsintake.model.EhsIncident;
import com.wellnessz.ehsintake.model.EhsIncidentRepository;
import com.wellnessz.ehsintake.model.ImportBatch;
import com.wellnessz.ehsintake.model.IntakeChannel;
import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1/intake/ehs")
public class EhsIntakeController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final IncidentIntakeService intake;
    private final MailboxIngestionJob mailbox;
    private final EhsIncidentRepository incidents;
    private final CurrentUserProvider users;

    public EhsIntakeController(IncidentIntakeService intake, MailboxIngestionJob mailbox, EhsIncidentRepository incidents,
                               CurrentUserProvider users) {
        this.intake = intake;
        this.mailbox = mailbox;
        this.incidents = incidents;
        this.users = users;
    }

    public record IncidentView(String id, String externalRef, java.time.Instant occurredAt, String location, String orgUnitCode,
                               String incidentType, String severity, boolean injury, IntakeChannel channel, String status,
                               java.time.Instant receivedAt) {
        static IncidentView of(EhsIncident i) {
            return new IncidentView(i.getId().toString(), i.getExternalRef(), i.getOccurredAt(), i.getLocation(),
                    i.getOrgUnitCode(), i.getIncidentType(), i.getSeverity(), i.isInjury(), i.getChannel(),
                    i.getStatus().name(), i.getReceivedAt());
        }
    }

    public record ImportBatchView(String id, IntakeChannel channel, String sourceName, String contentSha256, int rowsTotal,
                                  int rowsAccepted, int rowsDuplicate, int rowsRejected, String errors, java.time.Instant createdAt) {
        static ImportBatchView of(ImportBatch b) {
            return new ImportBatchView(b.getId().toString(), b.getChannel(), b.getSourceName(), b.getContentSha256(),
                    b.getRowsTotal(), b.getRowsAccepted(), b.getRowsDuplicate(), b.getRowsRejected(), b.getErrors(),
                    b.getCreatedAt());
        }
    }

    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('OH_ADMIN','CASE_MANAGER','INTEGRATION')")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportBatchView uploadExcel(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw ApiException.badRequest("empty_file", "File is empty");
        }
        String original = file.getOriginalFilename();
        String name = original == null ? "" : original;
        String contentType = file.getContentType();
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")
                || (contentType != null && !XLSX.equals(contentType) && !"application/octet-stream".equals(contentType))) {
            throw ApiException.badRequest("unsupported_media", "Only .xlsx workbooks are accepted");
        }
        return ImportBatchView.of(intake.importExcel(file.getBytes(), name, IntakeChannel.EXCEL_UPLOAD, users.actorOrSystem()));
    }

    @PostMapping("/mailbox/poll")
    @PreAuthorize("hasAnyRole('OH_ADMIN','PLATFORM_ADMIN')")
    public MailboxIngestionJob.PollResult pollMailbox() {
        return mailbox.poll();
    }

    /** Description (potentially clinical free text) is intentionally not exposed in the list view. */
    @GetMapping("/incidents")
    @PreAuthorize("hasAnyRole('OH_ADMIN','CASE_MANAGER','OH_CLINICIAN')")
    public List<IncidentView> list(@RequestParam(defaultValue = "100") int limit) {
        return incidents.findAllByOrderByReceivedAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 1000)))
                .stream().map(IncidentView::of).toList();
    }
}
