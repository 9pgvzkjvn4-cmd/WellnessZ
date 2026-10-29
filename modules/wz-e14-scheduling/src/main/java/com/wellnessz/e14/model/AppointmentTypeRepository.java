package com.wellnessz.e14.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentTypeRepository extends JpaRepository<AppointmentType, String> {

    List<AppointmentType> findAllByOrderByCode();

    List<AppointmentType> findByActiveTrueOrderByCode();
}
