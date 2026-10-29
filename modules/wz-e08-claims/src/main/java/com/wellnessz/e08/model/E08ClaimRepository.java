package com.wellnessz.e08.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E08ClaimRepository extends JpaRepository<Claim, UUID> {

    List<Claim> findAllByOrderByNotifiedAtDesc();

    List<Claim> findByCaseIdOrderByNotifiedAtDesc(UUID caseId);

    Optional<Claim> findByCaseIdAndClaimType(UUID caseId, String claimType);

    Optional<Claim> findByClaimRef(String claimRef);

    List<Claim> findByPayerCodeOrderByNotifiedAtDesc(String payerCode);

    List<Claim> findByEmployeeIdInOrderByNotifiedAtDesc(Collection<String> employeeIds);

    List<Claim> findByLostTimeTrueOrderByNotifiedAtDesc();
}
