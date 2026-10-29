package com.wellnessz.e08.disclosure;

import java.util.Optional;
import java.util.Set;

/**
 * Which documents may go into an insurer disclosure set (E08-F03): documents of the claim's employee, signed, of an allowed
 * type – provider / external clinical reports only when an OH role adds them – and never medico-legal reports, clinical
 * photographs, restricted-class documents or legally privileged material.
 */
public final class DisclosureRules {

    public static final Set<String> ALLOWED_TYPES = Set.of("FITNESS_CERTIFICATE", "RESTRICTION_NOTE", "RTW_PLAN", "PREAUTH_REQUEST",
            "FIRST_REPORT_OF_INJURY", "PROVIDER_INVOICE", "REDACTED_COPY");
    public static final Set<String> OH_ONLY_TYPES = Set.of("PROVIDER_REPORT", "EXTERNAL_REPORT");
    public static final Set<String> NEVER_TYPES = Set.of("MEDICO_LEGAL_REPORT", "PHOTO");

    /** Facts about one candidate document. */
    public record Candidate(String type, String employeeId, boolean signed, boolean restrictedClass, boolean privileged) {
    }

    private DisclosureRules() {
    }

    /** @return the reason the document is not disclosable, or empty when it may be added by this actor */
    public static Optional<String> refusal(Candidate d, String claimEmployeeId, boolean actorIsOh) {
        if (d.employeeId() == null || !d.employeeId().equals(claimEmployeeId)) {
            return Optional.of("not a document of the claim's employee");
        }
        if (NEVER_TYPES.contains(d.type())) {
            return Optional.of(d.type() + " is never disclosed to insurers");
        }
        if (d.restrictedClass()) {
            return Optional.of("restricted-class documents are never disclosed");
        }
        if (d.privileged()) {
            return Optional.of("legally privileged material is excluded");
        }
        if (OH_ONLY_TYPES.contains(d.type())) {
            if (!actorIsOh) {
                return Optional.of(d.type() + " can only be added by Occupational Health");
            }
        } else if (!ALLOWED_TYPES.contains(d.type())) {
            return Optional.of(d.type() + " is not a disclosable document type");
        }
        if (!d.signed()) {
            return Optional.of("only signed documents can be disclosed");
        }
        return Optional.empty();
    }
}
