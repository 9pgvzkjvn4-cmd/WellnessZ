package com.wellnessz.e10.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E10DutyStatusRepository extends JpaRepository<DutyStatusRecord, String> {

    List<DutyStatusRecord> findAllByOrderBySinceDesc();
}
