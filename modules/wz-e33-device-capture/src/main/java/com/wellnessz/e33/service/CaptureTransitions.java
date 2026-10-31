package com.wellnessz.e33.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.wellnessz.e33.domain.CaptureStateMachine;
import com.wellnessz.e33.domain.CaptureStateMachine.State;
import com.wellnessz.e33.domain.CaptureStateMachine.Trigger;
import com.wellnessz.e33.model.CaptureEvent;
import com.wellnessz.e33.model.CaptureSession;
import com.wellnessz.e33.model.E33CaptureEventRepository;
import com.wellnessz.security.audit.AuditService;

/**
 * Applies a state-machine transition to a session: new state, transition log row, audit {@code DEVICE_CAPTURE_<STATE>}
 * (codes only), and the {@code state} event on the live stream after commit.
 */
@Component
public class CaptureTransitions {

    public static final String MACHINE_ACTOR = "device-engine";

    private final E33CaptureEventRepository events;
    private final AuditService audit;
    private final CaptureEventHub hub;
    private final CaptureSupport support;

    public CaptureTransitions(E33CaptureEventRepository events, AuditService audit, CaptureEventHub hub, CaptureSupport support) {
        this.events = events;
        this.audit = audit;
        this.hub = hub;
        this.support = support;
    }

    /** Records the creation of a session (no previous state). */
    public void opened(CaptureSession s, String actor, String detail) {
        events.save(new CaptureEvent(s.getId(), support.now(), actor, null, s.getState(), "OPEN", detail));
        audit.record("DEVICE_CAPTURE_OPEN", "DEVICE_CAPTURE_SESSION", s.getId().toString(), AuditService.Outcome.SUCCESS,
                s.getFormCode() + " " + s.getMode() + " " + s.getState() + (detail == null ? "" : " " + detail));
        hub.publish(s.getId(), stateEvent(s));
    }

    /**
     * @param machine {@code true} for transitions caused by an engine notice or a job (audited as {@code device-engine})
     */
    public State apply(CaptureSession s, Trigger trigger, String actor, String event, String detail, boolean machine) {
        State from = State.valueOf(s.getState());
        State to = CaptureStateMachine.next(from, trigger);
        s.setState(to.name());
        s.setLastActivityAt(support.now());
        if (to.isFinal()) {
            s.setClosedAt(support.now());
        }
        events.save(new CaptureEvent(s.getId(), support.now(), machine ? MACHINE_ACTOR : actor, from.name(), to.name(), event, detail));
        String action = auditAction(trigger, to);
        String reason = s.getFormCode() + " " + from + "->" + to + " " + event + (detail == null ? "" : " " + detail);
        if (machine) {
            audit.recordAs(MACHINE_ACTOR, "MODULE", action, "DEVICE_CAPTURE_SESSION", s.getId().toString(), AuditService.Outcome.SUCCESS, reason);
        } else {
            audit.record(action, "DEVICE_CAPTURE_SESSION", s.getId().toString(), AuditService.Outcome.SUCCESS, reason);
        }
        hub.publish(s.getId(), stateEvent(s), to.isFinal());
        return to;
    }

    /** A progress event that does not change the state (e.g. RECEIVING, an unmatched reading on the device). */
    public void progress(CaptureSession s, String stage, String detail) {
        events.save(new CaptureEvent(s.getId(), support.now(), MACHINE_ACTOR, s.getState(), s.getState(), "PROGRESS_" + stage, detail));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", s.getId().toString());
        data.put("stage", stage);
        if (detail != null) {
            data.put("detail", detail);
        }
        data.put("at", support.now().toString());
        hub.publish(s.getId(), new CaptureEventHub.Event("progress", data));
    }

    /** The {@code preview} event: the preview is ready to be read (ids, status and flag codes only). */
    public void previewReady(CaptureSession s) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", s.getId().toString());
        data.put("observationId", s.getObservationId() == null ? null : s.getObservationId().toString());
        data.put("status", s.getObservationStatus());
        data.put("flags", support.readFlags(s.getFlags()).stream().map(f -> f.outcome() + ":" + f.code()).toList());
        data.put("at", support.now().toString());
        hub.publish(s.getId(), new CaptureEventHub.Event("preview", data));
    }

    public CaptureEventHub.Event stateEvent(CaptureSession s) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", s.getId().toString());
        data.put("state", s.getState());
        if (s.getObservationStatus() != null) {
            data.put("observationStatus", s.getObservationStatus());
        }
        if (s.getPath() != null) {
            data.put("path", s.getPath());
        }
        data.put("at", support.now().toString());
        return new CaptureEventHub.Event("state", data);
    }

    static String auditAction(Trigger trigger, State to) {
        if (trigger == Trigger.CHOOSE_DEVICE || trigger == Trigger.CHOOSE_ENTRY || trigger == Trigger.SUBMIT_ENTRY
                || to == State.FALLBACK_OFFERED || to == State.FALLBACK_ENTRY) {
            return "DEVICE_CAPTURE_FALLBACK";
        }
        if (trigger == Trigger.RETEST) {
            return "DEVICE_CAPTURE_REJECT";
        }
        return switch (to) {
            case ACCEPTED -> "DEVICE_CAPTURE_ACCEPT";
            case REJECTED -> "DEVICE_CAPTURE_REJECT";
            case EXPIRED -> "DEVICE_CAPTURE_EXPIRE";
            case CANCELLED -> "DEVICE_CAPTURE_CANCEL";
            default -> "DEVICE_CAPTURE_" + to.name();
        };
    }
}
