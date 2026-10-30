package com.wellnessz.e09.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E09UrgentItemRepository extends JpaRepository<UrgentItem, UUID> {

    List<UrgentItem> findByStatusOrderByCallBackByAsc(String status);

    List<UrgentItem> findBySupportCaseIdAndStatus(UUID supportCaseId, String status);
}
