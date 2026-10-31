package com.wellnessz.e33.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e33.domain.CaptureStateMachine.State;
import com.wellnessz.e33.domain.CaptureStateMachine.Trigger;

class CaptureStateMachineTest {

    @Test
    void happyPathListeningPreviewAccepted() {
        State s = CaptureStateMachine.next(State.LISTENING, Trigger.RESULT);
        assertThat(s).isEqualTo(State.PREVIEW);
        assertThat(CaptureStateMachine.next(s, Trigger.ACCEPT)).isEqualTo(State.ACCEPTED);
        assertThat(State.ACCEPTED.isFinal()).isTrue();
    }

    @Test
    void retestReturnsToListeningAndRejectIsFinal() {
        assertThat(CaptureStateMachine.next(State.PREVIEW, Trigger.RETEST)).isEqualTo(State.LISTENING);
        assertThat(CaptureStateMachine.next(State.PREVIEW, Trigger.REJECT)).isEqualTo(State.REJECTED);
        assertThat(State.REJECTED.isFinal()).isTrue();
    }

    @Test
    void timeoutExpiresWaitingStatesOnly() {
        assertThat(CaptureStateMachine.next(State.LISTENING, Trigger.TIMEOUT)).isEqualTo(State.EXPIRED);
        assertThat(CaptureStateMachine.next(State.FALLBACK_OFFERED, Trigger.TIMEOUT)).isEqualTo(State.EXPIRED);
        assertThat(CaptureStateMachine.allowed(State.PREVIEW, Trigger.TIMEOUT)).isFalse();
        assertThat(State.LISTENING.waiting()).isTrue();
        assertThat(State.PREVIEW.waiting()).isFalse();
    }

    @Test
    void expiredSessionAnswersSessionExpired() {
        assertThatThrownBy(() -> CaptureStateMachine.next(State.EXPIRED, Trigger.ACCEPT))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("session_expired");
    }

    @Test
    void acceptWithoutPreviewIsNotPending() {
        assertThatThrownBy(() -> CaptureStateMachine.next(State.LISTENING, Trigger.ACCEPT))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("observation_not_pending");
        assertThatThrownBy(() -> CaptureStateMachine.next(State.ACCEPTED, Trigger.CANCEL))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("observation_not_pending");
    }

    @Test
    void fallbackChainTransitions() {
        assertThat(CaptureStateMachine.next(State.LISTENING, Trigger.UNAVAILABLE)).isEqualTo(State.FALLBACK_OFFERED);
        assertThat(CaptureStateMachine.next(State.FALLBACK_OFFERED, Trigger.CHOOSE_DEVICE)).isEqualTo(State.LISTENING);
        assertThat(CaptureStateMachine.next(State.FALLBACK_OFFERED, Trigger.CHOOSE_ENTRY)).isEqualTo(State.FALLBACK_ENTRY);
        assertThat(CaptureStateMachine.next(State.FALLBACK_ENTRY, Trigger.SUBMIT_ENTRY)).isEqualTo(State.PREVIEW);
        // a blocked preview may fall back too
        assertThat(CaptureStateMachine.next(State.PREVIEW, Trigger.CHOOSE_ENTRY)).isEqualTo(State.FALLBACK_ENTRY);
        assertThat(CaptureStateMachine.allowed(State.FALLBACK_ENTRY, Trigger.ACCEPT)).isFalse();
    }

    @Test
    void sequenceKeepsPreview() {
        assertThat(CaptureStateMachine.next(State.PREVIEW, Trigger.RESULT)).isEqualTo(State.PREVIEW);
    }

    @Test
    void finalStatesAcceptNothing() {
        for (State s : State.values()) {
            if (s.isFinal()) {
                assertThat(CaptureStateMachine.triggers(s)).isEmpty();
            } else {
                assertThat(CaptureStateMachine.triggers(s)).contains(Trigger.CANCEL);
            }
        }
    }
}
