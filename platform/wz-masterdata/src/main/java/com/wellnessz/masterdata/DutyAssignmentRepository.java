package com.wellnessz.masterdata;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DutyAssignmentRepository extends JpaRepository<DutyAssignment, UUID> {

    Optional<DutyAssignment> findBySourceAndSourceRef(String source, String sourceRef);

    /** Duties of one employee overlapping a time window (index on employee_id, starts_at). */
    @Query("select d from DutyAssignment d where d.employeeId = :employeeId and d.startsAt < :to and d.endsAt > :from order by d.startsAt")
    List<DutyAssignment> findForEmployee(@Param("employeeId") String employeeId, @Param("from") Instant from, @Param("to") Instant to);

    /** Everyone on the same duties (flight / shift / facility) overlapping a time window – co-workers for tracing and CISM. */
    @Query("select d from DutyAssignment d where d.dutyRef in :dutyRefs and d.startsAt < :to and d.endsAt > :from order by d.startsAt")
    List<DutyAssignment> findOnDuties(@Param("dutyRefs") Collection<String> dutyRefs, @Param("from") Instant from, @Param("to") Instant to);

    /** Everyone in the same proximity groups (aircraft / work area) overlapping a time window. */
    @Query("select d from DutyAssignment d where d.proximityGroup in :groups and d.startsAt < :to and d.endsAt > :from order by d.startsAt")
    List<DutyAssignment> findInProximityGroups(@Param("groups") Collection<String> groups, @Param("from") Instant from, @Param("to") Instant to);

    /** Crew rostered to a destination (layover city) in a window – targeting of health advisories. */
    @Query("select d from DutyAssignment d where d.destinationCode = :destination and d.startsAt < :to and d.endsAt > :from order by d.startsAt")
    List<DutyAssignment> findToDestination(@Param("destination") String destination, @Param("from") Instant from, @Param("to") Instant to);
}
