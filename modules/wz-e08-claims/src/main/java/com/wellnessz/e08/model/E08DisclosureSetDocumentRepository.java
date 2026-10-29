package com.wellnessz.e08.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E08DisclosureSetDocumentRepository extends JpaRepository<DisclosureSetDocument, UUID> {

    List<DisclosureSetDocument> findBySetIdOrderByPositionAsc(UUID setId);

    List<DisclosureSetDocument> findBySetIdInOrderByPositionAsc(Collection<UUID> setIds);
}
