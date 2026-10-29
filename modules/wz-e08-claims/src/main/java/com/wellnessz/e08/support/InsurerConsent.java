package com.wellnessz.e08.support;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.security.consent.Consent;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentRepository;
import com.wellnessz.security.consent.ConsentService;

/**
 * Consent gate for every disclosure to an insurer / TPA: the employee's active {@code INSURER_SHARING} consent, either the
 * one named by the caller or – when none is named – the most recent active one. Missing consent → 422 {@code consent_required}.
 */
@Component
public class InsurerConsent {

    private final ConsentService consents;
    private final ConsentRepository repository;

    public InsurerConsent(ConsentService consents, ConsentRepository repository) {
        this.consents = consents;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public UUID require(String employeeId, UUID consentId) {
        UUID id = consentId != null ? consentId : latest(employeeId).orElse(null);
        if (id == null || !consents.isActive(id, employeeId, ConsentPurpose.INSURER_SHARING)) {
            throw ClaimsSupport.unprocessable("consent_required",
                    "Disclosure to an insurer or TPA requires the employee's active consent to insurer sharing");
        }
        return id;
    }

    public boolean isActive(UUID consentId, String employeeId) {
        return consents.isActive(consentId, employeeId, ConsentPurpose.INSURER_SHARING);
    }

    /** Active INSURER_SHARING consent of an employee (consent picker of the disclosure-set screen). */
    public record ConsentView(UUID id, String purpose, java.time.Instant grantedAt, String scope) {
    }

    @Transactional(readOnly = true)
    public java.util.List<ConsentView> active(String employeeId) {
        return repository.findByEmployeeIdOrderByGrantedAtDesc(employeeId).stream()
                .filter(c -> c.getPurpose() == ConsentPurpose.INSURER_SHARING && c.getStatus() == Consent.Status.GRANTED)
                .map(c -> new ConsentView(c.getId(), c.getPurpose().name(), c.getGrantedAt(), c.getScope())).toList();
    }

    @Transactional(readOnly = true)
    public Optional<UUID> latest(String employeeId) {
        return repository.findByEmployeeIdOrderByGrantedAtDesc(employeeId).stream()
                .filter(c -> c.getPurpose() == ConsentPurpose.INSURER_SHARING && c.getStatus() == Consent.Status.GRANTED)
                .map(Consent::getId).findFirst();
    }
}
