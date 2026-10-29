package com.wellnessz.masterdata.fitness;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FitnessStandardRepository extends JpaRepository<FitnessStandard, FitnessStandard.Key> {

    List<FitnessStandard> findByStatusOrderByCodeAsc(String status);

    Optional<FitnessStandard> findFirstByCodeOrderByVersionDesc(String code);

    /** Current (active) version of a standard. */
    default Optional<FitnessStandard> current(String code) {
        return findFirstByCodeOrderByVersionDesc(code).filter(FitnessStandard::isActive);
    }
}
