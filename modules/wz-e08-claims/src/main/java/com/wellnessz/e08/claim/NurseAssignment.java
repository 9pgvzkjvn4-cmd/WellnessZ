package com.wellnessz.e08.claim;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.wellnessz.common.model.Roles;

/**
 * Nurse case manager choice for a lost-time claim (E08-F04): the case's current owner when they hold CASE_MANAGER or
 * OH_CLINICIAN and are available; otherwise the least-loaded available CASE_MANAGER still below their caseload cap
 * (ties broken by username for a deterministic result). Empty when nobody qualifies – the claim then shows as unassigned
 * on the nurse-assignment KPI.
 */
public final class NurseAssignment {

    /** Staff snapshot: roles, caseload cap and availability today (active and not out of office). */
    public record Candidate(String username, Set<String> roles, int caseloadCap, boolean available) {
    }

    public record Choice(String username, boolean caseOwner) {
    }

    private NurseAssignment() {
    }

    public static Optional<Choice> choose(Candidate owner, Collection<Candidate> staff, Map<String, Long> openCaseload) {
        if (owner != null && owner.available()
                && (owner.roles().contains(Roles.CASE_MANAGER) || owner.roles().contains(Roles.OH_CLINICIAN))) {
            return Optional.of(new Choice(owner.username(), true));
        }
        return staff.stream()
                .filter(Candidate::available)
                .filter(s -> s.roles().contains(Roles.CASE_MANAGER))
                .filter(s -> openCaseload.getOrDefault(s.username(), 0L) < s.caseloadCap())
                .min(Comparator.comparingLong((Candidate s) -> openCaseload.getOrDefault(s.username(), 0L)).thenComparing(Candidate::username))
                .map(s -> new Choice(s.username(), false));
    }
}
