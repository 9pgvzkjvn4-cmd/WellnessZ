package com.wellnessz.e07.support;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.support.AppointmentBooking;

/**
 * Access to the scheduling SPI (implemented by E14). Resolved lazily – the scheduling module itself depends on the
 * appointment-status listeners of this module – and optional: without it invitations are sent "invited, not booked".
 */
@Component
public class Booker {

    private final ObjectProvider<AppointmentBooking> booking;

    public Booker(ObjectProvider<AppointmentBooking> booking) {
        this.booking = booking;
    }

    public boolean available() {
        return booking.orderedStream().findFirst().isPresent();
    }

    public Optional<AppointmentBooking.Booking> book(String actor, AppointmentBooking.BookingRequest request) {
        return booking.orderedStream().findFirst().flatMap(b -> b.bookNext(actor, request));
    }
}
