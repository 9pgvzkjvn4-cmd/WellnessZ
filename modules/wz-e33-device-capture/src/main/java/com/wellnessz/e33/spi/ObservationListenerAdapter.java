package com.wellnessz.e33.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceObservationListener;
import com.wellnessz.e33.service.CaptureNoticeService;
import com.wellnessz.e33.service.CaptureSupport;

/**
 * {@link DeviceObservationListener}: the engine (E31) calls it after its commit. Never throws (the engine logs and continues);
 * a disabled module skips the notice. The service is resolved lazily because {@code DeviceSpis} collects the listeners at
 * start-up.
 */
@Component
public class ObservationListenerAdapter implements DeviceObservationListener {

    private static final Logger log = LoggerFactory.getLogger(ObservationListenerAdapter.class);

    private final ObjectProvider<CaptureNoticeService> service;
    private final ObjectProvider<CaptureSupport> support;

    public ObservationListenerAdapter(ObjectProvider<CaptureNoticeService> service, ObjectProvider<CaptureSupport> support) {
        this.service = service;
        this.support = support;
    }

    @Override
    public void observationUpdated(ObservationNotice notice) {
        try {
            if (!support.getObject().enabled()) {
                log.info("E33 disabled: observation notice skipped");
                return;
            }
            service.getObject().handle(notice);
        } catch (RuntimeException e) {
            log.warn("E33 observation notice not applied: {}", e.getClass().getSimpleName());
        }
    }
}
