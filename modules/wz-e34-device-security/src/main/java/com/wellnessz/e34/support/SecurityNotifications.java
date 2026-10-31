package com.wellnessz.e34.support;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wellnessz.notification.Channel;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;

/**
 * Content-free notifications of E34 to Security Operations on duty (approved templates {@code device.security-alert},
 * {@code device.endpoint-isolated}, {@code device.certificate-expiring}). Only the endpoint reference, site, level and due
 * date are used – never a value, payload or person identifier.
 */
@Component
public class SecurityNotifications {

    private static final Logger log = LoggerFactory.getLogger(SecurityNotifications.class);

    private final NotificationService notifications;
    private final E34Settings settings;
    private final String portal;

    public SecurityNotifications(NotificationService notifications, E34Settings settings,
                                 @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String ohWorkspace) {
        this.notifications = notifications;
        this.settings = settings;
        this.portal = ohWorkspace + "/#/security/devices";
    }

    public void alert(String level, String reference, String site) {
        send("device.security-alert", "HIGH".equals(level) || "CRITICAL".equals(level) ? Priority.URGENT : Priority.NORMAL,
                Map.of("level", level, "reference", nz(reference), "site", nz(site)));
    }

    public void isolated(String reference, String site) {
        send("device.endpoint-isolated", Priority.URGENT, Map.of("reference", nz(reference), "site", nz(site)));
    }

    public void certificateExpiring(String reference, LocalDate dueDate) {
        send("device.certificate-expiring", Priority.NORMAL, Map.of("reference", nz(reference), "dueDate", dueDate.toString()));
    }

    private void send(String template, Priority priority, Map<String, String> params) {
        String address = settings.socEmail();
        if (address == null) {
            return;
        }
        Map<String, String> p = new HashMap<>(params);
        p.put("portalLink", portal);
        try {
            notifications.enqueue("security-ops", address, Channel.EMAIL, priority, template, p, ZoneOffset.UTC);
        } catch (RuntimeException e) {
            log.warn("Device security notification {} not queued: {}", template, e.getClass().getSimpleName());
        }
    }

    private static String nz(String v) {
        return v == null ? "-" : v;
    }
}
