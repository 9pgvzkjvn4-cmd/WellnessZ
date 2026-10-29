package com.wellnessz.e04.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e04.model.ProviderContract;
import com.wellnessz.e04.model.ProviderContractRepository;
import com.wellnessz.e04.model.ProviderInvoice;
import com.wellnessz.e04.model.ProviderInvoiceRepository;
import com.wellnessz.e04.model.ProviderProfile;
import com.wellnessz.e04.model.ProviderProfileRepository;
import com.wellnessz.e04.model.ProviderScorecard;
import com.wellnessz.e04.model.ProviderScorecardRepository;
import com.wellnessz.e04.model.Referral;
import com.wellnessz.e04.model.ServiceDefinition;
import com.wellnessz.e04.model.ServiceDefinitionRepository;
import com.wellnessz.e04.model.TreatmentOutcome;
import com.wellnessz.e04.model.TreatmentOutcomeRepository;
import com.wellnessz.e04.model.TreatmentReferralRepository;
import com.wellnessz.e04.support.Money;
import com.wellnessz.e04.support.TreatmentSupport;
import com.wellnessz.masterdata.PartnerOrganisation;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Provider network directory & scorecards (E04-F03 / F06): profiles on top of the partner-organisation master, versioned
 * contracts with rate cards and SLAs, network-gap report and monthly provider scorecards (+ CSV review pack).
 */
@Service
public class ProviderDirectoryService {

    static final Set<String> PAYER_TYPES = Set.of("INSURER", "TPA", "AUTHORITY");

    private final ProviderProfileRepository profiles;
    private final ProviderContractRepository contracts;
    private final ProviderScorecardRepository scorecards;
    private final ServiceDefinitionRepository services;
    private final TreatmentReferralRepository referrals;
    private final ProviderInvoiceRepository invoices;
    private final TreatmentOutcomeRepository outcomes;
    private final TreatmentSupport support;
    private final AuditService audit;

    public ProviderDirectoryService(ProviderProfileRepository profiles, ProviderContractRepository contracts, ProviderScorecardRepository scorecards,
                                    ServiceDefinitionRepository services, TreatmentReferralRepository referrals, ProviderInvoiceRepository invoices,
                                    TreatmentOutcomeRepository outcomes, TreatmentSupport support, AuditService audit) {
        this.profiles = profiles;
        this.contracts = contracts;
        this.scorecards = scorecards;
        this.services = services;
        this.referrals = referrals;
        this.invoices = invoices;
        this.outcomes = outcomes;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ commands & views

    public record Accreditation(@NotBlank @Size(max = 100) String body, LocalDate validUntil) {
    }

    public record ProfileCommand(@Size(max = 20) List<@NotBlank @Size(max = 60) String> specialties,
                                 @Size(max = 20) List<@Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String> services,
                                 @Size(max = 15) List<@Pattern(regexp = "[a-z]{2}") String> languages, @Valid @Size(max = 10) List<Accreditation> accreditation,
                                 @Pattern(regexp = "HUB|OUTSTATION|LAYOVER") String coverage, Boolean preferred,
                                 @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude, @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude) {
    }

    public record Rate(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String serviceCode, @NotNull @Valid Money price) {
    }

    public record Sla(@Min(0) @Max(365) Integer appointmentDays, @Min(0) @Max(720) Integer urgentHours, @Min(0) @Max(365) Integer reportDays) {
    }

    public record ContractCommand(@NotBlank @Pattern(regexp = "[A-Za-z0-9_/\\-]{2,40}") String ref, @NotNull LocalDate start, @NotNull LocalDate end,
                                  @Valid @Size(max = 30) List<Rate> rates, @Valid Sla sla) {
    }

    public record ContractView(String ref, String status, LocalDate start, LocalDate end, int version, boolean current, String createdBy,
                               Instant createdAt, List<Rate> rates, Sla sla) {
    }

    public record ScoreSummary(String month, BigDecimal overall, BigDecimal timeliness, BigDecimal reportQuality, BigDecimal outcome, BigDecimal cost) {
    }

    public record ProviderView(String code, String name, String orgType, String city, String countryCode, List<String> specialties,
                               List<String> services, List<String> languages, List<Accreditation> accreditation, String coverage,
                               BigDecimal latitude, BigDecimal longitude, boolean preferred, boolean portalEnabled, boolean active,
                               ContractView contract, List<Rate> rates, Sla sla, ScoreSummary scorecard, List<ContractView> contracts) {
    }

    public record Gap(String serviceCode, String city, String coverage, String reason) {
    }

    public record ScorecardView(String providerCode, String providerName, String month, int referrals, BigDecimal medianDaysToAppointment,
                                BigDecimal reportWithin7DaysPct, BigDecimal avgReportQuality, BigDecimal outcomeImprovedPct, Money avgInvoice,
                                BigDecimal timeliness, BigDecimal reportQuality, BigDecimal outcome, BigDecimal cost, BigDecimal overall) {
    }

    // ------------------------------------------------------------------ directory

    @Transactional(readOnly = true)
    public List<ProviderView> list(CurrentUser user, String serviceCode, String specialty, String city, String countryCode, String contractStatus,
                                   String q, Boolean preferredOnly) {
        Map<String, ProviderProfile> prof = new HashMap<>();
        profiles.findAll().forEach(p -> prof.put(p.getCode(), p));
        Map<String, ProviderContract> current = currentContracts();
        List<ProviderView> out = new ArrayList<>();
        for (PartnerOrganisation o : support.partnersByCode().values()) {
            if (PAYER_TYPES.contains(o.getOrgType())) {
                continue;
            }
            ProviderView v = view(o, prof.get(o.getCode()), current.get(o.getCode()), false);
            String cs = v.contract() == null ? "NONE" : v.contract().status();
            if (serviceCode != null && !v.services().contains(serviceCode)
                    || specialty != null && v.specialties().stream().noneMatch(s -> s.toLowerCase(Locale.ROOT).contains(specialty.toLowerCase(Locale.ROOT)))
                    || city != null && (o.getCity() == null || !o.getCity().equalsIgnoreCase(city))
                    || countryCode != null && !countryCode.equalsIgnoreCase(o.getCountryCode())
                    || contractStatus != null && !contractStatus.equals(cs)
                    || q != null && !(o.getName().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)) || o.getCode().contains(q.toUpperCase(Locale.ROOT)))
                    || Boolean.TRUE.equals(preferredOnly) && !v.preferred()) {
                continue;
            }
            out.add(v);
        }
        out.sort((a, b) -> a.preferred() != b.preferred() ? (a.preferred() ? -1 : 1) : a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    @Transactional(readOnly = true)
    public ProviderView get(CurrentUser user, String code) {
        PartnerOrganisation o = provider(code);
        return view(o, profiles.findById(code).orElse(null), currentContracts().get(code), true);
    }

    private PartnerOrganisation provider(String code) {
        return support.partner(code).filter(o -> !PAYER_TYPES.contains(o.getOrgType())).orElseThrow(() -> ApiException.notFound("Provider"));
    }

    Map<String, ProviderContract> currentContracts() {
        Map<String, ProviderContract> out = new HashMap<>();
        contracts.findByCurrentVersionTrue().forEach(c -> out.put(c.getProviderCode(), c));
        return out;
    }

    ProviderView view(PartnerOrganisation o, ProviderProfile p, ProviderContract c, boolean detail) {
        ContractView cv = c == null ? null : contractView(c);
        ScoreSummary score = scorecards.findFirstByProviderCodeOrderByMonthDesc(o.getCode())
                .map(s -> new ScoreSummary(s.getMonth(), s.getOverall(), s.getTimeliness(), s.getReportQuality(), s.getOutcomeScore(), s.getCostScore()))
                .orElse(null);
        List<ContractView> history = detail ? contracts.findByProviderCodeOrderByVersionDesc(o.getCode()).stream().map(this::contractView).toList() : null;
        List<Accreditation> acc = p == null ? List.of() : support.readMaps(p.getAccreditation()).stream()
                .map(m -> new Accreditation(String.valueOf(m.get("body")), m.get("validUntil") == null ? null : LocalDate.parse(String.valueOf(m.get("validUntil")))))
                .toList();
        return new ProviderView(o.getCode(), o.getName(), o.getOrgType(), o.getCity(), o.getCountryCode(),
                p == null ? List.of() : TreatmentSupport.csv(p.getSpecialties()), p == null ? List.of() : TreatmentSupport.csv(p.getServices()),
                p == null ? List.of() : TreatmentSupport.csv(p.getLanguages()), acc, p == null ? null : p.getCoverage(),
                p == null ? null : p.getLatitude(), p == null ? null : p.getLongitude(), p != null && p.isPreferred(), o.isPortalEnabled(), o.isActive(),
                cv, cv == null ? List.of() : cv.rates(), cv == null ? null : cv.sla(), score, history);
    }

    ContractView contractView(ProviderContract c) {
        List<Rate> rates = support.readMaps(c.getRates()).stream()
                .map(m -> new Rate(String.valueOf(m.get("serviceCode")), new Money(String.valueOf(m.get("amount")), String.valueOf(m.get("currency")))))
                .toList();
        Sla sla = c.getAppointmentDays() == null && c.getUrgentHours() == null && c.getReportDays() == null ? null
                : new Sla(c.getAppointmentDays(), c.getUrgentHours(), c.getReportDays());
        String status = c.isCurrentVersion() ? ContractStatus.of(c, support.today(), support.configInt("treatment.contract.expiring-days", 60)) : "SUPERSEDED";
        return new ContractView(c.getRef(), status, c.getStartDate(), c.getEndDate(), c.getVersion(), c.isCurrentVersion(), c.getCreatedBy(),
                c.getCreatedAt(), rates, sla);
    }

    @Transactional
    public ProviderView updateProfile(CurrentUser user, String code, ProfileCommand cmd) {
        PartnerOrganisation o = provider(code);
        if (cmd.services() != null) {
            for (String s : cmd.services()) {
                services.findById(s).orElseThrow(() -> ApiException.badRequest("unknown_service", "Unknown service " + s));
            }
        }
        ProviderProfile p = profiles.findById(code).orElseGet(() -> {
            ProviderProfile n = new ProviderProfile();
            n.setCode(code);
            return n;
        });
        if (cmd.specialties() != null) {
            p.setSpecialties(TreatmentSupport.joinCsv(cmd.specialties().stream().map(s -> s.strip().replace(",", " ")).toList()));
        }
        if (cmd.services() != null) {
            p.setServices(TreatmentSupport.joinCsv(cmd.services()));
        }
        if (cmd.languages() != null) {
            p.setLanguages(TreatmentSupport.joinCsv(cmd.languages()));
        }
        if (cmd.accreditation() != null) {
            List<Map<String, Object>> acc = new ArrayList<>();
            for (Accreditation a : cmd.accreditation()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("body", a.body().strip());
                m.put("validUntil", a.validUntil() == null ? null : a.validUntil().toString());
                acc.add(m);
            }
            p.setAccreditation(support.write(acc));
        }
        if (cmd.coverage() != null) {
            p.setCoverage(cmd.coverage());
        }
        if (cmd.preferred() != null) {
            p.setPreferred(cmd.preferred());
        }
        if (cmd.latitude() != null) {
            p.setLatitude(cmd.latitude());
        }
        if (cmd.longitude() != null) {
            p.setLongitude(cmd.longitude());
        }
        p.setUpdatedBy(user.username());
        p.setUpdatedAt(support.now());
        profiles.save(p);
        audit.record("PROVIDER_PROFILE_UPDATE", "PROVIDER", code, AuditService.Outcome.SUCCESS, null);
        return view(o, p, currentContracts().get(code), true);
    }

    @Transactional
    public ProviderView addContract(CurrentUser user, String code, ContractCommand cmd) {
        PartnerOrganisation o = provider(code);
        if (cmd.end().isBefore(cmd.start())) {
            throw ApiException.badRequest("invalid_dates", "Contract end before start");
        }
        List<Map<String, Object>> rates = new ArrayList<>();
        for (Rate r : cmd.rates() == null ? List.<Rate>of() : cmd.rates()) {
            services.findById(r.serviceCode()).orElseThrow(() -> ApiException.badRequest("unknown_service", "Unknown service " + r.serviceCode()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("serviceCode", r.serviceCode());
            m.put("amount", r.price().value().toPlainString());
            m.put("currency", r.price().normalisedCurrency());
            rates.add(m);
        }
        List<ProviderContract> existing = contracts.findByProviderCodeOrderByVersionDesc(code);
        existing.forEach(c -> c.setCurrentVersion(false));
        contracts.flush();
        ProviderContract c = new ProviderContract();
        c.setId(UUID.randomUUID());
        c.setProviderCode(code);
        c.setRef(cmd.ref());
        c.setVersion(existing.isEmpty() ? 1 : existing.get(0).getVersion() + 1);
        c.setStartDate(cmd.start());
        c.setEndDate(cmd.end());
        c.setRates(support.write(rates));
        if (cmd.sla() != null) {
            c.setAppointmentDays(cmd.sla().appointmentDays());
            c.setUrgentHours(cmd.sla().urgentHours());
            c.setReportDays(cmd.sla().reportDays());
        }
        c.setCurrentVersion(true);
        c.setCreatedBy(user.username());
        c.setCreatedAt(support.now());
        contracts.save(c);
        audit.record("PROVIDER_CONTRACT_CREATE", "PROVIDER", code, AuditService.Outcome.SUCCESS, "ref=" + cmd.ref() + " version=" + c.getVersion());
        return view(o, profiles.findById(code).orElse(null), c, true);
    }

    // ------------------------------------------------------------------ network gaps

    @Transactional(readOnly = true)
    public List<Gap> gaps(CurrentUser user) {
        Map<String, String> cities = new LinkedHashMap<>();
        for (String entry : TreatmentSupport.csv(support.configString("treatment.network.cities",
                "London:HUB,Dubai:OUTSTATION,New York:LAYOVER,Singapore:LAYOVER,Hong Kong:LAYOVER"))) {
            String[] kv = entry.split(":", 2);
            cities.put(kv[0].trim(), kv.length > 1 ? kv[1].trim() : "HUB");
        }
        Map<String, ProviderContract> current = currentContracts();
        Map<String, ProviderProfile> prof = new HashMap<>();
        profiles.findAll().forEach(p -> prof.put(p.getCode(), p));
        List<PartnerOrganisation> orgs = support.partnersByCode().values().stream().filter(o -> o.isActive() && !PAYER_TYPES.contains(o.getOrgType())).toList();
        int days = support.configInt("treatment.contract.expiring-days", 60);
        List<Gap> out = new ArrayList<>();
        for (ServiceDefinition s : services.findByActiveTrueOrderByCode()) {
            boolean remote = "TELEHEALTH_SPECIALIST".equals(s.getCode());
            for (Map.Entry<String, String> city : cities.entrySet()) {
                List<String> statuses = new ArrayList<>();
                for (PartnerOrganisation o : orgs) {
                    ProviderProfile p = prof.get(o.getCode());
                    boolean offers = p != null && TreatmentSupport.csv(p.getServices()).contains(s.getCode());
                    boolean here = remote || o.getCity() != null && o.getCity().equalsIgnoreCase(city.getKey());
                    if (offers && here) {
                        statuses.add(ContractStatus.of(current.get(o.getCode()), support.today(), days));
                    }
                }
                String reason = null;
                if (statuses.isEmpty()) {
                    reason = "No provider in the network";
                } else if (!statuses.contains("ACTIVE") && !statuses.contains("EXPIRING")) {
                    reason = "No active contract";
                } else if (!statuses.contains("ACTIVE")) {
                    reason = "Contract expiring";
                }
                if (reason != null) {
                    out.add(new Gap(s.getCode(), city.getKey(), city.getValue(), reason));
                }
            }
        }
        audit.record("PROVIDER_NETWORK_GAPS", "PROVIDER", null, AuditService.Outcome.SUCCESS, "gaps=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ scorecards

    @Transactional(readOnly = true)
    public List<ScorecardView> scorecards(CurrentUser user, YearMonth month) {
        Map<String, PartnerOrganisation> orgs = support.partnersByCode();
        List<ScorecardView> out = scorecards.findByMonthOrderByProviderCode(month.toString()).stream().map(s -> new ScorecardView(s.getProviderCode(),
                orgs.containsKey(s.getProviderCode()) ? orgs.get(s.getProviderCode()).getName() : s.getProviderCode(), s.getMonth(), s.getReferrals(),
                s.getMedianDaysToAppointment(), s.getReportWithin7Pct(), s.getAvgReportQuality(), s.getOutcomeImprovedPct(),
                Money.of(s.getAvgInvoice(), s.getCurrency()), s.getTimeliness(), s.getReportQuality(), s.getOutcomeScore(), s.getCostScore(),
                s.getOverall())).toList();
        audit.record("PROVIDER_SCORECARDS_READ", "PROVIDER", month.toString(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    public String scorecardsCsv(CurrentUser user, YearMonth month) {
        StringBuilder sb = new StringBuilder("providerCode,providerName,month,referrals,medianDaysToAppointment,reportWithin7DaysPct,"
                + "avgReportQuality,outcomeImprovedPct,avgInvoice,currency,timeliness,reportQuality,outcome,cost,overall\n");
        for (ScorecardView s : scorecards(user, month)) {
            sb.append(s.providerCode()).append(',').append(csv(s.providerName())).append(',').append(s.month()).append(',').append(s.referrals())
                    .append(',').append(n(s.medianDaysToAppointment())).append(',').append(n(s.reportWithin7DaysPct())).append(',')
                    .append(n(s.avgReportQuality())).append(',').append(n(s.outcomeImprovedPct())).append(',')
                    .append(s.avgInvoice() == null ? "" : s.avgInvoice().amount()).append(',').append(s.avgInvoice() == null ? "" : s.avgInvoice().currency())
                    .append(',').append(n(s.timeliness())).append(',').append(n(s.reportQuality())).append(',').append(n(s.outcome())).append(',')
                    .append(n(s.cost())).append(',').append(n(s.overall())).append('\n');
        }
        return sb.toString();
    }

    private static String n(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static String csv(String v) {
        String s = v == null ? "" : v.replace("\"", "\"\"");
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s + "\"";
    }

    /** Monthly {@code provider-scorecards} job (and on-demand recompute): upserts one row per provider with activity. */
    @Transactional
    public int recompute(YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        String base = support.baseCurrency();
        Map<String, List<Referral>> byProvider = new HashMap<>();
        for (Referral r : referrals.findBySentAtBetween(from, to)) {
            if (r.getProviderCode() != null) {
                byProvider.computeIfAbsent(r.getProviderCode(), k -> new ArrayList<>()).add(r);
            }
        }
        Map<String, List<BigDecimal>> invoiceAmounts = new HashMap<>();
        for (ProviderInvoice i : invoices.findByServiceDateBetween(month.atDay(1), month.atEndOfMonth())) {
            if (base.equals(i.getCurrency())) {
                invoiceAmounts.computeIfAbsent(i.getProviderCode(), k -> new ArrayList<>()).add(i.getAmount());
            }
        }
        Set<String> providers = new java.util.TreeSet<>(byProvider.keySet());
        providers.addAll(invoiceAmounts.keySet());
        Map<String, ProviderContract> current = currentContracts();
        int n = 0;
        for (String code : providers) {
            List<Referral> list = byProvider.getOrDefault(code, List.of());
            List<Long> days = new ArrayList<>();
            int due = 0;
            int within = 0;
            List<Integer> ratings = new ArrayList<>();
            for (Referral r : list) {
                if (r.getAppointmentAt() != null && r.getSentAt() != null) {
                    days.add(ScorecardMaths.workingDays(r.getSentAt(), r.getAppointmentAt()));
                }
                Instant seen = r.getSeenAt() != null ? r.getSeenAt() : r.getAppointmentAt();
                if (r.getSeenAt() != null || r.getReportReceivedAt() != null) {
                    due++;
                    if (r.getReportReceivedAt() != null && seen != null && !r.getReportReceivedAt().isAfter(seen.plus(java.time.Duration.ofDays(7)))) {
                        within++;
                    }
                }
                if (r.getRating() != null) {
                    ratings.add(r.getRating());
                }
            }
            int measured = 0;
            int improved = 0;
            Map<UUID, List<TreatmentOutcome>> byCase = new HashMap<>();
            outcomes.findByCaseIdIn(list.stream().map(Referral::getCaseId).distinct().toList())
                    .forEach(o -> byCase.computeIfAbsent(o.getCaseId(), k -> new ArrayList<>()).add(o));
            for (List<TreatmentOutcome> os : byCase.values()) {
                os.sort((a, b) -> a.getRecordedAt().compareTo(b.getRecordedAt()));
                TreatmentOutcome baseline = os.stream().filter(o -> "BASELINE".equals(o.getMilestone())).findFirst().orElse(null);
                TreatmentOutcome last = os.get(os.size() - 1);
                if (baseline != null && last != baseline) {
                    measured++;
                    if (last.getPainScore() < baseline.getPainScore()) {
                        improved++;
                    }
                }
            }
            ProviderContract c = current.get(code);
            BigDecimal rate = null;
            if (c != null) {
                List<BigDecimal> rs = support.readMaps(c.getRates()).stream().filter(m -> base.equals(m.get("currency")))
                        .map(m -> new BigDecimal(String.valueOf(m.get("amount")))).toList();
                rate = rs.isEmpty() ? null : rs.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(rs.size()), 2, RoundingMode.HALF_UP);
            }
            ScorecardMaths.Result res = ScorecardMaths.compute(new ScorecardMaths.Input(days, due, within, ratings, measured, improved,
                    invoiceAmounts.getOrDefault(code, List.of()), c == null ? null : c.getAppointmentDays(), rate));
            ProviderScorecard s = scorecards.findByProviderCodeAndMonth(code, month.toString()).orElseGet(() -> {
                ProviderScorecard x = new ProviderScorecard();
                x.setId(UUID.randomUUID());
                x.setProviderCode(code);
                x.setMonth(month.toString());
                return x;
            });
            s.setReferrals(list.size());
            s.setMedianDaysToAppointment(res.medianDaysToAppointment());
            s.setReportWithin7Pct(res.reportWithin7DaysPct());
            s.setAvgReportQuality(res.avgReportQuality());
            s.setOutcomeImprovedPct(res.outcomeImprovedPct());
            s.setAvgInvoice(res.avgInvoice());
            s.setCurrency(base);
            s.setTimeliness(res.timeliness());
            s.setReportQuality(res.reportQuality());
            s.setOutcomeScore(res.outcome());
            s.setCostScore(res.cost());
            s.setOverall(res.overall());
            s.setComputedAt(support.now());
            scorecards.save(s);
            n++;
        }
        audit.recordAs("system", "MODULE", "PROVIDER_SCORECARDS_COMPUTED", "PROVIDER", month.toString(), AuditService.Outcome.SUCCESS, "rows=" + n);
        return n;
    }
}
