package com.wellnessz.e05.model;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RosterFeedMessageRepository extends JpaRepository<RosterFeedMessage, String> {

    List<RosterFeedMessage> findBySentAtGreaterThanEqualOrderBySentAtAsc(Instant since, Pageable page);

    List<RosterFeedMessage> findBySentAtBetweenOrderBySentAtAsc(Instant from, Instant to);

    List<RosterFeedMessage> findBySentAtLessThanOrderByEmployeeIdAscSentAtAsc(Instant to);

    List<RosterFeedMessage> findByAckAtIsNullAndAlertedAtIsNullAndSentAtBefore(Instant before);

    List<RosterFeedMessage> findByEmployeeIdInOrderBySentAtDesc(Collection<String> employeeIds);
}
