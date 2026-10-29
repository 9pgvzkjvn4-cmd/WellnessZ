package com.wellnessz.e06.model;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AbsenceCertificateCallRepository extends JpaRepository<CertificateCall, UUID> {

    List<CertificateCall> findByCertificateIdInOrderByCalledAtAsc(Collection<UUID> certificateIds);
}
