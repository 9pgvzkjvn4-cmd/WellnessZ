package com.wellnessz.e04.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderInvoiceRepository extends JpaRepository<ProviderInvoice, UUID> {

    List<ProviderInvoice> findByCaseIdOrderByCreatedAtDesc(UUID caseId);

    boolean existsByProviderCodeAndInvoiceNumber(String providerCode, String invoiceNumber);

    List<ProviderInvoice> findByServiceDateBetween(LocalDate from, LocalDate to);

    List<ProviderInvoice> findByPreauthIdOrMatchedPreauthId(UUID preauthId, UUID matchedPreauthId);
}
