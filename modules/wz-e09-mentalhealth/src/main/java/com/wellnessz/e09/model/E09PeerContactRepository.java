package com.wellnessz.e09.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09PeerContactRepository extends JpaRepository<PeerContact, UUID> {

    List<PeerContact> findByVolunteerUsernameOrderByContactDateDesc(String volunteerUsername);

    List<PeerContact> findByContactDateBetween(LocalDate from, LocalDate to);

    boolean existsByContactCode(String contactCode);
}
