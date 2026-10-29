package com.wellnessz.e04.referral;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e04.model.PlanFollowUp;
import com.wellnessz.e04.model.PlanFollowUpRepository;
import com.wellnessz.e04.model.ProviderInvoice;
import com.wellnessz.e04.model.ProviderInvoiceRepository;
import com.wellnessz.e04.model.Referral;
import com.wellnessz.e04.model.TreatmentReferralRepository;
import com.wellnessz.e04.provider.ScorecardMaths;
import com.wellnessz.e04.support.Money;
import com.wellnessz.e04.support.TreatmentSupport;
import com.wellnessz.security.audit.AuditService;

/** Treatment KPIs (E04-F02 / F06) for the CMO and nursing head – aggregates only, no person-level data. */
@Service
public class TreatmentKpis {

    private final TreatmentReferralRepository referrals;
    private final PlanFollowUpRepository followUps;
    private final ProviderInvoiceRepository invoices;
    private final TreatmentSupport support;
    private final AuditService audit;

    public TreatmentKpis(TreatmentReferralRepository referrals, PlanFollowUpRepository followUps, ProviderInvoiceRepository invoices,
                         TreatmentSupport support, AuditService audit) {
        this.referrals = referrals;
        this.followUps = followUps;
        this.invoices = invoices;
        this.support = support;
        this.audit = audit;
    }

    public record Kpis(LocalDate from, LocalDate to, int referralsSent, BigDecimal medianWorkingDaysReferralToAppointment, BigDecimal urgentWithin48hPct,
                       BigDecimal reportWithin7DaysPct, BigDecimal missedAppointmentPct, Money avgMedicalCostPerCase) {
    }

    @Transactional(readOnly = true)
    public Kpis kpis(LocalDate from, LocalDate to) {
        LocalDate f = from == null ? support.today().minusDays(90) : from;
        LocalDate t = to == null ? support.today() : to;
        if (t.isBefore(f)) {
            throw ApiException.badRequest("invalid_range", "'to' is before 'from'");
        }
        List<Referral> sent = referrals.findBySentAtBetween(f.atStartOfDay().toInstant(ZoneOffset.UTC), t.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        List<Long> days = new ArrayList<>();
        int urgent = 0;
        int urgentOk = 0;
        int seen = 0;
        int within7 = 0;
        for (Referral r : sent) {
            if (r.getAppointmentAt() != null) {
                days.add(ScorecardMaths.workingDays(r.getSentAt(), r.getAppointmentAt()));
            }
            if ("URGENT".equals(r.getUrgency())) {
                urgent++;
                if (r.getAppointmentAt() != null && !r.getAppointmentAt().isAfter(r.getSentAt().plus(Duration.ofHours(48)))) {
                    urgentOk++;
                }
            }
            if (r.getSeenAt() != null) {
                seen++;
                if (r.getReportReceivedAt() != null && !r.getReportReceivedAt().isAfter(r.getSeenAt().plus(Duration.ofDays(7)))) {
                    within7++;
                }
            }
        }
        List<PlanFollowUp> attended = followUps.findByStatusInAndDueDateBetween(Set.of("DONE", "MISSED"), f, t);
        long missed = attended.stream().filter(x -> "MISSED".equals(x.getStatus())).count();
        String base = support.baseCurrency();
        BigDecimal total = BigDecimal.ZERO;
        Set<UUID> cases = new HashSet<>();
        for (ProviderInvoice i : invoices.findByServiceDateBetween(f, t)) {
            if (base.equals(i.getCurrency())) {
                total = total.add(i.getAmount());
                cases.add(i.getCaseId());
            }
        }
        Money avg = cases.isEmpty() ? null : Money.of(total.divide(BigDecimal.valueOf(cases.size()), 2, RoundingMode.HALF_UP), base);
        audit.record("TREATMENT_KPIS_READ", "KPI", null, AuditService.Outcome.SUCCESS, f + ".." + t);
        return new Kpis(f, t, sent.size(), ScorecardMaths.median(days), ScorecardMaths.pct(urgentOk, urgent), ScorecardMaths.pct(within7, seen),
                ScorecardMaths.pct((int) missed, attended.size()), avg);
    }
}
