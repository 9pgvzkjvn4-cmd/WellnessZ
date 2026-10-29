package com.wellnessz.casekernel.support;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI implemented by the scheduling module (E14) so that other modules (E07 surveillance invitations, E06 absence contact
 * schedule) can book clinic appointments without depending on it. With no implementation present, callers degrade to
 * "invited, not booked".
 */
public interface AppointmentBooking {

    /**
     * @param employeeId      employee to book
     * @param serviceCode     appointment type code from the E14 service catalogue (e.g. {@code AUDIOMETRY})
     * @param siteCode        preferred clinic site, or {@code null} for the employee's home site
     * @param earliest        earliest acceptable date
     * @param campaignId      E14 campaign to place the booking in, or {@code null}
     * @param sourceType      originating module object, e.g. {@code SURVEILLANCE_INVITATION}
     * @param sourceRef       originating object id (used to route attendance callbacks)
     * @param caseId          related OH case, if any
     */
    record BookingRequest(String employeeId, String serviceCode, String siteCode, LocalDate earliest, UUID campaignId,
                          String sourceType, String sourceRef, UUID caseId) {
    }

    record Booking(UUID appointmentId, String serviceCode, String siteCode, Instant startsAt, Instant endsAt, String status) {
    }

    /** Books the first free slot that satisfies the request; empty when no capacity is available. */
    Optional<Booking> bookNext(String actor, BookingRequest request);

    /** Current (non-cancelled) bookings created for a source object. */
    List<Booking> bookingsFor(String sourceType, String sourceRef);
}
