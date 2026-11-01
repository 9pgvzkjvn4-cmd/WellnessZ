package com.wellnessz.e31.cloud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.ModuleRegistry;

/**
 * Scheduler tick of the P6 vendor-cloud poller: every 30 s the due connectors are claimed and polled (each connector keeps its own
 * {@code poll.intervalSeconds}; the tick only bounds the latency of a due poll).
 */
@Component("e31CloudPollJob")
public class CloudPollJob {

    private static final Logger log = LoggerFactory.getLogger(CloudPollJob.class);

    private final CloudPollService poller;
    private final ModuleRegistry modules;

    public CloudPollJob(CloudPollService poller, ModuleRegistry modules) {
        this.poller = poller;
        this.modules = modules;
    }

    @Scheduled(fixedDelayString = "${wellnessz.device.cloud.poll-tick-ms:30000}",
            initialDelayString = "${wellnessz.device.cloud.poll-initial-delay-ms:60000}")
    public void tick() {
        if (!modules.isEnabled("E31")) {
            return;
        }
        try {
            poller.pollDue();
        } catch (RuntimeException e) {
            log.warn("cloud poll tick failed: {}", e.getClass().getSimpleName());
        }
    }
}
