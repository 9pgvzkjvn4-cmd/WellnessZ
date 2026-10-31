package com.wellnessz.e33.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceObservationGateway;
import com.wellnessz.casekernel.device.DeviceObservationListener.ObservationNotice;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e33.domain.CaptureStateMachine;
import com.wellnessz.e33.domain.CaptureStateMachine.State;
import com.wellnessz.e33.domain.CaptureStateMachine.Trigger;
import com.wellnessz.e33.domain.FallbackChain;
import com.wellnessz.e33.model.CaptureObservation;
import com.wellnessz.e33.model.CaptureSession;
import com.wellnessz.e33.model.E33CaptureObservationRepository;
import com.wellnessz.e33.model.E33CaptureSessionRepository;
import com.wellnessz.e33.model.E33ObservationNoticeRepository;
import com.wellnessz.e33.model.ObservationNoticeRow;

/**
 * Applies the engine's observation notices to capture sessions (E33-HLR-02): a reading ready for verification or blocked by a
 * gate moves the session to PREVIEW (blocked: accept disabled, fall-back offered); an auto-posted reading (kiosk, screening,
 * wearable totals – verify-before-post off) is recorded as AUTO_POSTED and closes a form / batch entry; a dead-lettered
 * reading offers the fall-back chain; an unmatched reading on the device is shown as progress only. Runs in its own
 * transaction after the engine committed; idempotent per observation and status.
 */
@Service
public class CaptureNoticeService {

    private final E33CaptureSessionRepository sessions;
    private final E33CaptureObservationRepository observations;
    private final E33ObservationNoticeRepository notices;
    private final CaptureTransitions transitions;
    private final CaptureSessionService sessionService;
    private final SummaryReleaser releaser;
    private final FormRegistry forms;
    private final DeviceSpis spis;
    private final CaptureSupport support;

    public CaptureNoticeService(E33CaptureSessionRepository sessions, E33CaptureObservationRepository observations,
                                E33ObservationNoticeRepository notices, CaptureTransitions transitions, CaptureSessionService sessionService,
                                SummaryReleaser releaser, FormRegistry forms, DeviceSpis spis, CaptureSupport support) {
        this.sessions = sessions;
        this.observations = observations;
        this.notices = notices;
        this.transitions = transitions;
        this.sessionService = sessionService;
        this.releaser = releaser;
        this.forms = forms;
        this.spis = spis;
        this.support = support;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(ObservationNotice n) {
        if (n == null || n.observationId() == null || n.status() == null) {
            return;
        }
        String category = n.category() == null ? null : n.category().name();
        ObservationNoticeRow row = notices.findById(n.observationId()).orElse(null);
        if (row == null) {
            notices.save(new ObservationNoticeRow(n.observationId(), n.deviceInstanceId(), category, n.sessionId(), n.status(), support.now()));
        } else {
            row.update(n.status(), n.sessionId(), support.now());
        }
        if (n.sessionId() == null) {
            return;
        }
        CaptureSession s = sessions.findById(n.sessionId()).orElse(null);
        if (s == null) {
            return;
        }
        if (n.employeeId() != null && !n.employeeId().equals(s.getEmployeeId())) {
            transitions.progress(s, "EMPLOYEE_MISMATCH", "association_conflict");
            return;
        }
        if ("UNMATCHED".equals(n.status())) {
            transitions.progress(s, "UNMATCHED", "reading not matched to the session");
            return;
        }
        Optional<CaptureObservation> existing = observations.findByObservationId(n.observationId());
        if (existing.isPresent() && n.status().equals(existing.get().getStatus())) {
            return; // repeated notice
        }
        CaptureObservation o = existing.orElseGet(() -> sessionService.newObservation(s, n.observationId()));
        if (n.deviceInstanceId() != null) {
            o.setDeviceInstanceId(n.deviceInstanceId());
        }
        if (category != null) {
            o.setCategory(category);
        }
        o.setStatus(n.status());
        o.setFlags(support.writeFlags(n.flags()));
        observations.save(o);
        State st = State.valueOf(s.getState());
        switch (n.status()) {
            case "PENDING_VERIFICATION", "BLOCKED" -> result(s, st, o, n);
            case "ROUTED" -> routed(s, st, o);
            case "REJECTED" -> engineRejected(s, st, o);
            case "DEAD_LETTER" -> {
                if (CaptureStateMachine.allowed(st, Trigger.UNAVAILABLE) && o.getDecision() == null) {
                    s.setFallbackReason("CONNECTION_FAILED");
                    transitions.apply(s, Trigger.UNAVAILABLE, null, "DEAD_LETTER", "connection_failed", true);
                }
            }
            default -> transitions.progress(s, "RECEIVING", n.status());
        }
    }

    private void result(CaptureSession s, State st, CaptureObservation o, ObservationNotice n) {
        if (o.getDecision() != null || !CaptureStateMachine.allowed(st, Trigger.RESULT)) {
            return;
        }
        s.setObservationId(o.getObservationId());
        s.setObservationStatus(n.status());
        s.setFlags(support.writeFlags(n.flags()));
        if (s.getPath() == null) {
            s.setPath(FallbackChain.DEVICE);
        }
        if ("KIOSK".equals(s.getMode())) {
            s.setExpiresAt(support.now().plusSeconds(KioskService.IDLE_SECONDS));
        }
        transitions.apply(s, Trigger.RESULT, null, "RESULT", n.status(), true);
        transitions.previewReady(s);
    }

    /** Auto-posted (verify-before-post off) or routed after our own accept. */
    private void routed(CaptureSession s, State st, CaptureObservation o) {
        if (o.getDecision() != null) {
            return; // accepted by a clinician in this module – nothing more to do
        }
        o.setDecision("AUTO_POSTED");
        o.setDecidedBy(CaptureTransitions.MACHINE_ACTOR);
        o.setDecidedAt(support.now());
        snapshot(o);
        s.setObservationId(o.getObservationId());
        s.setObservationStatus("ROUTED");
        if (s.getPath() == null) {
            s.setPath(FallbackChain.DEVICE);
        }
        if ("KIOSK".equals(s.getMode())) {
            s.setExpiresAt(support.now().plusSeconds(KioskService.IDLE_SECONDS));
            if (CaptureStateMachine.allowed(st, Trigger.RESULT)) {
                transitions.apply(s, Trigger.RESULT, null, "AUTO_POSTED", null, true);
            }
        } else {
            if (st == State.LISTENING || st == State.FALLBACK_OFFERED || st == State.FALLBACK_ENTRY) {
                st = transitions.apply(s, Trigger.RESULT, null, "AUTO_POSTED", null, true);
            }
            if (st == State.PREVIEW && sessionService.undecided(s).isEmpty()) {
                transitions.apply(s, Trigger.ACCEPT, null, "AUTO_POSTED", null, true);
            }
        }
        releaser.release(forms.rule(s.getFormCode()), s, List.of(o), CaptureTransitions.MACHINE_ACTOR);
    }

    /** The engine rejected an undecided reading (e.g. the target form refused it): the session listens again. */
    private void engineRejected(CaptureSession s, State st, CaptureObservation o) {
        if (o.getDecision() != null) {
            return;
        }
        o.setDecision("REJECTED");
        o.setDecidedBy(CaptureTransitions.MACHINE_ACTOR);
        o.setDecidedAt(support.now());
        if (st == State.PREVIEW && sessionService.undecided(s).isEmpty() && !"KIOSK".equals(s.getMode())) {
            CaptureSessionService.clearPreview(s);
            s.setExpiresAt(support.now().plusSeconds(60L * support.sessionTimeoutMinutes()));
            transitions.apply(s, Trigger.RETEST, null, "ENGINE_REJECTED", null, true);
        }
    }

    private void snapshot(CaptureObservation o) {
        try {
            Optional<DeviceObservationGateway.ObservationPreview> p = spis.gateway().preview(o.getObservationId());
            p.ifPresent(x -> sessionService.fill(o, x.observation()));
        } catch (ApiException e) {
            // engine not deployed: no values to keep
        }
    }
}
