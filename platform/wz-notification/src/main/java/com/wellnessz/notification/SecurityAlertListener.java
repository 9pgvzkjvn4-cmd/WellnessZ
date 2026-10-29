package com.wellnessz.notification;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wellnessz.security.breakglass.BreakGlassGranted;

/** Content-free alert to the data protection officer whenever break-glass access is used (E03-BL-021). */
@Component
public class SecurityAlertListener {

    private final NotificationService notifications;
    private final String dpoEmail;
    private final String portal;

    public SecurityAlertListener(NotificationService notifications,
                                 @Value("${wellnessz.notification.dpo-email:dpo@wellnessz.local}") String dpoEmail,
                                 @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String portal) {
        this.notifications = notifications;
        this.dpoEmail = dpoEmail;
        this.portal = portal;
    }

    @EventListener
    public void onBreakGlass(BreakGlassGranted e) {
        notifications.enqueue("DPO", dpoEmail, Channel.EMAIL, Priority.URGENT, "break-glass.alert",
                Map.of("level", "emergency access", "dueDate", DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(e.reviewDueAt()),
                        "portalLink", portal + "/#/dpo/break-glass"),
                ZoneId.of("UTC"));
    }
}
