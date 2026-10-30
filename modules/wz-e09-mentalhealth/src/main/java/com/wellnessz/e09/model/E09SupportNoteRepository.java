package com.wellnessz.e09.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09SupportNoteRepository extends JpaRepository<SupportNote, UUID> {

    List<SupportNote> findBySupportCaseIdOrderByCreatedAtAsc(UUID supportCaseId);
}
