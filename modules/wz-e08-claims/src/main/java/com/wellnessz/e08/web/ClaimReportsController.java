package com.wellnessz.e08.web;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e08.report.ReportService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E08-F02 statutory & insurer e-reporting: forms, first reports, submission, acknowledgements and the deadline dashboard. */
@RestController("e08ClaimReportsController")
@RequestMapping("/api/v1")
public class ClaimReportsController {

    private final ReportService reports;
    private final CurrentUserProvider users;

    public ClaimReportsController(ReportService reports, CurrentUserProvider users) {
        this.reports = reports;
        this.users = users;
    }

    @GetMapping("/claims/report-forms")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','HSE_OFFICER')")
    public List<ReportService.FormView> forms() {
        return reports.forms();
    }

    public record CreateBody(@NotBlank @Pattern(regexp = Patterns.CODE) String formCode, Boolean specifiedInjury) {
    }

    @PostMapping("/claims/{id}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','HSE_OFFICER')")
    public ReportService.ReportView create(@PathVariable UUID id, @Valid @RequestBody CreateBody b) {
        return reports.create(users.require(), id, b.formCode(), b.specifiedInjury());
    }

    @GetMapping("/claims/{id}/reports")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','HSE_OFFICER')")
    public List<ReportService.ReportView> forClaim(@PathVariable UUID id) {
        return reports.forClaim(users.require(), id);
    }

    @GetMapping("/claims/reports")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','HSE_OFFICER')")
    public List<ReportService.DashboardRow> dashboard(@RequestParam(required = false) @Pattern(regexp = Patterns.REPORT_STATUS) String status,
                                                      @RequestParam(required = false) @Min(0) @Max(8760) Integer dueWithinHours) {
        return reports.dashboard(status, dueWithinHours);
    }

    @GetMapping("/claims/reports/kpis")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','HSE_OFFICER')")
    public ReportService.Kpi kpis() {
        return reports.kpi();
    }

    @GetMapping("/claims/reports/{reportId}")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','HSE_OFFICER')")
    public ReportService.ReportView get(@PathVariable UUID reportId) {
        return reports.get(users.require(), reportId);
    }

    public record UpdateBody(@Size(max = 40) Map<@Pattern(regexp = "[A-Za-z]{1,40}") String, @Size(max = 4000) String> fields, Boolean specifiedInjury) {
    }

    @PutMapping("/claims/reports/{reportId}")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','HSE_OFFICER')")
    public ReportService.ReportView update(@PathVariable UUID reportId, @Valid @RequestBody UpdateBody b) {
        return reports.update(users.require(), reportId, b.fields(), b.specifiedInjury());
    }

    public record SubmitBody(@NotBlank @Pattern(regexp = Patterns.CHANNEL) String channel, UUID consentId) {
    }

    @PostMapping("/claims/reports/{reportId}/submit")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','HSE_OFFICER')")
    public ReportService.ReportView submit(@PathVariable UUID reportId, @Valid @RequestBody SubmitBody b) {
        return reports.submit(users.require(), reportId, b.channel(), b.consentId());
    }

    @PostMapping(value = "/claims/reports/{reportId}/acknowledgement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CLAIMS_COORDINATOR')")
    public ReportService.ReportView acknowledge(@PathVariable UUID reportId, @RequestPart(value = "file", required = false) MultipartFile file,
                                                @RequestParam @Pattern(regexp = Patterns.ACK_REF) String ackRef,
                                                @RequestParam(required = false) Instant receivedAt) {
        return reports.acknowledge(users.require(), reportId, ackRef, receivedAt, bytes(file));
    }

    public record AckJsonBody(@NotBlank @Pattern(regexp = Patterns.ACK_REF) String ackRef, Instant receivedAt) {
    }

    /** JSON variant of the manual acknowledgement (no file). */
    @PostMapping(value = "/claims/reports/{reportId}/acknowledgement", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLAIMS_COORDINATOR')")
    public ReportService.ReportView acknowledgeJson(@PathVariable UUID reportId, @Valid @RequestBody AckJsonBody b) {
        return reports.acknowledge(users.require(), reportId, b.ackRef(), b.receivedAt(), new byte[0]);
    }

    public record IntegrationAckBody(@NotBlank @Pattern(regexp = Patterns.ACK_REF) String ackRef, Instant receivedAt,
                                     @NotBlank @Pattern(regexp = "ACKNOWLEDGED|REJECTED") String status, @Size(max = 1000) String reason) {
    }

    @PostMapping("/integration/claims/reports/{ref}/acknowledgement")
    @PreAuthorize("hasRole('INTEGRATION')")
    public ReportService.ReportView integrationAck(@PathVariable @Pattern(regexp = Patterns.REF) String ref, @Valid @RequestBody IntegrationAckBody b) {
        return reports.integrationAck(users.require(), ref, b.ackRef(), b.receivedAt(), b.status(), b.reason());
    }

    static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new byte[0];
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("invalid_file", "The file could not be read");
        }
    }
}
