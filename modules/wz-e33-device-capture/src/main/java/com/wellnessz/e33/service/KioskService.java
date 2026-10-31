package com.wellnessz.e33.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e33.domain.CaptureStateMachine.State;
import com.wellnessz.e33.domain.CaptureStateMachine.Trigger;
import com.wellnessz.e33.domain.PlainLanguage;
import com.wellnessz.e33.model.CaptureObservation;
import com.wellnessz.e33.model.CaptureSession;
import com.wellnessz.e33.model.E33CaptureSessionRepository;
import com.wellnessz.e33.model.FormRule;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.Consent;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentRepository;
import com.wellnessz.security.consent.ConsentService;

/**
 * Kiosk self-measurement (E33-HLR-07, E33-BL-013, contract 14.6): the kiosk endpoint (kind {@code KIOSK}, authenticated by the
 * device-ingest filter) identifies the employee by badge, records the {@code KIOSK_SELF_MEASUREMENT} consent through the
 * platform consent service, binds its device instances to the session for the readings (BP, weight) and shows a plain-language
 * summary in the employee's language – category text only, no diagnosis. The session ends after 3 minutes without activity
 * (privacy screen). Only the first name and the language ever leave the core.
 */
@Service
public class KioskService {

    public static final long IDLE_SECONDS = 180;
    static final String DEFAULT_FORM = "E12.SCREENING_RESULT";

    public record IdentifyResult(UUID kioskSessionId, String firstName, String language, boolean consentRequired, String consentNoticeVersion) {
    }

    public record ConsentResult(UUID kioskSessionId, String state, boolean consented) {
    }

    public record SummaryItem(String category, Instant measuredAt, String plainLanguage, String educationLink) {
    }

    public record KioskSummary(UUID kioskSessionId, String language, List<SummaryItem> items, String state) {
    }

    private final E33CaptureSessionRepository sessions;
    private final CaptureSessionService sessionService;
    private final CaptureTransitions transitions;
    private final FormRegistry forms;
    private final DeviceSpis spis;
    private final ConsentService consents;
    private final ConsentRepository consentRepository;
    private final AuditService audit;
    private final CaptureSupport support;
    private final String noticeVersion;

    public KioskService(E33CaptureSessionRepository sessions, CaptureSessionService sessionService, CaptureTransitions transitions,
                        FormRegistry forms, DeviceSpis spis, ConsentService consents,
                        ConsentRepository consentRepository, AuditService audit, CaptureSupport support,
                        @Value("${wellnessz.consent.notice-version:2026.1}") String noticeVersion) {
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.transitions = transitions;
        this.forms = forms;
        this.spis = spis;
        this.consents = consents;
        this.consentRepository = consentRepository;
        this.audit = audit;
        this.support = support;
        this.noticeVersion = noticeVersion;
    }

    @Transactional
    public IdentifyResult identify(String badgeId) {
        support.requireEnabled();
        DeviceEndpointPrincipal kiosk = requireKiosk();
        Employee e = support.employeeByBadge(badgeId).orElseThrow(() -> {
            audit.recordAs(kiosk.authenticationName(), "DEVICE_ENDPOINT", "DEVICE_KIOSK_SESSION", "DEVICE_ENDPOINT", kiosk.endpointId(),
                    AuditService.Outcome.DENIED, "badge not recognised");
            return ApiException.notFound("Badge");
        });
        // a kiosk shows one person at a time: earlier sessions of this kiosk end now
        endOpenSessionsOf(kiosk.endpointId(), "next_person");
        FormRule rule = forms.rule(support.configString("device.capture.kiosk-form").orElse(DEFAULT_FORM));
        Set<String> instances = new LinkedHashSet<>(kiosk.deviceInstanceIds());
        spis.catalogue().instancesForEndpoint(kiosk.endpointId(), null).stream().filter(DeviceCatalogueLookup.DeviceInstance::published)
                .map(DeviceCatalogueLookup.DeviceInstance::deviceInstanceId).forEach(instances::add);
        Optional<Consent> existing = activeConsent(e.getEmployeeId());
        Instant now = support.now();
        CaptureSession s = new CaptureSession();
        s.setId(UUID.randomUUID());
        s.setMode("KIOSK");
        s.setState(State.LISTENING.name());
        s.setEmployeeId(e.getEmployeeId());
        s.setOrderRef(support.configString("device.capture.kiosk-screening-event").orElse(null));
        s.setFormCode(rule.getFormCode());
        s.setFormVersion(rule.getFormVersion());
        s.setCategory(rule.getCategory());
        s.setRestricted(false);
        s.setKioskInstances(String.join(",", instances));
        s.setEndpointId(kiosk.endpointId());
        s.setOperator(kiosk.authenticationName());
        s.setSiteCode(kiosk.siteCode() == null ? "-" : kiosk.siteCode());
        s.setContextPush("NONE");
        s.setOpenedAt(now);
        s.setLastActivityAt(now);
        s.setExpiresAt(now.plusSeconds(IDLE_SECONDS));
        s.setLanguage(PlainLanguage.language(e.getPreferredLanguage()));
        existing.ifPresent(c -> s.setConsentId(c.getId()));
        sessions.save(s);
        transitions.opened(s, kiosk.authenticationName(), existing.isPresent() ? "consent_on_file" : "consent_required");
        audit.recordAs(kiosk.authenticationName(), "DEVICE_ENDPOINT", "DEVICE_KIOSK_SESSION", "DEVICE_CAPTURE_SESSION", s.getId().toString(),
                AuditService.Outcome.SUCCESS, "identified instances=" + instances.size());
        return new IdentifyResult(s.getId(), CaptureSupport.firstName(e.getDisplayName()), s.getLanguage(), existing.isEmpty(), noticeVersion);
    }

    @Transactional
    public ConsentResult consent(UUID kioskSessionId, boolean accepted, String version) {
        support.requireEnabled();
        DeviceEndpointPrincipal kiosk = requireKiosk();
        CaptureSession s = requireSession(kiosk, kioskSessionId);
        if (!accepted) {
            transitions.apply(s, Trigger.CANCEL, kiosk.authenticationName(), "CONSENT_DECLINED", null, false);
            audit.recordAs(kiosk.authenticationName(), "DEVICE_ENDPOINT", "DEVICE_KIOSK_SESSION", "DEVICE_CAPTURE_SESSION", s.getId().toString(),
                    AuditService.Outcome.SUCCESS, "consent declined");
            return new ConsentResult(s.getId(), s.getState(), false);
        }
        if (version == null || !version.equals(noticeVersion)) {
            throw CaptureSupport.unprocessable("notice_version_mismatch", "The consent notice shown is not the current version");
        }
        if (s.getConsentId() == null) {
            Consent c = consents.grantOnBehalf(s.getEmployeeId(), ConsentPurpose.KIOSK_SELF_MEASUREMENT, "KIOSK:" + kiosk.endpointId(),
                    kiosk.authenticationName());
            s.setConsentId(c.getId());
        }
        touch(s);
        audit.recordAs(kiosk.authenticationName(), "DEVICE_ENDPOINT", "DEVICE_KIOSK_SESSION", "DEVICE_CAPTURE_SESSION", s.getId().toString(),
                AuditService.Outcome.SUCCESS, "consent recorded");
        return new ConsentResult(s.getId(), s.getState(), true);
    }

    @Transactional
    public KioskSummary summary(UUID kioskSessionId) {
        support.requireEnabled();
        DeviceEndpointPrincipal kiosk = requireKiosk();
        CaptureSession s = requireSession(kiosk, kioskSessionId);
        if (s.getConsentId() == null) {
            throw CaptureSupport.unprocessable("consent_missing", "Self-measurement needs the employee's consent");
        }
        touch(s);
        List<CaptureObservation> readings = sessionService.observationsOf(s).stream()
                .filter(o -> !"REJECTED".equals(o.getDecision()) && o.getValuesJson() != null).toList();
        Map<String, java.math.BigDecimal> latest = new LinkedHashMap<>();
        Instant measured = null;
        for (CaptureObservation o : readings) {
            latest.putAll(SummaryReleaser.numeric(support.readMap(o.getValuesJson())));
            measured = o.getEffectiveAt() == null ? o.getReceivedAt() : o.getEffectiveAt();
        }
        List<SummaryItem> items = new ArrayList<>();
        if (!latest.isEmpty()) {
            PlainLanguage.Summary text = PlainLanguage.summarise(s.getCategory(), latest, LocalDate.ofInstant(measured, ZoneOffset.UTC), s.getLanguage());
            items.add(new SummaryItem(s.getCategory(), measured, text.text(), text.educationLink()));
        }
        return new KioskSummary(s.getId(), s.getLanguage(), items, s.getState());
    }

    /** Ends idle kiosk sessions (privacy screen); returns the number ended. */
    @Transactional
    public int endIdle() {
        int n = 0;
        for (CaptureSession s : sessions.findByModeAndStateIn("KIOSK", List.of("LISTENING", "PREVIEW"))) {
            if (s.getExpiresAt().isBefore(support.now())) {
                end(s, "idle");
                n++;
            }
        }
        return n;
    }

    private void endOpenSessionsOf(String endpointId, String reason) {
        sessions.findByModeAndStateIn("KIOSK", List.of("LISTENING", "PREVIEW")).stream()
                .filter(s -> endpointId.equals(s.getEndpointId())).forEach(s -> end(s, reason));
    }

    /** A kiosk visit ends ACCEPTED when readings were filed (auto-post), otherwise CANCELLED (nothing measured). */
    private void end(CaptureSession s, String reason) {
        boolean filed = sessionService.observationsOf(s).stream().anyMatch(o -> "AUTO_POSTED".equals(o.getDecision()) || "ACCEPTED".equals(o.getDecision()));
        if (filed && State.PREVIEW.name().equals(s.getState())) {
            transitions.apply(s, Trigger.ACCEPT, null, "KIOSK_END", reason, true);
        } else {
            transitions.apply(s, Trigger.CANCEL, null, "KIOSK_END", reason, true);
        }
    }

    private void touch(CaptureSession s) {
        s.setLastActivityAt(support.now());
        s.setExpiresAt(support.now().plusSeconds(IDLE_SECONDS));
    }

    private Optional<Consent> activeConsent(String employeeId) {
        return consentRepository.findByEmployeeIdOrderByGrantedAtDesc(employeeId).stream()
                .filter(c -> c.getPurpose() == ConsentPurpose.KIOSK_SELF_MEASUREMENT && c.getStatus() == Consent.Status.GRANTED)
                .filter(c -> noticeVersion.equals(c.getNoticeVersion())).findFirst();
    }

    private static DeviceEndpointPrincipal requireKiosk() {
        DeviceEndpointPrincipal p = DeviceEndpointPrincipal.require();
        if (p.kind() != DeviceCodes.EndpointKind.KIOSK) {
            throw new ApiException(HttpStatus.FORBIDDEN, "endpoint_forbidden", "Only kiosk endpoints may use the kiosk API");
        }
        return p;
    }

    /** The kiosk's own, still open session; another kiosk's or an ended session is "not found" (privacy). */
    private CaptureSession requireSession(DeviceEndpointPrincipal kiosk, UUID id) {
        CaptureSession s = sessions.findById(id).filter(x -> "KIOSK".equals(x.getMode()))
                .filter(x -> kiosk.endpointId().equals(x.getEndpointId())).orElseThrow(() -> ApiException.notFound("Kiosk session"));
        if (State.valueOf(s.getState()).isFinal()) {
            throw ApiException.notFound("Kiosk session");
        }
        if (s.getExpiresAt().isBefore(support.now())) {
            end(s, "idle");
            throw ApiException.notFound("Kiosk session");
        }
        return s;
    }
}
