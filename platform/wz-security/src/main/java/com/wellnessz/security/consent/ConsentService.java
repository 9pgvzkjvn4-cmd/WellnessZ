package com.wellnessz.security.consent;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/** Consent capture, withdrawal and checks. Other modules call {@link #hasActiveConsent} before sharing data. */
@Service
public class ConsentService {

    private final ConsentRepository repository;
    private final AuditService audit;
    private final Clock clock;
    private final String noticeVersion;

    public ConsentService(ConsentRepository repository, AuditService audit, Clock clock,
                          @Value("${wellnessz.consent.notice-version:2026.1}") String noticeVersion) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
        this.noticeVersion = noticeVersion;
    }

    @Transactional
    public Consent grant(CurrentUser user, ConsentPurpose purpose, String scope) {
        if (user.employeeId() == null) {
            throw ApiException.forbidden("Only employees can grant consent");
        }
        Consent consent = repository.save(new Consent(user.employeeId(), purpose, scope, noticeVersion, user.username(), clock.instant()));
        audit.record("CONSENT_GRANT", "CONSENT", consent.getId().toString(), AuditService.Outcome.SUCCESS, purpose.name());
        return consent;
    }

    @Transactional
    public Consent withdraw(CurrentUser user, UUID id) {
        Consent consent = repository.findById(id).orElseThrow(() -> ApiException.notFound("Consent"));
        if (!consent.getEmployeeId().equals(user.employeeId())) {
            audit.record("CONSENT_WITHDRAW", "CONSENT", id.toString(), AuditService.Outcome.DENIED, "Not the data subject");
            throw ApiException.notFound("Consent"); // do not reveal existence
        }
        consent.withdraw(clock.instant());
        audit.record("CONSENT_WITHDRAW", "CONSENT", id.toString(), AuditService.Outcome.SUCCESS, consent.getPurpose().name());
        return consent;
    }

    @Transactional(readOnly = true)
    public List<Consent> mine(CurrentUser user) {
        return user.employeeId() == null ? List.of() : repository.findByEmployeeIdOrderByGrantedAtDesc(user.employeeId());
    }

    @Transactional(readOnly = true)
    public boolean hasActiveConsent(String employeeId, ConsentPurpose purpose) {
        return repository.existsByEmployeeIdAndPurposeAndStatus(employeeId, purpose, Consent.Status.GRANTED);
    }
}
