package com.wellnessz.e04.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e04.referral.EReferralService;
import com.wellnessz.e04.referral.ReferralCore;
import com.wellnessz.e04.referral.TreatmentKpis;
import com.wellnessz.e04.telehealth.TelehealthService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E04-F02 e-referral management (OH side), KPIs and the FHIR / telehealth integration endpoints. */
@RestController
@RequestMapping("/api/v1")
public class EReferralController {

    private final EReferralService referrals;
    private final TreatmentKpis kpis;
    private final TelehealthService telehealth;
    private final CurrentUserProvider users;

    public EReferralController(EReferralService referrals, TreatmentKpis kpis, TelehealthService telehealth, CurrentUserProvider users) {
        this.referrals = referrals;
        this.kpis = kpis;
        this.telehealth = telehealth;
        this.users = users;
    }

    @GetMapping("/referrals/services")
    @PreAuthorize(Spel.OH_OR_CLAIMS)
    public List<EReferralService.ServiceView> services() {
        return referrals.services();
    }

    @PostMapping("/cases/{caseId}/referrals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public ReferralCore.ReferralView create(@PathVariable UUID caseId, @Valid @RequestBody EReferralService.ReferralCommand cmd) {
        return referrals.create(users.require(), caseId, cmd);
    }

    @PutMapping("/referrals/{id}")
    @PreAuthorize(Spel.CLINICIANS)
    public ReferralCore.ReferralView update(@PathVariable UUID id, @Valid @RequestBody EReferralService.ReferralCommand cmd) {
        return referrals.update(users.require(), id, cmd);
    }

    @PostMapping("/referrals/{id}/send")
    @PreAuthorize(Spel.CLINICIANS)
    public ReferralCore.ReferralView send(@PathVariable UUID id) {
        return referrals.send(users.require(), id);
    }

    @GetMapping("/referrals")
    @PreAuthorize(Spel.OH_TEAM)
    public List<ReferralCore.ReferralView> list(
            @RequestParam(required = false) @Pattern(regexp = "DRAFT|SENT|ACCEPTED|DECLINED|SCHEDULED|SEEN|REPORT_RECEIVED|CLOSED|CANCELLED") String status,
            @RequestParam(required = false) Boolean overdue, @RequestParam(required = false) Boolean mine,
            @RequestParam(required = false) @Pattern(regexp = Spel.CODE) String serviceCode,
            @RequestParam(required = false) @Pattern(regexp = Spel.ORG) String providerCode) {
        return referrals.list(users.require(), status, overdue, mine, serviceCode, providerCode);
    }

    @GetMapping("/referrals/{id}")
    @PreAuthorize(Spel.OH_TEAM)
    public ReferralCore.ReferralView get(@PathVariable UUID id) {
        return referrals.get(users.require(), id);
    }

    public record RemindRequest(@NotEmpty @Size(max = 200) List<@NotNull UUID> referralIds) {
    }

    @PostMapping("/referrals/remind")
    @PreAuthorize(Spel.OH_TEAM)
    public EReferralService.Reminded remind(@Valid @RequestBody RemindRequest r) {
        return referrals.remind(users.require(), r.referralIds());
    }

    public record CancelRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    @PostMapping("/referrals/{id}/cancel")
    @PreAuthorize(Spel.CLINICIANS)
    public ReferralCore.ReferralView cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest r) {
        return referrals.cancel(users.require(), id, r.reason());
    }

    @PostMapping("/referrals/{id}/close")
    @PreAuthorize(Spel.CLINICIANS)
    public ReferralCore.ReferralView close(@PathVariable UUID id) {
        return referrals.close(users.require(), id);
    }

    public record RatingRequest(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 2000) String comment) {
    }

    @PostMapping("/referrals/{id}/rating")
    @PreAuthorize(Spel.CLINICIANS)
    public ReferralCore.ReferralView rate(@PathVariable UUID id, @Valid @RequestBody RatingRequest r) {
        return referrals.rate(users.require(), id, r.rating(), r.comment());
    }

    @GetMapping("/cases/{caseId}/referrals")
    @PreAuthorize(Spel.OH_TEAM)
    public List<ReferralCore.ReferralView> byCase(@PathVariable UUID caseId) {
        return referrals.byCase(users.require(), caseId);
    }

    @GetMapping("/treatment/kpis")
    @PreAuthorize("hasAnyRole('CMO','NURSING_HEAD')")
    public TreatmentKpis.Kpis kpis(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return kpis.kpis(from, to);
    }

    // ------------------------------------------------------------------ integration

    @GetMapping("/integration/referrals/{id}/fhir")
    @PreAuthorize(Spel.INTEGRATION)
    public Map<String, Object> fhir(@PathVariable UUID id) {
        return referrals.fhir(users.require().username(), id);
    }

    @PostMapping("/integration/referrals/fhir/document-reference")
    @PreAuthorize(Spel.INTEGRATION)
    public EReferralService.DocumentReferenceResult documentReference(@RequestBody @Size(max = 20) Map<String, Object> body) {
        return referrals.documentReference(users.require().username(), body);
    }

    public record TokenRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{20,100}") String token) {
    }

    @PostMapping("/integration/telehealth/tokens/redeem")
    @PreAuthorize(Spel.INTEGRATION)
    public TelehealthService.TokenCheck redeem(@Valid @RequestBody TokenRequest r) {
        return telehealth.redeem(users.require().username(), r.token());
    }
}
