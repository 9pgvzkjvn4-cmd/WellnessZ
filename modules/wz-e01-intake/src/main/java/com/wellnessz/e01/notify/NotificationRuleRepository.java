package com.wellnessz.e01.notify;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, Long> {

    List<NotificationRule> findByActiveTrue();

    List<NotificationRule> findAllByOrderByEventAscIdAsc();
}
