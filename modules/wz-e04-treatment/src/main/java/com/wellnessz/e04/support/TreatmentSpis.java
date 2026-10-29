package com.wellnessz.e04.support;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.support.AppointmentBooking;
import com.wellnessz.casekernel.support.CaseCostRecorder;
import com.wellnessz.casekernel.support.ClaimContextProvider;
import com.wellnessz.casekernel.support.ReferralStatusListener;

/**
 * Optional kernel SPIs E04 calls into (resolved lazily – the implementing modules may be absent or may themselves depend
 * on E04 SPIs): scheduling bookings (E14), the case cost ledger and claim context (E08) and referral status listeners.
 */
@Component
public class TreatmentSpis {

    private static final Logger log = LoggerFactory.getLogger(TreatmentSpis.class);

    private final ObjectProvider<AppointmentBooking> booking;
    private final ObjectProvider<CaseCostRecorder> costs;
    private final ObjectProvider<ClaimContextProvider> claims;
    private final ObjectProvider<ReferralStatusListener> listeners;

    public TreatmentSpis(ObjectProvider<AppointmentBooking> booking, ObjectProvider<CaseCostRecorder> costs,
                         ObjectProvider<ClaimContextProvider> claims, ObjectProvider<ReferralStatusListener> listeners) {
        this.booking = booking;
        this.costs = costs;
        this.claims = claims;
        this.listeners = listeners;
    }

    public Optional<AppointmentBooking.Booking> book(String actor, AppointmentBooking.BookingRequest request) {
        return booking.orderedStream().findFirst().flatMap(b -> b.bookNext(actor, request));
    }

    public java.util.List<AppointmentBooking.Booking> bookingsFor(String sourceType, String sourceRef) {
        return booking.orderedStream().findFirst().map(b -> b.bookingsFor(sourceType, sourceRef)).orElse(java.util.List.of());
    }

    /** Posts to every cost recorder present (normally the E08 ledger); returns how many recorded the entry. */
    public int recordCost(String actor, CaseCostRecorder.CostEntry entry) {
        int n = 0;
        for (CaseCostRecorder r : costs.orderedStream().toList()) {
            r.record(actor, entry);
            n++;
        }
        if (n == 0) {
            log.debug("No case cost recorder present – {} {} kept case-level in E04 only", entry.sourceType(), entry.kind());
        }
        return n;
    }

    public Optional<ClaimContextProvider.ClaimContext> claimContext(UUID caseId) {
        return claims.orderedStream().findFirst().flatMap(p -> p.forCase(caseId));
    }

    public void referralStatusChanged(UUID referralId, String sourceType, String sourceRef, String status, UUID reportDocumentId) {
        if (sourceType == null) {
            return;
        }
        ReferralStatusListener.Status s = ReferralStatusListener.Status.valueOf(status);
        listeners.orderedStream().filter(l -> l.supports(sourceType))
                .forEach(l -> l.referralStatusChanged(referralId, sourceType, sourceRef, s, reportDocumentId));
    }
}
