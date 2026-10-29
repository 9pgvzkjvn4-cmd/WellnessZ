package com.wellnessz.documents.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentShareRepository extends JpaRepository<DocumentShare, UUID> {

    List<DocumentShare> findByDocumentIdOrderBySharedAtAsc(UUID documentId);

    List<DocumentShare> findByDocumentIdInOrderBySharedAtAsc(Collection<UUID> documentIds);

    List<DocumentShare> findByTargetAndRecipientAndRevokedAtIsNull(DocumentShare.Target target, String recipient);

    List<DocumentShare> findByTargetAndRevokedAtIsNull(DocumentShare.Target target);

    List<DocumentShare> findByConsentIdAndRevokedAtIsNull(UUID consentId);
}
