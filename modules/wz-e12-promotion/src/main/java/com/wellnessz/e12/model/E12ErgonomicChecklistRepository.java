package com.wellnessz.e12.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E12ErgonomicChecklistRepository extends JpaRepository<ErgonomicChecklist, String> {

    List<ErgonomicChecklist> findAllByOrderByCodeAsc();
}
