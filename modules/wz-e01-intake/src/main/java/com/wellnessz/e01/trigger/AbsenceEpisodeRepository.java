package com.wellnessz.e01.trigger;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AbsenceEpisodeRepository extends JpaRepository<AbsenceEpisode, UUID> {

    Optional<AbsenceEpisode> findBySourceRef(String sourceRef);

    List<AbsenceEpisode> findByEmployeeIdAndStartDateGreaterThanEqualOrderByStartDate(String employeeId, LocalDate since);

    List<AbsenceEpisode> findByEmployeeIdOrderByStartDateDesc(String employeeId);
}
