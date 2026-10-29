package com.wellnessz.e07.due;

/**
 * What to do when a surveillance invitation is ignored (appointment missed, or never booked within the response window):
 * re-invite until {@code surveillance.max-invitations} (default 2) invitations have been sent, then ask the line manager to
 * support attendance (status only) and flag the enrolment as escalated – once.
 */
public final class NoShowPolicy {

    public enum Action { REINVITE, ESCALATE, NONE }

    private NoShowPolicy() {
    }

    /**
     * @param invitationsSent invitations already sent for the current due period (including the ignored one)
     * @param maxInvitations  configured maximum
     * @param alreadyEscalated whether the manager was already asked
     */
    public static Action onIgnored(int invitationsSent, int maxInvitations, boolean alreadyEscalated) {
        if (alreadyEscalated) {
            return Action.NONE;
        }
        return invitationsSent < Math.max(1, maxInvitations) ? Action.REINVITE : Action.ESCALATE;
    }
}
