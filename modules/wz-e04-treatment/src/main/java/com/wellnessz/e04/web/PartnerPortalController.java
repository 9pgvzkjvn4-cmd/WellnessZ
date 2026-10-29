package com.wellnessz.e04.web;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e04.preauth.PreauthService;
import com.wellnessz.e04.referral.PartnerReferralService;
import com.wellnessz.e04.support.Money;
import com.wellnessz.e04.telehealth.TelehealthService;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * Partner portal API: providers (PARTNER_PROVIDER) see referrals and telehealth sessions of their own organisation only;
 * TPA / insurer adjusters (TPA_ADJUSTER) see and decide pre-authorisations where their organisation is the payer.
 */
@RestController
@RequestMapping("/api/v1/partner")
public class PartnerPortalController {

    static final long MAX_BYTES = 10L * 1024 * 1024;

    private final PartnerReferralService referrals;
    private final TelehealthService telehealth;
    private final PreauthService preauth;
    private final CurrentUserProvider users;

    public PartnerPortalController(PartnerReferralService referrals, TelehealthService telehealth, PreauthService preauth, CurrentUserProvider users) {
        this.referrals = referrals;
        this.telehealth = telehealth;
        this.preauth = preauth;
        this.users = users;
    }

    static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("file_too_large", "Files must be between 1 byte and 10 MB");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("upload_failed", "Upload could not be read");
        }
    }

    // ------------------------------------------------------------------ referrals

    @GetMapping("/referrals")
    @PreAuthorize(Spel.PARTNER)
    public List<PartnerReferralService.PartnerReferral> referrals(
            @RequestParam(required = false) @Pattern(regexp = "SENT|ACCEPTED|DECLINED|SCHEDULED|SEEN|REPORT_RECEIVED|CLOSED|CANCELLED") String status) {
        return referrals.list(users.require(), status);
    }

    @GetMapping("/referrals/{id}")
    @PreAuthorize(Spel.PARTNER)
    public PartnerReferralService.PartnerReferral referral(@PathVariable UUID id) {
        return referrals.get(users.require(), id);
    }

    @PostMapping("/referrals/{id}/accept")
    @PreAuthorize(Spel.PARTNER)
    public PartnerReferralService.PartnerReferral accept(@PathVariable UUID id) {
        return referrals.accept(users.require(), id);
    }

    public record DeclineRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @PostMapping("/referrals/{id}/decline")
    @PreAuthorize(Spel.PARTNER)
    public PartnerReferralService.PartnerReferral decline(@PathVariable UUID id, @Valid @RequestBody DeclineRequest r) {
        return referrals.decline(users.require(), id, r.reason());
    }

    public record AppointmentRequest(@NotNull Instant appointmentAt, @Size(max = 300) String location, @Size(max = 1000) String instructions) {
    }

    @PostMapping("/referrals/{id}/appointment")
    @PreAuthorize(Spel.PARTNER)
    public PartnerReferralService.PartnerReferral appointment(@PathVariable UUID id, @Valid @RequestBody AppointmentRequest r) {
        return referrals.appointment(users.require(), id, r.appointmentAt(), r.location(), r.instructions());
    }

    @PostMapping("/referrals/{id}/seen")
    @PreAuthorize(Spel.PARTNER)
    public PartnerReferralService.PartnerReferral seen(@PathVariable UUID id) {
        return referrals.seen(users.require(), id);
    }

    @PostMapping(value = "/referrals/{id}/report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Spel.PARTNER)
    public PartnerReferralService.PartnerReferral report(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                                         @RequestParam(required = false) @Size(max = 4000) String summary) {
        return referrals.report(users.require(), id, bytes(file), file.getOriginalFilename(), summary);
    }

    @PostMapping(value = "/referrals/{id}/invoice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.PARTNER)
    public PartnerReferralService.InvoiceReceipt invoice(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                                         @RequestParam @Pattern(regexp = "[A-Za-z0-9_/\\-]{1,60}") String invoiceNumber,
                                                         @RequestParam @Pattern(regexp = "\\d{1,10}(\\.\\d{1,2})?") String amount,
                                                         @RequestParam @Pattern(regexp = "[A-Z]{3}") String currency,
                                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate) {
        return referrals.invoice(users.require(), id, bytes(file), invoiceNumber, new Money(amount, currency), serviceDate);
    }

    // ------------------------------------------------------------------ telehealth

    @GetMapping("/telehealth/sessions")
    @PreAuthorize(Spel.PARTNER)
    public List<TelehealthService.SessionView> sessions() {
        return telehealth.partnerList(users.require());
    }

    @PostMapping("/telehealth/sessions/{id}/join")
    @PreAuthorize(Spel.PARTNER)
    public TelehealthService.Join join(@PathVariable UUID id) {
        return telehealth.join(users.require(), id, TelehealthService.Participant.PARTNER);
    }

    @GetMapping("/telehealth/sessions/{id}/messages")
    @PreAuthorize(Spel.PARTNER)
    public List<TelehealthService.MessageView> messages(@PathVariable UUID id) {
        return telehealth.messages(users.require(), id, TelehealthService.Participant.PARTNER);
    }

    @PostMapping("/telehealth/sessions/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.PARTNER)
    public TelehealthService.MessageView post(@PathVariable UUID id, @Valid @RequestBody OutcomeTelehealthController.MessageRequest r) {
        return telehealth.post(users.require(), id, TelehealthService.Participant.PARTNER, r.text());
    }

    // ------------------------------------------------------------------ pre-authorisations (TPA / insurer)

    @GetMapping("/preauthorisations")
    @PreAuthorize(Spel.ADJUSTER)
    public List<PreauthService.PreauthView> preauthorisations(
            @RequestParam(required = false) @Pattern(regexp = "SUBMITTED|APPROVED|PARTIALLY_APPROVED|DECLINED|EXPIRED") String status) {
        return preauth.partnerList(users.require(), status);
    }

    @PostMapping("/preauthorisations/{id}/decision")
    @PreAuthorize(Spel.ADJUSTER)
    public PreauthService.PreauthView decide(@PathVariable UUID id, @Valid @RequestBody PreauthService.DecisionCommand cmd) {
        return preauth.decideAsAdjuster(users.require(), id, cmd);
    }
}
