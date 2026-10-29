package com.wellnessz.e14.capacity;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.support.CapacityDemandSource;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e14.catalogue.CatalogueService;
import com.wellnessz.e14.engine.CapacityMath;
import com.wellnessz.e14.engine.SlotEngine;
import com.wellnessz.e14.model.Appointment;
import com.wellnessz.e14.model.AppointmentRepository;
import com.wellnessz.e14.model.AppointmentType;
import com.wellnessz.e14.support.SchedulingSupport;
import com.wellnessz.masterdata.ClinicSite;

/**
 * E14-F05 capacity planning and utilisation. Capacity comes from the weekly diary templates (session minutes ÷ mean duration of
 * the types offered), demand from the {@link CapacityDemandSource}s (E07 due lists, E06 contact schedules – counts only)
 * plus a three-month run rate of other bookings. Not transactional: each source reads in its own transaction.
 */
@Service
public class CapacityService {

    private static final Logger log = LoggerFactory.getLogger(CapacityService.class);
    public static final int MAX_DAYS = 400;

    private final AppointmentRepository appointments;
    private final CatalogueService catalogue;
    private final ObjectProvider<CapacityDemandSource> sources;
    private final SchedulingSupport support;

    public CapacityService(AppointmentRepository appointments, CatalogueService catalogue, ObjectProvider<CapacityDemandSource> sources,
                           SchedulingSupport support) {
        this.appointments = appointments;
        this.catalogue = catalogue;
        this.sources = sources;
        this.support = support;
    }

    public record CapacityRow(String siteCode, String siteName, String month, int capacityAppointments, int bookedAppointments,
                              int forecastDemand, Map<String, Integer> demandBySource, int gap, double utilisationPct) {
    }

    public record WhatIf(String siteCode, int extraClinicianSessionsPerWeek, int extraCampaignDays) {
    }

    public record UtilisationRow(String key, String label, long sessionsMinutes, long bookedMinutes, double utilisationPct, double noShowRatePct,
                                 double averageWaitMinutes) {
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("invalid_range", "'to' must not be before 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw ApiException.badRequest("range_too_large", "At most " + MAX_DAYS + " days");
        }
    }

    private List<ClinicSite> sites(String siteCode) {
        return siteCode == null ? support.activeSites() : List.of(support.site(siteCode));
    }

    // ------------------------------------------------------------------ capacity

    public List<CapacityRow> capacity(LocalDate from, LocalDate to, String siteCode, WhatIf whatIf) {
        checkRange(from, to);
        Map<String, AppointmentType> types = catalogue.typeMap();
        List<CapacityDemandSource.Demand> demand = new ArrayList<>();
        sources.orderedStream().forEach(s -> {
            try {
                demand.addAll(s.demand(from, to));
            } catch (RuntimeException e) {
                log.warn("Capacity demand source {} failed: {}", s.getClass().getSimpleName(), e.getClass().getSimpleName());
            }
        });
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            months.add(m);
        }
        List<CapacityRow> out = new ArrayList<>();
        for (ClinicSite site : sites(siteCode)) {
            ZoneId zone = ZoneId.of(site.getTimeZone());
            List<SlotEngine.TemplateRow> rows = catalogue.templateRows(site.getCode());
            int runRate = runRate(site.getCode(), zone);
            boolean applyWhatIf = whatIf != null && (whatIf.siteCode() == null || whatIf.siteCode().equals(site.getCode()));
            double siteMean = CapacityMath.meanDuration(rows.stream().flatMap(r -> durations(r, types).stream()).toList());
            int meanSessionMinutes = rows.isEmpty() ? CapacityMath.STANDARD_SESSION_MINUTES
                    : (int) Math.round(rows.stream().mapToLong(r -> Duration.between(r.start(), r.end()).toMinutes()).average().orElse(480));
            for (int i = 0; i < months.size(); i++) {
                YearMonth m = months.get(i);
                LocalDate start = m.atDay(1).isBefore(from) ? from : m.atDay(1);
                LocalDate end = m.atEndOfMonth().isAfter(to) ? to : m.atEndOfMonth();
                int capacity = 0;
                for (SlotEngine.Session s : SlotEngine.expand(rows, start, end)) {
                    capacity += CapacityMath.sessionCapacity(s.minutes(), CapacityMath.meanDuration(durations(s.appointmentTypes(), types)));
                }
                if (applyWhatIf) {
                    int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
                    capacity += CapacityMath.extraSessionCapacity(whatIf.extraClinicianSessionsPerWeek(), meanSessionMinutes, siteMean, days);
                    capacity += CapacityMath.share(whatIf.extraCampaignDays() * CapacityMath.campaignDayCapacity(siteMean), months.size(), i);
                }
                Instant lo = start.atStartOfDay(zone).toInstant();
                Instant hi = end.plusDays(1).atStartOfDay(zone).toInstant();
                int booked = (int) appointments.findBetween(lo, hi, site.getCode()).stream().filter(a -> !"CANCELLED".equals(a.getStatus())).count();
                Map<String, Integer> bySource = new TreeMap<>();
                for (CapacityDemandSource.Demand d : demand) {
                    if (site.getCode().equals(d.siteCode()) && m.equals(d.month())) {
                        bySource.merge(d.source() == null ? "OTHER" : d.source(), d.appointments(), Integer::sum);
                    }
                }
                bySource.put("RUN_RATE", runRate);
                int forecast = bySource.values().stream().mapToInt(Integer::intValue).sum();
                out.add(new CapacityRow(site.getCode(), site.getName(), m.toString(), capacity, booked, forecast, bySource, capacity - forecast,
                        CapacityMath.utilisationPct(booked, capacity)));
            }
        }
        return out;
    }

    private static List<Integer> durations(SlotEngine.TemplateRow r, Map<String, AppointmentType> types) {
        return durations(r.appointmentTypes(), types);
    }

    private static List<Integer> durations(java.util.Set<String> offered, Map<String, AppointmentType> types) {
        return types.values().stream().filter(AppointmentType::isActive)
                .filter(t -> offered == null || offered.isEmpty() || offered.contains(t.getCode()))
                .map(AppointmentType::getDurationMinutes).toList();
    }

    /** Mean monthly count of the previous three complete months' bookings that no demand source accounts for. */
    int runRate(String siteCode, ZoneId zone) {
        YearMonth current = YearMonth.from(support.today(siteCode));
        List<Integer> counts = new ArrayList<>();
        for (int i = 3; i >= 1; i--) {
            YearMonth m = current.minusMonths(i);
            Instant lo = m.atDay(1).atStartOfDay(zone).toInstant();
            Instant hi = m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
            counts.add((int) appointments.findBetween(lo, hi, siteCode).stream()
                    .filter(a -> !"CANCELLED".equals(a.getStatus()))
                    .filter(a -> a.getSourceType() == null || "MANUAL".equals(a.getSourceType())).count());
        }
        return CapacityMath.runRate(counts);
    }

    // ------------------------------------------------------------------ utilisation

    public List<UtilisationRow> utilisation(LocalDate from, LocalDate to, String groupBy) {
        checkRange(from, to);
        Map<String, AppointmentType> types = catalogue.typeMap();
        Map<String, long[]> acc = new LinkedHashMap<>(); // sessionMin, bookedMin, attended, noShow, waitSeconds, called
        Map<String, String> labels = new HashMap<>();
        boolean bySite = !"CLINICIAN".equals(groupBy);
        for (ClinicSite site : support.activeSites()) {
            ZoneId zone = ZoneId.of(site.getTimeZone());
            for (SlotEngine.Session s : SlotEngine.expand(catalogue.templateRows(site.getCode()), from, to)) {
                String key = bySite ? site.getCode() : s.clinicianUsername();
                acc.computeIfAbsent(key, k -> new long[6])[0] += s.minutes();
                labels.putIfAbsent(key, bySite ? site.getName() : support.staffName(key));
            }
            Instant lo = from.atStartOfDay(zone).toInstant();
            Instant hi = to.plusDays(1).atStartOfDay(zone).toInstant();
            for (Appointment a : appointments.findBetween(lo, hi, site.getCode())) {
                String key = bySite ? site.getCode() : a.getClinicianUsername();
                if (key == null || "CANCELLED".equals(a.getStatus())) {
                    continue;
                }
                long[] v = acc.computeIfAbsent(key, k -> new long[6]);
                labels.putIfAbsent(key, bySite ? site.getName() : support.staffName(key));
                AppointmentType t = types.get(a.getAppointmentType());
                v[1] += t == null ? Duration.between(a.getStartsAt(), a.getEndsAt()).toMinutes() : t.getDurationMinutes();
                if ("ATTENDED".equals(a.getStatus())) {
                    v[2]++;
                } else if ("NO_SHOW".equals(a.getStatus())) {
                    v[3]++;
                }
                if (a.getCheckedInAt() != null && a.getCalledAt() != null) {
                    v[4] += Duration.between(a.getCheckedInAt(), a.getCalledAt()).toSeconds();
                    v[5]++;
                }
            }
        }
        List<UtilisationRow> out = new ArrayList<>();
        acc.forEach((k, v) -> out.add(new UtilisationRow(k, labels.getOrDefault(k, k), v[0], v[1],
                CapacityMath.utilisationPct((int) v[1], (int) v[0]), CapacityMath.pct(v[3], v[2] + v[3]),
                v[5] == 0 ? 0.0 : Math.round(v[4] / 6.0 / v[5]) / 10.0)));
        return out;
    }
}
