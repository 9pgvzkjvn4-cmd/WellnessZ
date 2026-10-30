package com.wellnessz.e12.campaign;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e12.E12Events;
import com.wellnessz.e12.model.Campaign;
import com.wellnessz.e12.model.CampaignInvitation;
import com.wellnessz.e12.model.E12CampaignInvitationRepository;
import com.wellnessz.e12.model.E12CampaignRepository;
import com.wellnessz.e12.model.E12ParticipationRepository;
import com.wellnessz.e12.model.Participation;
import com.wellnessz.e12.support.PromotionSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Campaign planner (E12-F01) and campaign-app integration (E12-F04): campaigns linked to anonymised insights, publication to
 * the group's health-campaign application ({@code campaign-app} outbox) with content-free invitations to the targeted staff,
 * and participation flowing back from the app.
 */
@Service
public class PromotionCampaignService {

    public static final String RISK = "MSK|HEARING|METABOLIC|FATIGUE|MENTAL_WELLBEING";
    public static final String STATUS = "PLANNED|ACTIVE|COMPLETED|CANCELLED";
    static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED");

    private final E12CampaignRepository campaigns;
    private final E12CampaignInvitationRepository invitations;
    private final E12ParticipationRepository participation;
    private final PromotionSupport support;
    private final EventBus bus;
    private final AuditService audit;

    public PromotionCampaignService(E12CampaignRepository campaigns, E12CampaignInvitationRepository invitations, E12ParticipationRepository participation,
                           PromotionSupport support, EventBus bus, AuditService audit) {
        this.campaigns = campaigns;
        this.invitations = invitations;
        this.participation = participation;
        this.support = support;
        this.bus = bus;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ DTOs

    public record Money(@NotBlank @Pattern(regexp = "\\d{1,10}(\\.\\d{1,2})?") String amount, @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    public record Kpi(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,59}") String metric, @NotNull BigDecimal target) {
    }

    public record CampaignCommand(@NotBlank @Size(max = 200) String title,
                                  @NotBlank @Pattern(regexp = RISK) String risk,
                                  @NotBlank @Size(max = 2000) String objective,
                                  @NotEmpty @Size(max = 50) List<@NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_\\-]{0,63}") String> targetUnits,
                                  @Pattern(regexp = "[A-Za-z0-9_:\\-]{1,200}") String insightRef,
                                  @Valid Money budget,
                                  @NotNull LocalDate startDate,
                                  @NotNull LocalDate endDate,
                                  @NotEmpty @Size(max = 20) List<@NotBlank @Pattern(regexp = "[A-Za-z0-9 ._'\\-]{1,100}") String> owners,
                                  @Size(max = 20) List<@Valid @NotNull Kpi> kpis,
                                  @Pattern(regexp = STATUS) String status) {
    }

    public record CampaignView(UUID id, String ref, String title, String risk, String objective, List<String> targetUnits, String insightRef,
                               Money budget, LocalDate startDate, LocalDate endDate, List<String> owners, List<Kpi> kpis, String status,
                               Instant publishedAt, String publishedBy, long invited, Instant createdAt, Instant updatedAt) {
    }

    public record PublishResult(CampaignView campaign, int newlyInvited, String messageId) {
    }

    public record ParticipationRow(@NotNull UUID campaignId, @NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeRef,
                                   @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String activity, @NotNull Instant occurredAt,
                                   @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("1000000000") BigDecimal value) {
    }

    public record Rejected(int index, String reason) {
    }

    public record IngestResult(int received, int accepted, int duplicates, List<String> unmatched, List<Rejected> rejected) {
    }

    // ------------------------------------------------------------------ views

    CampaignView view(Campaign c, long invited) {
        Money budget = c.getBudgetAmount() == null ? null : new Money(c.getBudgetAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                c.getBudgetCurrency());
        return new CampaignView(c.getId(), c.getCampaignRef(), c.getTitle(), c.getRisk(), c.getObjective(), PromotionSupport.csv(c.getTargetUnits()),
                c.getInsightRef(), budget, c.getStartDate(), c.getEndDate(), PromotionSupport.csv(c.getOwners()), kpis(c), c.getStatus(),
                c.getPublishedAt(), c.getPublishedBy(), invited, c.getCreatedAt(), c.getUpdatedAt());
    }

    public List<Kpi> kpis(Campaign c) {
        if (c.getKpis() == null || c.getKpis().isBlank()) {
            return List.of();
        }
        return support.read(c.getKpis(), new TypeReference<List<Kpi>>() {
        });
    }

    public Campaign require(UUID id) {
        return campaigns.findById(id).orElseThrow(() -> ApiException.notFound("Campaign"));
    }

    @Transactional(readOnly = true)
    public List<CampaignView> list(String status, String risk) {
        List<Campaign> all = status == null ? campaigns.findAllByOrderByStartDateDesc() : campaigns.findByStatusOrderByStartDateDesc(status);
        return all.stream().filter(c -> risk == null || risk.equals(c.getRisk()))
                .map(c -> view(c, invitations.findByCampaignId(c.getId()).size())).toList();
    }

    @Transactional(readOnly = true)
    public CampaignView get(UUID id) {
        Campaign c = require(id);
        return view(c, invitations.findByCampaignId(id).size());
    }

    // ------------------------------------------------------------------ create / update

    @Transactional
    public CampaignView create(CurrentUser user, CampaignCommand cmd) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setCampaignRef(support.nextRef("CMP", campaigns.nextRef()));
        c.setCreatedBy(user.username());
        c.setCreatedAt(support.now());
        String status = cmd.status() == null ? "PLANNED" : cmd.status();
        if (TERMINAL.contains(status)) {
            throw PromotionSupport.unprocessable("invalid_status", "A new campaign starts as PLANNED or ACTIVE");
        }
        apply(user, c, cmd, status);
        campaigns.save(c);
        audit.record("CAMPAIGN_CREATE", "CAMPAIGN", c.getId().toString(), AuditService.Outcome.SUCCESS, c.getRisk());
        return view(c, 0);
    }

    @Transactional
    public CampaignView update(CurrentUser user, UUID id, CampaignCommand cmd) {
        Campaign c = require(id);
        if (TERMINAL.contains(c.getStatus())) {
            throw ApiException.conflict("campaign_closed", "Completed or cancelled campaigns cannot be changed");
        }
        String status = cmd.status() == null ? c.getStatus() : cmd.status();
        if ("ACTIVE".equals(c.getStatus()) && "PLANNED".equals(status)) {
            throw ApiException.conflict("invalid_transition", "An active campaign cannot return to PLANNED");
        }
        apply(user, c, cmd, status);
        audit.record("CAMPAIGN_UPDATE", "CAMPAIGN", id.toString(), AuditService.Outcome.SUCCESS, "status=" + status);
        return view(c, invitations.findByCampaignId(id).size());
    }

    private void apply(CurrentUser user, Campaign c, CampaignCommand cmd, String status) {
        if (cmd.endDate().isBefore(cmd.startDate())) {
            throw PromotionSupport.unprocessable("invalid_dates", "endDate must not be before startDate");
        }
        Set<String> units = new LinkedHashSet<>(cmd.targetUnits());
        for (String u : units) {
            if (!support.unitExists(u)) {
                throw PromotionSupport.unprocessable("unknown_unit", "Unknown target unit " + u);
            }
        }
        Set<String> metrics = new HashSet<>();
        List<Kpi> kpis = cmd.kpis() == null ? List.of() : cmd.kpis();
        for (Kpi k : kpis) {
            if (!metrics.add(k.metric())) {
                throw PromotionSupport.unprocessable("duplicate_kpi", "Each KPI metric may appear once");
            }
        }
        c.setTitle(PromotionSupport.functional("title", cmd.title(), 200));
        c.setObjective(PromotionSupport.functional("objective", cmd.objective(), 2000));
        c.setRisk(cmd.risk());
        c.setTargetUnits(String.join(",", units));
        c.setInsightRef(cmd.insightRef());
        if (cmd.budget() != null) {
            c.setBudgetAmount(new BigDecimal(cmd.budget().amount()));
            c.setBudgetCurrency(cmd.budget().currency());
        } else {
            c.setBudgetAmount(null);
            c.setBudgetCurrency(null);
        }
        c.setStartDate(cmd.startDate());
        c.setEndDate(cmd.endDate());
        List<String> owners = new ArrayList<>();
        for (String o : cmd.owners()) {
            owners.add(PromotionSupport.functional("owners", o, 100));
        }
        c.setOwners(String.join(",", new LinkedHashSet<>(owners)));
        c.setKpis(support.write(kpis));
        c.setStatus(status);
        c.setUpdatedBy(user.username());
        c.setUpdatedAt(support.now());
    }

    // ------------------------------------------------------------------ publish (campaign app + invitations)

    @Transactional
    public PublishResult publish(CurrentUser user, UUID id) {
        Campaign c = require(id);
        if (TERMINAL.contains(c.getStatus())) {
            throw ApiException.conflict("campaign_closed", "Completed or cancelled campaigns cannot be published");
        }
        if (c.getEndDate().isBefore(support.today())) {
            throw PromotionSupport.unprocessable("campaign_ended", "The campaign end date has passed");
        }
        Instant now = support.now();
        c.setStatus("ACTIVE");
        c.setPublishedAt(now);
        c.setPublishedBy(user.username());
        c.setUpdatedAt(now);
        c.setUpdatedBy(user.username());
        Set<String> already = new HashSet<>();
        invitations.findByCampaignId(id).forEach(i -> already.add(i.getEmployeeId()));
        int invited = 0;
        for (Employee e : support.activeEmployeesIn(PromotionSupport.csv(c.getTargetUnits()))) {
            if (already.contains(e.getEmployeeId())) {
                continue;
            }
            invite(c.getId(), e, now);
            support.notifyEmployee(e.getEmployeeId(), "campaign.invitation", Map.of("reference", c.getTitle()), "/#/campaigns");
            invited++;
        }
        String messageId = UUID.randomUUID().toString();
        bus.publish(new E12Events.CampaignPublished(messageId, c.getId().toString(), c.getTitle(), c.getRisk(), c.getStartDate(), c.getEndDate(),
                c.getTargetUnits()), E12Events.CAMPAIGN_APP);
        audit.record("CAMPAIGN_PUBLISH", "CAMPAIGN", id.toString(), AuditService.Outcome.SUCCESS, "invited=" + invited);
        return new PublishResult(view(c, already.size() + invited), invited, messageId);
    }

    /** Adds an employee to the campaign population (e.g. a screening participant from outside the targeted units). */
    @Transactional
    public void ensureInvited(Campaign c, Employee e) {
        if (!invitations.existsByCampaignIdAndEmployeeId(c.getId(), e.getEmployeeId())) {
            invite(c.getId(), e, support.now());
        }
    }

    CampaignInvitation invite(UUID campaignId, Employee e, Instant now) {
        CampaignInvitation i = new CampaignInvitation();
        i.setId(UUID.randomUUID());
        i.setCampaignId(campaignId);
        i.setEmployeeId(e.getEmployeeId());
        i.setUnitCode(e.getDepartmentCode());
        i.setInvitedAt(now);
        return invitations.save(i);
    }

    // ------------------------------------------------------------------ participation (campaign app → OH)

    /** Imports participation from the internal campaign app; unknown employees are reported, never created. */
    @Transactional
    public IngestResult ingest(CurrentUser client, List<ParticipationRow> rows) {
        requireInternal(client, "Participation is accepted from the internal campaign application only");
        int accepted = 0;
        int duplicates = 0;
        List<String> unmatched = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        Instant now = support.now();
        for (int i = 0; i < rows.size(); i++) {
            ParticipationRow r = rows.get(i);
            Campaign c = campaigns.findById(r.campaignId()).orElse(null);
            if (c == null) {
                rejected.add(new Rejected(i, "UNKNOWN_CAMPAIGN"));
                continue;
            }
            if (!"ACTIVE".equals(c.getStatus()) && !"COMPLETED".equals(c.getStatus())) {
                rejected.add(new Rejected(i, "CAMPAIGN_NOT_ACTIVE"));
                continue;
            }
            if (r.occurredAt().isAfter(now.plusSeconds(300))) {
                rejected.add(new Rejected(i, "FUTURE_TIMESTAMP"));
                continue;
            }
            Employee e = support.byRef(r.employeeRef()).orElse(null);
            if (e == null) {
                unmatched.add(r.employeeRef());
                continue;
            }
            if (record(c, e, r.activity(), r.occurredAt(), r.value(), "APP", null)) {
                accepted++;
            } else {
                duplicates++;
            }
        }
        audit.record("CAMPAIGN_PARTICIPATION_IMPORT", "CAMPAIGN", null, AuditService.Outcome.SUCCESS,
                "received=" + rows.size() + " accepted=" + accepted + " unmatched=" + unmatched.size());
        return new IngestResult(rows.size(), accepted, duplicates, unmatched, rejected);
    }

    /** Records one participation (idempotent); a participant who was not invited is added to the campaign population. */
    @Transactional
    public boolean record(Campaign c, Employee e, String activity, Instant occurredAt, BigDecimal value, String source, String sourceRef) {
        Instant at = occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (participation.existsByCampaignIdAndEmployeeIdAndActivityAndOccurredAt(c.getId(), e.getEmployeeId(), activity, at)) {
            return false;
        }
        if (!invitations.existsByCampaignIdAndEmployeeId(c.getId(), e.getEmployeeId())) {
            invite(c.getId(), e, support.now());
        }
        Participation p = new Participation();
        p.setId(UUID.randomUUID());
        p.setCampaignId(c.getId());
        p.setEmployeeId(e.getEmployeeId());
        p.setUnitCode(e.getDepartmentCode());
        p.setActivity(activity);
        p.setOccurredAt(at);
        p.setValue(value);
        p.setSource(source);
        p.setSourceRef(sourceRef);
        p.setReceivedAt(support.now());
        participation.save(p);
        return true;
    }

    /** Machine clients bound to an external partner organisation are refused (internal systems only). */
    public static void requireInternal(CurrentUser client, String message) {
        if (client.partnerOrganisation() != null || (client.orgScope() != null && client.orgScope().startsWith("/PARTNERS"))) {
            throw ApiException.forbidden(message);
        }
    }
}
