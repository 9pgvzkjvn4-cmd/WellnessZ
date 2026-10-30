package com.wellnessz.e09.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09PeerVolunteerRepository extends JpaRepository<PeerVolunteer, UUID> {

    List<PeerVolunteer> findAllByOrderByDisplayNameAsc();

    Optional<PeerVolunteer> findByUsername(String username);

    List<PeerVolunteer> findByAvailableTrueOrderByDisplayNameAsc();
}
