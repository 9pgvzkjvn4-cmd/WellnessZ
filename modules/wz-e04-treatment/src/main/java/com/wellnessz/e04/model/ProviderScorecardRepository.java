package com.wellnessz.e04.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderScorecardRepository extends JpaRepository<ProviderScorecard, UUID> {

    List<ProviderScorecard> findByMonthOrderByProviderCode(String month);

    Optional<ProviderScorecard> findByProviderCodeAndMonth(String providerCode, String month);

    Optional<ProviderScorecard> findFirstByProviderCodeOrderByMonthDesc(String providerCode);
}
