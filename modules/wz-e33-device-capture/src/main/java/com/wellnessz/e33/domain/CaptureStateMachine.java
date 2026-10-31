package com.wellnessz.e33.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.wellnessz.common.error.ApiException;

/**
 * Capture-session state machine (contract 14.1, E33-HLR-01 / -02 / -10). Pure – no persistence; the session service applies
 * the returned state, writes the transition log and audits {@code DEVICE_CAPTURE_<STATE>}.
 *
 * <pre>
 *  (none) ─open─▶ LISTENING ─result─▶ PREVIEW ─accept─▶ ACCEPTED
 *                   │ │ ▲  re-test        │ └─reject─▶ REJECTED
 *                   │ │ └─────────────────┘
 *                   │ └─timeout─▶ EXPIRED,  cancel ─▶ CANCELLED
 *                   └─unavailable / BLOCKED─▶ FALLBACK_OFFERED ─choose device─▶ LISTENING
 *                                                └─choose entry─▶ FALLBACK_ENTRY ─submit─▶ PREVIEW
 * </pre>
 *
 * A further result in PREVIEW keeps PREVIEW (a sequence – full vitals set, kiosk visit – carries several observations).
 */
public final class CaptureStateMachine {

    public enum State {
        LISTENING, PREVIEW, FALLBACK_OFFERED, FALLBACK_ENTRY, ACCEPTED, REJECTED, EXPIRED, CANCELLED;

        public boolean isFinal() {
            return this == ACCEPTED || this == REJECTED || this == EXPIRED || this == CANCELLED;
        }

        /** States in which the session is still waiting for something from the device or the operator (expiry applies). */
        public boolean waiting() {
            return this == LISTENING || this == FALLBACK_OFFERED || this == FALLBACK_ENTRY;
        }
    }

    public enum Trigger {
        /** a result notice (PENDING_VERIFICATION / BLOCKED / ROUTED) arrived for the session */
        RESULT,
        ACCEPT,
        REJECT,
        /** reject with reason RETEST: the session listens again */
        RETEST,
        TIMEOUT,
        CANCEL,
        /** device unavailable, locked out, failed (dead letter) or the result is blocked */
        UNAVAILABLE,
        /** fall-back to another certified device */
        CHOOSE_DEVICE,
        /** fall-back to image / OCR or manual entry */
        CHOOSE_ENTRY,
        /** the OCR / manual values were submitted into the pipeline */
        SUBMIT_ENTRY
    }

    private static final Map<State, Map<Trigger, State>> TABLE = new EnumMap<>(State.class);

    static {
        on(State.LISTENING, Trigger.RESULT, State.PREVIEW);
        on(State.LISTENING, Trigger.TIMEOUT, State.EXPIRED);
        on(State.LISTENING, Trigger.CANCEL, State.CANCELLED);
        on(State.LISTENING, Trigger.UNAVAILABLE, State.FALLBACK_OFFERED);
        on(State.LISTENING, Trigger.CHOOSE_DEVICE, State.LISTENING);
        on(State.LISTENING, Trigger.CHOOSE_ENTRY, State.FALLBACK_ENTRY);

        on(State.PREVIEW, Trigger.RESULT, State.PREVIEW);
        on(State.PREVIEW, Trigger.ACCEPT, State.ACCEPTED);
        on(State.PREVIEW, Trigger.REJECT, State.REJECTED);
        on(State.PREVIEW, Trigger.RETEST, State.LISTENING);
        on(State.PREVIEW, Trigger.CANCEL, State.CANCELLED);
        on(State.PREVIEW, Trigger.UNAVAILABLE, State.FALLBACK_OFFERED);
        on(State.PREVIEW, Trigger.CHOOSE_DEVICE, State.LISTENING);
        on(State.PREVIEW, Trigger.CHOOSE_ENTRY, State.FALLBACK_ENTRY);

        on(State.FALLBACK_OFFERED, Trigger.CHOOSE_DEVICE, State.LISTENING);
        on(State.FALLBACK_OFFERED, Trigger.CHOOSE_ENTRY, State.FALLBACK_ENTRY);
        on(State.FALLBACK_OFFERED, Trigger.CANCEL, State.CANCELLED);
        on(State.FALLBACK_OFFERED, Trigger.TIMEOUT, State.EXPIRED);
        on(State.FALLBACK_OFFERED, Trigger.RESULT, State.PREVIEW);

        on(State.FALLBACK_ENTRY, Trigger.SUBMIT_ENTRY, State.PREVIEW);
        on(State.FALLBACK_ENTRY, Trigger.RESULT, State.PREVIEW);
        on(State.FALLBACK_ENTRY, Trigger.CANCEL, State.CANCELLED);
        on(State.FALLBACK_ENTRY, Trigger.TIMEOUT, State.EXPIRED);
    }

    private CaptureStateMachine() {
    }

    private static void on(State from, Trigger t, State to) {
        TABLE.computeIfAbsent(from, k -> new EnumMap<>(Trigger.class)).put(t, to);
    }

    /** {@code true} when the trigger is allowed in the state. */
    public static boolean allowed(State from, Trigger trigger) {
        return TABLE.getOrDefault(from, Map.of()).containsKey(trigger);
    }

    /** The triggers the state accepts (drives the buttons the capture dialogue enables). */
    public static Set<Trigger> triggers(State from) {
        Map<Trigger, State> m = TABLE.get(from);
        return m == null || m.isEmpty() ? EnumSet.noneOf(Trigger.class) : EnumSet.copyOf(m.keySet());
    }

    /**
     * Next state, or 409: {@code session_expired} for an expired session, {@code observation_not_pending} for any other
     * transition that the current state does not allow.
     */
    public static State next(State from, Trigger trigger) {
        State to = TABLE.getOrDefault(from, Map.of()).get(trigger);
        if (to != null) {
            return to;
        }
        if (from == State.EXPIRED) {
            throw new ApiException(HttpStatus.CONFLICT, "session_expired", "The capture session has expired – open a new one");
        }
        throw new ApiException(HttpStatus.CONFLICT, "observation_not_pending",
                "The capture session is " + from + " and cannot " + trigger.name().toLowerCase().replace('_', ' '));
    }
}
