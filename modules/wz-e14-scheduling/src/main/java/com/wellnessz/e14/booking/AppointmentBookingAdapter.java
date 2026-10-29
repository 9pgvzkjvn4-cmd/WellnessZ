package com.wellnessz.e14.booking;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.support.AppointmentBooking;
import com.wellnessz.e14.campaign.CampaignService;
import com.wellnessz.e14.model.Appointment;
import com.wellnessz.e14.model.AppointmentTypeRepository;
import com.wellnessz.e14.model.AppointmentType;
import com.wellnessz.e14.support.SchedulingSupport;

/**
 * Kernel SPI implementation used by E07 (invitations, pre-employment, immunisation) and E06 (absence contacts): books the
 * first free slot of the service at the site (or the employee's home site) on or after {@code earliest}, or the first free
 * campaign slot when a campaign is given. Never throws for "no capacity" – the caller degrades to "invited, not booked".
 */
@Component
public class AppointmentBookingAdapter implements AppointmentBooking {

    private final AppointmentService appointments;
    private final CampaignService campaigns;
    private final AppointmentTypeRepository types;
    private final SchedulingSupport support;

    public AppointmentBookingAdapter(AppointmentService appointments, CampaignService campaigns, AppointmentTypeRepository types,
                                     SchedulingSupport support) {
        this.appointments = appointments;
        this.campaigns = campaigns;
        this.types = types;
        this.support = support;
    }

    @Override
    public Optional<Booking> bookNext(String actor, BookingRequest r) {
        if (r == null || r.employeeId() == null || r.serviceCode() == null
                || types.findById(r.serviceCode()).filter(AppointmentType::isActive).isEmpty()) {
            return Optional.empty();
        }
        if (r.siteCode() != null && support.findSite(r.siteCode()).filter(s -> s.isActive()).isEmpty()) {
            return Optional.empty();
        }
        String sourceType = r.sourceType() == null ? "MANUAL" : r.sourceType();
        Optional<Appointment> booked = r.campaignId() != null
                ? campaigns.bookOne(actor, r.campaignId(), r.employeeId(), r.earliest(), sourceType, r.sourceRef(), r.caseId())
                : appointments.bookFirstFree(actor, r.employeeId(), r.serviceCode(), r.siteCode(), r.earliest(), sourceType, r.sourceRef(), r.caseId());
        return booked.map(AppointmentBookingAdapter::booking);
    }

    @Override
    public List<Booking> bookingsFor(String sourceType, String sourceRef) {
        if (sourceType == null || sourceRef == null) {
            return List.of();
        }
        return appointments.bySource(sourceType, sourceRef).stream().map(AppointmentBookingAdapter::booking).toList();
    }

    static Booking booking(Appointment a) {
        return new Booking(a.getId(), a.getAppointmentType(), a.getSiteCode(), a.getStartsAt(), a.getEndsAt(), a.getStatus());
    }
}
