package com.wellnessz.security.breakglass;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BreakGlassRepository extends JpaRepository<BreakGlassGrant, UUID> {

    boolean existsByGrantedToAndEmployeeIdAndExpiresAtAfter(String grantedTo, String employeeId, Instant now);

    List<BreakGlassGrant> findByReviewOrderByGrantedAtAsc(BreakGlassGrant.Review review);

    List<BreakGlassGrant> findAllByOrderByGrantedAtDesc();

    List<BreakGlassGrant> findByGrantedAtBetweenOrderByGrantedAtAsc(Instant from, Instant to);
}
