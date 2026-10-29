package com.wellnessz.notification;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.notification.channel.NotificationChannel;

/** Queues and dispatches notifications honouring content rules and quiet hours. */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final NotificationTemplates templates;
    private final QuietHours quietHours;
    private final List<NotificationChannel> channels;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, NotificationTemplates templates, QuietHours quietHours,
                               List<NotificationChannel> channels, Clock clock) {
        this.repository = repository;
        this.templates = templates;
        this.quietHours = quietHours;
        this.channels = channels;
        this.clock = clock;
    }

    @Transactional
    public Notification enqueue(String recipientId, String address, Channel channel, Priority priority, String templateKey,
                                Map<String, String> params, ZoneId recipientZone) {
        String body = templates.render(templateKey, params);
        var when = quietHours.nextAllowed(clock.instant(), recipientZone, priority);
        return repository.save(new Notification(recipientId, address, channel, priority, templateKey, body, when));
    }

    @Scheduled(fixedDelayString = "${wellnessz.notification.poll-interval:PT15S}")
    @Transactional
    public void dispatchDue() {
        for (Notification n : repository.claimDue(clock.instant())) {
            NotificationChannel ch = channels.stream().filter(c -> c.channel() == n.getChannel()).findFirst().orElse(null);
            try {
                if (ch == null) {
                    throw new IllegalStateException("No channel adapter for " + n.getChannel());
                }
                ch.send(n.getAddress(), n.getBody());
                n.sent(clock.instant());
            } catch (Exception e) {
                n.failed(clock.instant().plusSeconds(300));
                log.warn("Notification {} failed via {}: {}", n.getId(), n.getChannel(), e.getClass().getSimpleName());
            }
        }
    }
}
