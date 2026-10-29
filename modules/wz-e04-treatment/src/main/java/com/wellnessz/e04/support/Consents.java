package com.wellnessz.e04.support;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.security.consent.Consent;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentRepository;
import com.wellnessz.security.consent.ConsentService;

/** Resolves the employee's active consent for a disclosure purpose (explicit id, else the latest granted one). */
@Component
public class Consents {

    private final ConsentService consents;
    private final ConsentRepository repository;

    public Consents(ConsentService consents, ConsentRepository repository) {
        this.consents = consents;
        this.repository = repository;
    }

    public record ConsentView(UUID id, String purpose, java.time.Instant grantedAt, String scope) {
    }

    /** Active consents of the employee for one purpose, newest first. */
    public java.util.List<ConsentView> active(String employeeId, ConsentPurpose purpose) {
        return repository.findByEmployeeIdOrderByGrantedAtDesc(employeeId).stream()
                .filter(c -> c.getPurpose() == purpose && c.getStatus() == Consent.Status.GRANTED)
                .map(c -> new ConsentView(c.getId(), c.getPurpose().name(), c.getGrantedAt(), c.getScope())).toList();
    }

    public boolean isActive(UUID consentId, String employeeId, ConsentPurpose purpose) {
        return consents.isActive(consentId, employeeId, purpose);
    }

    /** 422 {@code consent_required} unless an active consent of the purpose exists. */
    public UUID require(UUID consentId, String employeeId, ConsentPurpose purpose, String what) {
        if (consentId != null) {
            if (consents.isActive(consentId, employeeId, purpose)) {
                return consentId;
            }
        } else {
            UUID latest = repository.findByEmployeeIdOrderByGrantedAtDesc(employeeId).stream()
                    .filter(c -> c.getPurpose() == purpose && c.getStatus() == Consent.Status.GRANTED).map(Consent::getId).findFirst().orElse(null);
            if (latest != null) {
                return latest;
            }
        }
        throw TreatmentSupport.unprocessable("consent_required", what + " requires the employee's active consent (" + purpose.name() + ")");
    }
}
