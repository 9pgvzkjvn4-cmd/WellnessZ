package com.wellnessz.casekernel.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CaseAttachmentRepository extends JpaRepository<CaseAttachment, UUID> {

    /** Metadata only (content is lazy and never needed for listings). */
    @Query("select new com.wellnessz.casekernel.model.AttachmentMeta(a.id, a.fileName, a.contentType, a.size, a.uploadedAt) "
            + "from CaseAttachment a where a.caseId = ?1 order by a.uploadedAt")
    List<AttachmentMeta> findMeta(UUID caseId);

    long countByCaseId(UUID caseId);

    List<CaseAttachment> findByCaseId(UUID caseId);
}
