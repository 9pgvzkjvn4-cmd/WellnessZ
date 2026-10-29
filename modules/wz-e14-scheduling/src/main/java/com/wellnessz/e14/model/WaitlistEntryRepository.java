package com.wellnessz.e14.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {

    @Query("""
            select w from WaitlistEntry w where (?1 is null or w.status = ?1) and (?2 is null or w.siteCode = ?2)
             order by w.priority, w.createdAt""")
    List<WaitlistEntry> search(String status, String siteCode);

    List<WaitlistEntry> findByAppointmentTypeAndSiteCodeAndStatusOrderByPriorityAscCreatedAtAsc(String appointmentType, String siteCode, String status);

    List<WaitlistEntry> findByEmployeeIdAndAppointmentTypeAndStatusIn(String employeeId, String appointmentType, Collection<String> statuses);
}
