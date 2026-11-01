package com.wellnessz.e03.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LabOrderRepository extends JpaRepository<LabOrder, UUID> {
    List<LabOrder> findByEmployeeIdOrderByOrderedAtDesc(String employeeId);

    List<LabOrder> findByStatusOrderByOrderedAtAsc(String status);

    Optional<LabOrder> findByPlacerOrderNumber(String placerOrderNumber);

    /** Open orders of the given categories (device work-list, E31 association priority 1). */
    @org.springframework.data.jpa.repository.Query("""
            select o from LabOrder o where o.category in ?1 and o.status not in ('RESULTED','CANCELLED') and o.orderedAt < ?2 order by o.orderedAt""")
    List<LabOrder> findOpenByCategory(java.util.Collection<String> categories, java.time.Instant orderedBefore);
}
