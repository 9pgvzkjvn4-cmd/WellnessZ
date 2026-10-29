package com.wellnessz.e06.model;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface E06EpisodeRepository extends JpaRepository<Episode, UUID> {

    Optional<Episode> findBySourceSystemAndSourceRef(String sourceSystem, String sourceRef);

    List<Episode> findByEmployeeIdOrderByStartDateDesc(String employeeId);

    List<Episode> findByEmployeeIdInOrderByStartDateDesc(Collection<String> employeeIds);

    /** Episodes overlapping [from, to] (open episodes overlap every later window). */
    @Query("select e from E06Episode e where e.startDate <= ?2 and (e.endDate is null or e.endDate >= ?1) order by e.startDate")
    List<Episode> findOverlapping(LocalDate from, LocalDate to);

    /** Employees with an episode that is open or ended on / after {@code since} – the nightly trigger population. */
    @Query("select distinct e.employeeId from E06Episode e where e.endDate is null or e.endDate >= ?1")
    List<String> findEmployeesActiveSince(LocalDate since);

    @Query("select distinct e.employeeId from E06Episode e where e.startDate >= ?1")
    List<String> findEmployeesWithStartSince(LocalDate since);
}
