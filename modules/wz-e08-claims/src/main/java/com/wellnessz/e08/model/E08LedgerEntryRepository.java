package com.wellnessz.e08.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface E08LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    Optional<LedgerEntry> findBySourceTypeAndSourceRefAndKind(String sourceType, String sourceRef, String kind);

    /** The claim's own entries plus the case-level entries (no claim) of the claim's case. */
    @Query("select e from E08LedgerEntry e where e.claimId = ?1 or (e.claimId is null and e.caseId = ?2) order by e.incurredOn, e.createdAt")
    List<LedgerEntry> findForClaim(UUID claimId, UUID caseId);

    List<LedgerEntry> findByIncurredOnBetweenOrderByIncurredOn(LocalDate from, LocalDate to);
}
