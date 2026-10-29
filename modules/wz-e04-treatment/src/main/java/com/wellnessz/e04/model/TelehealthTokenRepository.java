package com.wellnessz.e04.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TelehealthTokenRepository extends JpaRepository<TelehealthToken, UUID> {

    List<TelehealthToken> findBySessionIdAndUsernameAndUsedAtIsNullAndRevokedFalse(UUID sessionId, String username);

    Optional<TelehealthToken> findByTokenHash(String tokenHash);
}
