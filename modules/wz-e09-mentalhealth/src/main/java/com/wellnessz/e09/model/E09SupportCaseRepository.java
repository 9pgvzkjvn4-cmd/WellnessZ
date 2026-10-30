package com.wellnessz.e09.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface E09SupportCaseRepository extends JpaRepository<SupportCase, UUID> {

    List<SupportCase> findAllByOrderByOpenedAtDesc();

    List<SupportCase> findByEmployeeIdOrderByOpenedAtDesc(String employeeId);

    Optional<SupportCase> findBySourceTypeAndSourceRef(String sourceType, String sourceRef);

    long countByEmployeeIdAndStatusNot(String employeeId, String status);

    @Query("select s from E09SupportCase s where s.sharing <> 'NOTHING' and s.employeeId in ?1 order by s.sharingChangedAt desc")
    List<SupportCase> findSharedFor(Collection<String> employeeIds);

    @Query("select s.employeeId from E09SupportCase s where s.openedAt >= ?1")
    List<String> employeesOpenedSince(Instant since);
}
