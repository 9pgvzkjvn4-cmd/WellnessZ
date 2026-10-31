package com.wellnessz.e32.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.e32.calibration.CalibrationService;
import com.wellnessz.e32.support.E32Support;

/**
 * Consumes {@code device.qc.received.v1} (published in-process by the engine for POCT1-A {@code QCN.R01} and vendor QC
 * exports – PHI-free: instance, check type, lot, expiry, outcome, time) and records it as a QC check (contract §13.6).
 * Never throws: a disabled module or a malformed payload is logged and skipped.
 */
@Component("e32QcEventListener")
public class QcEventListener {

    public static final String EVENT = "device.qc.received.v1";
    private static final Logger log = LoggerFactory.getLogger(QcEventListener.class);

    private final CalibrationService calibration;
    private final E32Support support;

    public QcEventListener(CalibrationService calibration, E32Support support) {
        this.calibration = calibration;
        this.support = support;
    }

    @EventListener
    public void on(IntegrationEnvelope envelope) {
        if (!EVENT.equals(envelope.eventType())) {
            return;
        }
        if (!support.enabled()) {
            log.info("E32 disabled – QC event {} left for later processing", envelope.messageId());
            return;
        }
        try {
            calibration.recordDeviceQc(support.readMap(envelope.payload()));
        } catch (RuntimeException e) {
            log.warn("QC event {} not recorded: {}", envelope.messageId(), e.getClass().getSimpleName());
        }
    }
}
