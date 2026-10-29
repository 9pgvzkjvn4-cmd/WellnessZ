package com.wellnessz.e01.notify;

import java.util.List;

import com.wellnessz.casekernel.model.OhCase;

/**
 * Resolves OH-team recipients (queue distribution lists, case owner, Nursing Head). Implemented by E02, which owns
 * queues and assignment; E01 only knows the matrix.
 */
public interface RecipientResolver {

    record Address(String recipientId, String email) {
    }

    List<Address> resolve(NotificationRule.Recipient recipient, OhCase c);
}
