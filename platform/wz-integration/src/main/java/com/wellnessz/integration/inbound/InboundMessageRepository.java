package com.wellnessz.integration.inbound;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundMessageRepository extends JpaRepository<InboundMessage, UUID> {
}
