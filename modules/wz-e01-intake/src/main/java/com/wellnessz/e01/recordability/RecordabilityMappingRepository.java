package com.wellnessz.e01.recordability;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wellnessz.casekernel.model.CaseEnums.Recordability;

public interface RecordabilityMappingRepository extends JpaRepository<RecordabilityMapping, Recordability> {
}
