package com.wellnessz.e33.service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e33.model.DeviceConsent;
import com.wellnessz.e33.model.E33DeviceConsentRepository;
import com.wellnessz.e33.model.E33EmployeeSummaryRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.Consent;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Employee-facing device features (E33-HLR-09, E33-F12, contract 14.8): released plain-language summaries in the preferred
 * language, and granular, revocable consent for wearable data on top of the platform {@code WEARABLE_DATA} consent (the consent
 * id is what an E12 wearable enrolment refers to; revocation withdraws the platform consent, which stops ingestion and lets E12
 * purge the stored values).
 */
@Service
public class EmployeeDeviceService {

    public static final Set<String> SCOPES = Set.of("STEPS", "SLEEP", "HEART_RATE", "ACTIVITY");

    public record SummaryView(UUID summaryId, String category, Instant measuredAt, String language, String plainLanguage, String educationLink) {
    }

    public record ConsentView(UUID id, UUID consentId, String purpose, List<String> scopes, UUID campaignId, String status, Instant grantedAt,
                              Instant revokedAt) {
    }

    public record GrantCommand(String purpose, List<String> scopes, UUID campaignId) {
    }

    private final E33EmployeeSummaryRepository summaries;
    private final E33DeviceConsentRepository deviceConsents;
    private final ConsentService consents;
    private final AuditService audit;
    private final CaptureSupport support;

    public EmployeeDeviceService(E33EmployeeSummaryRepository summaries, E33DeviceConsentRepository deviceConsents, ConsentService consents,
                                 AuditService audit, CaptureSupport support) {
        this.summaries = summaries;
        this.deviceConsents = deviceConsents;
        this.consents = consents;
        this.audit = audit;
        this.support = support;
    }

    @Transactional(readOnly = true)
    public List<SummaryView> summaries(CurrentUser user) {
        support.requireEnabled();
        String me = requireEmployee(user);
        return summaries.findByEmployeeIdOrderByMeasuredAtDesc(me).stream()
                .map(s -> new SummaryView(s.getId(), s.getCategory(), s.getMeasuredAt(), s.getLanguage(), s.getPlainLanguage(), s.getEducationLink()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConsentView> consents(CurrentUser user) {
        support.requireEnabled();
        String me = requireEmployee(user);
        return deviceConsents.findByEmployeeIdOrderByGrantedAtDesc(me).stream().map(EmployeeDeviceService::view).toList();
    }

    @Transactional
    public ConsentView grant(CurrentUser user, GrantCommand cmd) {
        support.requireEnabled();
        String me = requireEmployee(user);
        if (!"WEARABLE_DATA".equals(cmd.purpose())) {
            throw ApiException.badRequest("validation_failed", "Purpose must be WEARABLE_DATA");
        }
        Set<String> scopes = new LinkedHashSet<>(cmd.scopes() == null ? List.of() : cmd.scopes());
        if (scopes.isEmpty() || !SCOPES.containsAll(scopes)) {
            throw ApiException.badRequest("validation_failed", "Choose one or more of " + SCOPES.stream().sorted().toList());
        }
        String scopeText = String.join(",", scopes);
        Consent c = consents.grant(user, ConsentPurpose.WEARABLE_DATA,
                "WEARABLE_SCOPES:" + scopeText + (cmd.campaignId() == null ? "" : " CAMPAIGN:" + cmd.campaignId()));
        DeviceConsent d = deviceConsents.save(new DeviceConsent(me, c.getId(), "WEARABLE_DATA", scopeText, cmd.campaignId(), support.now()));
        audit.record("DEVICE_CONSENT_GRANT", "DEVICE_CONSENT", d.getId().toString(), AuditService.Outcome.SUCCESS, "WEARABLE_DATA " + scopeText);
        return view(d);
    }

    @Transactional
    public ConsentView revoke(CurrentUser user, UUID id) {
        support.requireEnabled();
        String me = requireEmployee(user);
        DeviceConsent d = deviceConsents.findById(id).filter(x -> x.getEmployeeId().equals(me)).orElse(null);
        if (d == null) {
            audit.record("DEVICE_CONSENT_REVOKE", "DEVICE_CONSENT", id.toString(), AuditService.Outcome.DENIED, "not the data subject");
            throw ApiException.notFound("Consent");
        }
        if ("ACTIVE".equals(d.getStatus())) {
            consents.withdraw(user, d.getConsentId()); // listeners: E12 purge, E33 scope revocation
            d.revoke(support.now());
            audit.record("DEVICE_CONSENT_REVOKE", "DEVICE_CONSENT", d.getId().toString(), AuditService.Outcome.SUCCESS, "WEARABLE_DATA");
        }
        return view(d);
    }

    /** The platform consent was withdrawn (consent centre or here): the scopes resting on it are revoked. */
    @Transactional
    public int consentWithdrawn(UUID consentId) {
        int n = 0;
        for (DeviceConsent d : deviceConsents.findByConsentIdAndStatus(consentId, "ACTIVE")) {
            d.revoke(support.now());
            n++;
        }
        return n;
    }

    private static ConsentView view(DeviceConsent d) {
        return new ConsentView(d.getId(), d.getConsentId(), d.getPurpose(), CaptureSupport.csv(d.getScopes()), d.getCampaignId(), d.getStatus(),
                d.getGrantedAt(), d.getRevokedAt());
    }

    private static String requireEmployee(CurrentUser user) {
        if (user.employeeId() == null) {
            throw ApiException.forbidden("Only employees have device summaries and consents");
        }
        return user.employeeId();
    }
}
