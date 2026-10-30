package com.wellnessz.e09.model;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface E09CismAffectedRepository extends JpaRepository<CismAffected, UUID> {

    List<CismAffected> findByEventIdOrderByEmployeeIdAsc(UUID eventId);

    List<CismAffected> findByEventIdIn(Collection<UUID> eventIds);

    Optional<CismAffected> findByEventIdAndEmployeeId(UUID eventId, String employeeId);

    List<CismAffected> findByEmployeeIdAndOfferSentAtIsNotNullOrderByOfferSentAtDesc(String employeeId);

    @Query("""
            select a from E09CismAffected a where a.offerSentAt is not null and (a.response is null or a.response <> 'DECLINED')
               and ((a.followUp1wDue <= ?1 and a.followUp1wDone = false and a.followUp1wSentAt is null)
                 or (a.followUp4wDue <= ?1 and a.followUp4wDone = false and a.followUp4wSentAt is null))""")
    List<CismAffected> findFollowUpsDue(LocalDate today);
}
