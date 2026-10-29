package com.wellnessz.casekernel;

import com.wellnessz.casekernel.model.CaseEvents;
import com.wellnessz.casekernel.model.OhCase;

/** SPI: notified (inside the transaction) whenever a crew roster-status message is queued, e.g. to log it for reconciliation. */
public interface FitnessPublicationListener {

    void rosterStatusPublished(OhCase c, CaseEvents.RosterStatusChanged message);
}
