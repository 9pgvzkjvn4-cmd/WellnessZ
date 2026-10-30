package com.wellnessz.e09.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09PeerTrainingRepository extends JpaRepository<PeerTraining, UUID> {

    List<PeerTraining> findByVolunteerIdOrderByCompletedOnDesc(UUID volunteerId);

    List<PeerTraining> findByVolunteerIdIn(Collection<UUID> volunteerIds);

    void deleteByVolunteerId(UUID volunteerId);
}
