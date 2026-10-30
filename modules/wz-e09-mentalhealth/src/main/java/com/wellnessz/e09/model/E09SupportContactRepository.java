package com.wellnessz.e09.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09SupportContactRepository extends JpaRepository<SupportContact, UUID> {

    List<SupportContact> findBySupportCaseIdOrderByOccurredAtAsc(UUID supportCaseId);
}
