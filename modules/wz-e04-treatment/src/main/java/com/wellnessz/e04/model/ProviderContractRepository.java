package com.wellnessz.e04.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderContractRepository extends JpaRepository<ProviderContract, UUID> {

    List<ProviderContract> findByProviderCodeOrderByVersionDesc(String providerCode);

    List<ProviderContract> findByCurrentVersionTrue();
}
