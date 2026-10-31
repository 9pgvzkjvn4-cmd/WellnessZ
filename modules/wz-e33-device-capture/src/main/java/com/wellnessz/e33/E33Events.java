package com.wellnessz.e33;

import com.wellnessz.common.events.DomainEvent;

/**
 * In-process events of E33 (PHI-free: ids, codes and usernames of staff only – never employee ids or values).
 */
public final class E33Events {

    private E33Events() {
    }

    /**
     * {@code device.capture.session.opened.v1} – a capture session started listening on a device. The engine (E31) reacts by
     * queueing the gateway command named in {@code contextPush} ({@code PUSH_WORKLIST} / {@code START_ACQUISITION}, E31-HLR-08)
     * and reads the employee / order context through {@code DeviceCaptureSessions.activeSession(deviceInstanceId, now)}.
     */
    public record CaptureSessionOpened(String sessionId, String deviceInstanceId, String formCode, String mode, String contextPush,
                                       String siteCode) implements DomainEvent {
        @Override
        public String eventType() {
            return "device.capture.session.opened.v1";
        }

        @Override
        public String aggregateType() {
            return "DEVICE_CAPTURE_SESSION";
        }

        @Override
        public String aggregateId() {
            return sessionId;
        }
    }

    /**
     * {@code device.capture.interpretation-signed.v1} – a physician signed off the device interpretation of an ECG / ABPM /
     * Holter capture (E33-F06). The routed record (E03 result) is named so that the owning module can acknowledge it.
     */
    public record InterpretationSigned(String sessionId, String observationId, String routedRecordType, String routedRecordId,
                                       String signedBy) implements DomainEvent {
        @Override
        public String eventType() {
            return "device.capture.interpretation-signed.v1";
        }

        @Override
        public String aggregateType() {
            return "DEVICE_CAPTURE_SESSION";
        }

        @Override
        public String aggregateId() {
            return sessionId;
        }
    }
}
