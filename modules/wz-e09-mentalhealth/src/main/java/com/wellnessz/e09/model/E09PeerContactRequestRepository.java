package com.wellnessz.e09.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface E09PeerContactRequestRepository extends JpaRepository<PeerContactRequest, UUID> {

    Optional<PeerContactRequest> findByContactCode(String contactCode);

    boolean existsByContactCode(String contactCode);

    List<PeerContactRequest> findByVolunteerUsernameAndRequestedAtAfterOrderByRequestedAtDesc(String volunteerUsername, Instant after);

    long countByVolunteerUsernameAndStatus(String volunteerUsername, String status);

    @Query("select r from E09PeerContactRequest r where r.callbackPhone is not null and r.requestedAt < ?1")
    List<PeerContactRequest> findPhonesToPurge(Instant before);
}
