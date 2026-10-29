package com.wellnessz.e08.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E08ReportFormRepository extends JpaRepository<ReportForm, String> {

    List<ReportForm> findByActiveTrueOrderByCode();
}
