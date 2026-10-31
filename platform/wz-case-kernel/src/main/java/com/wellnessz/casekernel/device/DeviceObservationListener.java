package com.wellnessz.casekernel.device;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Notification from the engine (E31) that an observation reached a state the capture dialogue must show (E33-HLR-02):
 * ready for verification, blocked by a gate, routed, or unmatched. Implemented by E33; called after commit of the
 * engine's transaction. Must be fast and must not throw (the engine logs and continues). Contract section 14.
 */
public interface DeviceObservationListener {

    void observationUpdated(ObservationNotice notice);

    /**
     * @param status PENDING_VERIFICATION | BLOCKED | ROUTED | REJECTED | UNMATCHED | DEAD_LETTER
     */
    record ObservationNotice(UUID observationId, UUID sessionId, String deviceInstanceId, String employeeId, DeviceCodes.Category category,
                             String status, List<DeviceObservation.QualityFlag> flags, Instant at) {

        public ObservationNotice {
            flags = flags == null ? List.of() : List.copyOf(flags);
        }
    }
}
