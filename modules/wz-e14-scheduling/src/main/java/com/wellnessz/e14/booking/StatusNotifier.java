package com.wellnessz.e14.booking;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.support.AppointmentStatusListener;
import com.wellnessz.e14.E14Events;
import com.wellnessz.e14.model.Appointment;
import com.wellnessz.integration.outbox.EventBus;

/**
 * Publishes {@code appointment.status.v1} and calls every {@link AppointmentStatusListener} whose {@code supports(sourceType)}
 * is true, inside the caller's transaction (contract §5.2: every status change).
 */
@Component
public class StatusNotifier {

    /** Resolved lazily: listener modules may themselves depend on {@code AppointmentBooking} (no construction cycle). */
    private final ObjectProvider<AppointmentStatusListener> listeners;
    private final EventBus events;

    public StatusNotifier(ObjectProvider<AppointmentStatusListener> listeners, EventBus events) {
        this.listeners = listeners;
        this.events = events;
    }

    public void changed(Appointment a) {
        events.publish(new E14Events.AppointmentStatusChanged(a.getId().toString(), a.getEmployeeId(), a.getStatus()));
        AppointmentStatusListener.Status status = switch (a.getStatus()) {
            case "BOOKED" -> AppointmentStatusListener.Status.BOOKED;
            case "CHECKED_IN" -> AppointmentStatusListener.Status.CHECKED_IN;
            case "ATTENDED" -> AppointmentStatusListener.Status.ATTENDED;
            case "NO_SHOW" -> AppointmentStatusListener.Status.NO_SHOW;
            case "CANCELLED" -> AppointmentStatusListener.Status.CANCELLED;
            default -> null;
        };
        if (status == null || a.getSourceType() == null) {
            return;
        }
        for (AppointmentStatusListener l : listeners.orderedStream().toList()) {
            if (l.supports(a.getSourceType())) {
                l.appointmentStatusChanged(a.getId(), a.getEmployeeId(), a.getSourceType(), a.getSourceRef(), status);
            }
        }
    }
}
