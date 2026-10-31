package com.wellnessz.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.gateway.forward.Forwarder;
import com.wellnessz.gateway.ops.HeartbeatService;
import com.wellnessz.gateway.ops.RuntimeSettings;
import com.wellnessz.gateway.queue.EncryptedJournal;

/** Periodic work: drain the queue, heartbeat, retention expiry. Nothing runs before the runtime is ready. */
@Component
public class GatewayScheduler {

    private static final Logger log = LoggerFactory.getLogger(GatewayScheduler.class);

    private final GatewayRuntime runtime;
    private final Forwarder forwarder;
    private final HeartbeatService heartbeat;
    private final EncryptedJournal journal;
    private final RuntimeSettings settings;

    public GatewayScheduler(GatewayRuntime runtime, Forwarder forwarder, HeartbeatService heartbeat,
                            EncryptedJournal journal, RuntimeSettings settings) {
        this.runtime = runtime;
        this.forwarder = forwarder;
        this.heartbeat = heartbeat;
        this.journal = journal;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${wz.gateway.forwarder.poll-interval:PT1S}", initialDelay = 500)
    public void forward() {
        if (!runtime.ready()) {
            return;
        }
        try {
            forwarder.drain();
        } catch (RuntimeException e) {
            log.error("forwarding cycle failed: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 1000)
    public void heartbeat() {
        if (!runtime.ready()) {
            return;
        }
        try {
            heartbeat.tick();
        } catch (RuntimeException e) {
            log.error("heartbeat failed: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void expire() {
        if (!runtime.ready()) {
            return;
        }
        int n = journal.expire(settings.retention());
        if (n > 0) {
            log.warn("{} queue items exceeded the retention window and were dead-lettered", n);
        }
    }
}
