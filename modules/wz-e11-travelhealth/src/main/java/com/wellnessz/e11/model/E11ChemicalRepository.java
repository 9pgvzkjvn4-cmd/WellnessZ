package com.wellnessz.e11.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E11ChemicalRepository extends JpaRepository<Chemical, String> {

    List<Chemical> findAllByOrderByNameAsc();
}
