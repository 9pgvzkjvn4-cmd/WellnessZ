package com.wellnessz.e33.spi;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCaptureSessions;
import com.wellnessz.e33.model.CaptureBatch;
import com.wellnessz.e33.model.CaptureObservation;
import com.wellnessz.e33.model.CaptureSession;
import com.wellnessz.e33.model.E33CaptureBatchRepository;
import com.wellnessz.e33.model.E33CaptureObservationRepository;
import com.wellnessz.e33.model.E33CaptureSessionRepository;
import com.wellnessz.e33.service.CaptureSupport;

/**
 * {@link DeviceCaptureSessions} for the engine's association stage (E31-HLR-05 priority 2): which session listens on a device
 * instance at a time.
 * <ol>
 *   <li>FORM – the session holding the device (LISTENING, or PREVIEW of a sequence such as a full vitals set), measured no
 *       earlier than one minute before it opened and – while listening – not after its expiry;</li>
 *   <li>BATCH – the current queue entry of an open screening batch the device is paired to: the first entry (queue order) that
 *       is still open and has no reading from this device yet. Offline readings replayed in gateway sequence order therefore
 *       bind to the queue in order (E33-F10 reconciliation);</li>
 *   <li>KIOSK – a self-measurement session with a recorded consent on the kiosk's instances, not idle for more than 3 min.</li>
 * </ol>
 */
@Component
public class CaptureSessionsAdapter implements DeviceCaptureSessions {

    private static final Set<String> OPEN = Set.of("LISTENING", "PREVIEW");
    private static final long GRACE_SECONDS = 60;

    private final E33CaptureSessionRepository sessions;
    private final E33CaptureBatchRepository batches;
    private final E33CaptureObservationRepository observations;
    private final CaptureSupport support;

    public CaptureSessionsAdapter(E33CaptureSessionRepository sessions, E33CaptureBatchRepository batches,
                                  E33CaptureObservationRepository observations, CaptureSupport support) {
        this.sessions = sessions;
        this.batches = batches;
        this.observations = observations;
        this.support = support;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionBinding> activeSession(String deviceInstanceId, Instant at) {
        if (deviceInstanceId == null || !support.enabled()) {
            return Optional.empty();
        }
        Instant when = at == null ? support.now() : at;
        Optional<CaptureSession> form = sessions.findByDeviceInstanceIdAndStateIn(deviceInstanceId, OPEN).stream()
                .filter(s -> "FORM".equals(s.getMode()))
                .filter(s -> !when.isBefore(s.getOpenedAt().minusSeconds(GRACE_SECONDS)))
                .filter(s -> "PREVIEW".equals(s.getState()) || !when.isAfter(s.getExpiresAt()))
                .findFirst();
        if (form.isPresent()) {
            return form.map(CaptureSessionsAdapter::binding);
        }
        Optional<CaptureSession> batch = batchEntry(deviceInstanceId);
        if (batch.isPresent()) {
            return batch.map(CaptureSessionsAdapter::binding);
        }
        return sessions.findByModeAndStateIn("KIOSK", OPEN).stream()
                .filter(s -> s.getConsentId() != null)
                .filter(s -> CaptureSupport.csv(s.getKioskInstances()).contains(deviceInstanceId))
                .filter(s -> !support.now().isAfter(s.getExpiresAt()))
                .max(Comparator.comparing(CaptureSession::getLastActivityAt))
                .map(CaptureSessionsAdapter::binding);
    }

    private Optional<CaptureSession> batchEntry(String deviceInstanceId) {
        for (CaptureBatch b : batches.findByStatus("OPEN")) {
            if (!CaptureSupport.csv(b.getDeviceInstanceIds()).contains(deviceInstanceId)) {
                continue;
            }
            List<CaptureSession> queue = sessions.findByBatchIdOrderByQueuePositionAsc(b.getId()).stream()
                    .filter(s -> OPEN.contains(s.getState())).toList();
            if (queue.isEmpty()) {
                continue;
            }
            // sessions that already hold a (not rejected) reading from this device – one query for the whole queue
            Set<UUID> withReading = observations.findBySessionIdIn(queue.stream().map(CaptureSession::getId).toList()).stream()
                    .filter(o -> !"REJECTED".equals(o.getDecision()))
                    .filter(o -> deviceInstanceId.equals(o.getDeviceInstanceId()))
                    .map(CaptureObservation::getSessionId).collect(Collectors.toSet());
            for (CaptureSession s : queue) {
                if (!withReading.contains(s.getId())) {
                    return Optional.of(s);
                }
            }
        }
        return Optional.empty();
    }

    private static SessionBinding binding(CaptureSession s) {
        UUID id = s.getId();
        return new SessionBinding(id, s.getEmployeeId(), s.getCaseId(), s.getOrderRef(), s.getFormCode(), s.getFormVersion(), s.getOperator(),
                s.getSiteCode(), s.getStationCode(), s.getMode(), s.getOpenedAt(), s.getExpiresAt());
    }
}
