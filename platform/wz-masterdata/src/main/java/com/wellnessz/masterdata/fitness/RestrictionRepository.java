package com.wellnessz.masterdata.fitness;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RestrictionRepository extends JpaRepository<Restriction, String> {

    List<Restriction> findAllByOrderByCodeAsc();
}
