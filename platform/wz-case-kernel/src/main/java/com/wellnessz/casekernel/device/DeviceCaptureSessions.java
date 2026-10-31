package com.wellnessz.casekernel.device;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Station session binding (E31-HLR-05 priority 2, E33-HLR-01), implemented by capture (E33) and consulted by the engine's
 * association stage (E31): "the nurse selected the employee in the form before capture". Contract sections 9 and 14.1.
 */
public interface DeviceCaptureSessions {

    /**
     * The capture session listening on a device instance at a time (LISTENING / RECEIVING state, not expired), if any.
     * At most one session may listen on a device instance at a time (E33 enforces it – 409 {@code device_busy}).
     */
    Optional<SessionBinding> activeSession(String deviceInstanceId, Instant at);

    /**
     * @param mode FORM (capture dialogue in an OH form) | BATCH (tablet screening queue – current queue entry) | KIOSK
     *             (self-measurement after badge identification and consent)
     */
    record SessionBinding(UUID sessionId, String employeeId, UUID caseId, String orderRef, String formCode, String formVersion,
                          String operator, String siteCode, String stationCode, String mode, Instant openedAt, Instant expiresAt) {
    }

    /** Default when E33 is not deployed: no session binding. */
    DeviceCaptureSessions NONE = (deviceInstanceId, at) -> Optional.empty();
}
