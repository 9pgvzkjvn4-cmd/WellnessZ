package com.wellnessz.casekernel.support;

import java.util.UUID;

/**
 * SPI notified by the scheduling module (E14), inside its transaction, when an appointment that another module created
 * (see {@link AppointmentBooking}) is attended, missed or cancelled – e.g. E07 re-invites after a no-show.
 */
public interface AppointmentStatusListener {

    enum Status { BOOKED, CHECKED_IN, ATTENDED, NO_SHOW, CANCELLED }

    /** Called for every status change of an appointment whose {@code sourceType} the listener {@link #supports}. */
    void appointmentStatusChanged(UUID appointmentId, String employeeId, String sourceType, String sourceRef, Status status);

    boolean supports(String sourceType);
}
