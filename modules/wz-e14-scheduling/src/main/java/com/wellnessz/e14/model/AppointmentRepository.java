package com.wellnessz.e14.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /** Statuses that occupy a clinician, room, device or the employee. */
    List<String> OCCUPYING = List.of("BOOKED", "CHECKED_IN", "IN_PROGRESS", "ATTENDED");
    /** Statuses of an appointment that is still to happen or under way. */
    List<String> OPEN = List.of("BOOKED", "CHECKED_IN", "IN_PROGRESS");

    @Query("""
            select a from Appointment a where a.status in ?3 and a.startsAt < ?2 and a.endsAt > ?1
               and (a.siteCode = ?4 or a.clinicianUsername in ?5 or a.employeeId = ?6)""")
    List<Appointment> findOccupying(Instant from, Instant to, Collection<String> statuses, String siteCode, Collection<String> clinicians,
                                    String employeeId);

    @Query("select a from Appointment a where a.siteCode = ?1 and a.startsAt >= ?2 and a.startsAt < ?3 order by a.startsAt, a.id")
    List<Appointment> findAtSite(String siteCode, Instant from, Instant to, Pageable page);

    @Query("select a from Appointment a where a.employeeId = ?1 order by a.startsAt desc, a.id")
    List<Appointment> findForEmployee(String employeeId, Pageable page);

    @Query("select a from Appointment a where a.employeeId = ?1 and a.status in ?2 and a.endsAt > ?3 order by a.startsAt")
    List<Appointment> findOpenForEmployee(String employeeId, Collection<String> statuses, Instant after);

    @Query("select a from Appointment a where a.employeeId in ?1 and a.status in ?2 and a.startsAt < ?4 and a.endsAt > ?3")
    List<Appointment> findForEmployeesBetween(Collection<String> employeeIds, Collection<String> statuses, Instant from, Instant to);

    List<Appointment> findBySourceTypeAndSourceRefAndStatusNotOrderByStartsAt(String sourceType, String sourceRef, String status);

    List<Appointment> findByCampaignId(UUID campaignId);

    @Query("select a from Appointment a where a.status = 'BOOKED' and a.startsAt > ?1 and a.startsAt <= ?2 order by a.startsAt")
    List<Appointment> findBookedStartingBetween(Instant from, Instant to, Pageable page);

    @Query("select a from Appointment a where a.status in ('BOOKED','CHECKED_IN') and a.startedAt is null and a.endsAt < ?1 order by a.endsAt")
    List<Appointment> findNoShowCandidates(Instant endedBefore, Pageable page);

    @Query("""
            select a from Appointment a where a.siteCode = ?1 and a.status in ('CHECKED_IN','IN_PROGRESS')
               and a.checkedInAt >= ?2 and a.checkedInAt < ?3 order by a.checkedInAt, a.id""")
    List<Appointment> findQueue(String siteCode, Instant from, Instant to);

    @Query("select count(a) from Appointment a where a.siteCode = ?1 and a.ticket is not null and a.checkedInAt >= ?2 and a.checkedInAt < ?3")
    long countTickets(String siteCode, Instant from, Instant to);

    @Query("""
            select a from Appointment a where a.siteCode = ?1 and a.calledAt is not null and a.checkedInAt >= ?2 and a.checkedInAt < ?3""")
    List<Appointment> findCalledBetween(String siteCode, Instant from, Instant to);

    @Query("select a from Appointment a where a.startsAt >= ?1 and a.startsAt < ?2 and (?3 is null or a.siteCode = ?3)")
    List<Appointment> findBetween(Instant from, Instant to, String siteCode);

    @Query("select a from Appointment a where a.status = 'ATTENDED' and a.startsAt >= ?1 and a.startsAt < ?2")
    List<Appointment> findAttendedBetween(Instant from, Instant to);
}
