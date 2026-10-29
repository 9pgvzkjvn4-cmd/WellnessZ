package com.wellnessz.masterdata.fitness;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobDemandProfileRepository extends JpaRepository<JobDemandProfile, String> {

    List<JobDemandProfile> findAllByOrderByUnitAscJobCodeAsc();

    List<JobDemandProfile> findByUnitOrderByJobCodeAsc(String unit);
}
