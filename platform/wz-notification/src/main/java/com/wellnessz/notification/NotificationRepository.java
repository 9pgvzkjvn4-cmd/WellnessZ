package com.wellnessz.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query(value = """
            select * from platform.notification where status = 'SCHEDULED' and scheduled_at <= :now
            order by scheduled_at limit 100 for update skip locked
            """, nativeQuery = true)
    List<Notification> claimDue(Instant now);
}
