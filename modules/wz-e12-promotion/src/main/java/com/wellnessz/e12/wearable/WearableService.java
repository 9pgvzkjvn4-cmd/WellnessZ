package com.wellnessz.e12.wearable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e12.campaign.PromotionCampaignService;
import com.wellnessz.e12.model.Campaign;
import com.wellnessz.e12.model.E12CampaignRepository;
import com.wellnessz.e12.model.E12ParticipationRepository;
import com.wellnessz.e12.model.E12WearableDailyRepository;
import com.wellnessz.e12.model.E12WearableEnrolmentRepository;
import com.wellnessz.e12.model.WearableDaily;
import com.wellnessz.e12.model.WearableEnrolment;
import com.wellnessz.e12.rules.WearableGate;
import com.wellnessz.e12.support.PromotionSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Consented wearable / BYOD programmes (E12-BL-005): an employee enrols with an active {@code WEARABLE_DATA} consent; the
 * internal aggregator may then deliver daily aggregates only (steps, sleep minutes, active minutes) – nothing else is
 * accepted. Withdrawing the enrolment (or the consent) purges every stored value.
 */
@Service
public class WearableService {

    public static final String SOURCE = "WEARABLE";

    private final E12WearableEnrolmentRepository enrolments;
    private final E12WearableDailyRepository daily;
    private final E12ParticipationRepository participation;
    private final E12CampaignRepository campaigns;
    private final PromotionCampaignService campaignService;
    private final ConsentService consents;
    private final PromotionSupport support;
    private final AuditService audit;

    public WearableService(E12WearableEnrolmentRepository enrolments, E12WearableDailyRepository daily, E12ParticipationRepository participation,
                           E12CampaignRepository campaigns, PromotionCampaignService campaignService, ConsentService consents, PromotionSupport support,
                           AuditService audit) {
        this.enrolments = enrolments;
        this.daily = daily;
        this.participation = participation;
        this.campaigns = campaigns;
        this.campaignService = campaignService;
        this.consents = consents;
        this.support = support;
        this.audit = audit;
    }

    public record EnrolCommand(@NotNull UUID campaignId, @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,29}") String deviceType,
                               @NotNull UUID consentId) {
    }

    public record EnrolmentView(UUID id, UUID campaignId, String campaignTitle, String deviceType, UUID consentId, String status,
                                Instant enrolledAt, Instant withdrawnAt, Integer purgedRows) {
    }

    public record DataRow(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeRef, @NotNull UUID campaignId,
                          @NotBlank @Pattern(regexp = "STEPS|SLEEP_MINUTES|ACTIVE_MINUTES") String metric, @NotNull LocalDate date,
                          @NotNull BigDecimal value) {
    }

    public record Rejected(int index, String reason) {
    }

    public record IngestResult(int received, int accepted, List<String> unmatched, List<Rejected> rejected) {
    }

    EnrolmentView view(WearableEnrolment e) {
        return new EnrolmentView(e.getId(), e.getCampaignId(), campaigns.findById(e.getCampaignId()).map(Campaign::getTitle).orElse(null),
                e.getDeviceType(), e.getConsentId(), e.getStatus(), e.getEnrolledAt(), e.getWithdrawnAt(), e.getPurgedRows());
    }

    // ------------------------------------------------------------------ employee self-service

    @Transactional
    public EnrolmentView enrol(CurrentUser user, EnrolCommand cmd) {
        String employeeId = requireEmployee(user);
        Campaign c = campaigns.findById(cmd.campaignId()).orElseThrow(() -> ApiException.notFound("Campaign"));
        if (!"ACTIVE".equals(c.getStatus())) {
            throw PromotionSupport.unprocessable("campaign_not_active", "Wearable data can only be shared for an active campaign");
        }
        if (!consents.isActive(cmd.consentId(), employeeId, ConsentPurpose.WEARABLE_DATA)) {
            audit.record("WEARABLE_ENROL", "CAMPAIGN", c.getId().toString(), AuditService.Outcome.DENIED, "no active WEARABLE_DATA consent");
            throw PromotionSupport.unprocessable("consent_required", "An active WEARABLE_DATA consent is required");
        }
        if (enrolments.findByEmployeeIdAndCampaignIdAndStatus(employeeId, c.getId(), "ACTIVE").isPresent()) {
            throw ApiException.conflict("already_enrolled", "You already share wearable data for this campaign");
        }
        Instant now = support.now();
        WearableEnrolment e = new WearableEnrolment();
        e.setId(UUID.randomUUID());
        e.setEmployeeId(employeeId);
        e.setCampaignId(c.getId());
        e.setDeviceType(cmd.deviceType());
        e.setConsentId(cmd.consentId());
        e.setStatus("ACTIVE");
        e.setEnrolledAt(now);
        enrolments.save(e);
        Employee emp = support.requireEmployee(employeeId);
        campaignService.record(c, emp, "WEARABLE_ENROLLED", now, null, SOURCE, e.getId().toString());
        audit.record("WEARABLE_ENROL", "WEARABLE_ENROLMENT", e.getId().toString(), AuditService.Outcome.SUCCESS, cmd.deviceType());
        return view(e);
    }

    @Transactional
    public EnrolmentView withdraw(CurrentUser user, UUID id) {
        String employeeId = requireEmployee(user);
        WearableEnrolment e = enrolments.findById(id).filter(x -> x.getEmployeeId().equals(employeeId)).orElse(null);
        if (e == null) {
            audit.record("WEARABLE_WITHDRAW", "WEARABLE_ENROLMENT", id.toString(), AuditService.Outcome.DENIED, "not the data subject");
            throw ApiException.notFound("Enrolment");
        }
        if ("ACTIVE".equals(e.getStatus())) {
            purge(e, "EMPLOYEE_WITHDRAWAL");
        }
        return view(e);
    }

    /** Withdraws an enrolment and deletes all wearable values and wearable-derived participation. */
    @Transactional
    public void purge(WearableEnrolment e, String reason) {
        long values = daily.deleteByEnrolmentId(e.getId());
        long parts = participation.deleteByCampaignIdAndEmployeeIdAndSource(e.getCampaignId(), e.getEmployeeId(), SOURCE);
        e.setStatus("WITHDRAWN");
        e.setWithdrawnAt(support.now());
        e.setPurgedRows((int) Math.min(Integer.MAX_VALUE, values + parts));
        audit.recordAs("system", "MODULE", "WEARABLE_PURGE", "WEARABLE_ENROLMENT", e.getId().toString(), AuditService.Outcome.SUCCESS,
                reason + " values=" + values + " participation=" + parts);
    }

    /** Consent withdrawn in the consent centre: every enrolment under that consent is withdrawn and purged. */
    @Transactional
    public int consentWithdrawn(UUID consentId) {
        int n = 0;
        for (WearableEnrolment e : enrolments.findByConsentIdAndStatus(consentId, "ACTIVE")) {
            purge(e, "CONSENT_WITHDRAWN");
            n++;
        }
        return n;
    }

    @Transactional(readOnly = true)
    public List<EnrolmentView> mine(String employeeId) {
        return enrolments.findByEmployeeIdOrderByEnrolledAtDesc(employeeId).stream().map(this::view).toList();
    }

    private static String requireEmployee(CurrentUser user) {
        if (user.employeeId() == null) {
            throw ApiException.forbidden("Only employees can manage wearable enrolments");
        }
        return user.employeeId();
    }

    // ------------------------------------------------------------------ aggregator (internal integration)

    @Transactional
    public IngestResult ingest(CurrentUser client, List<DataRow> rows) {
        PromotionCampaignService.requireInternal(client, "Wearable data is accepted from the internal aggregator only");
        LocalDate today = support.today();
        int maxAge = Math.max(1, support.configInt("promotion.wearables.max-age-days", 31));
        Map<String, WearableEnrolment> enrolmentCache = new HashMap<>();
        Map<UUID, Boolean> consentCache = new HashMap<>();
        List<String> unmatched = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        int accepted = 0;
        Instant now = support.now();
        for (int i = 0; i < rows.size(); i++) {
            DataRow r = rows.get(i);
            Employee emp = support.byRef(r.employeeRef()).orElse(null);
            if (emp == null) {
                unmatched.add(r.employeeRef());
                continue;
            }
            WearableEnrolment e = enrolmentCache.computeIfAbsent(emp.getEmployeeId() + "|" + r.campaignId(),
                    k -> enrolments.findByEmployeeIdAndCampaignIdAndStatus(emp.getEmployeeId(), r.campaignId(), "ACTIVE").orElse(null));
            boolean consent = e != null && consentCache.computeIfAbsent(e.getConsentId(),
                    c -> consents.isActive(c, emp.getEmployeeId(), ConsentPurpose.WEARABLE_DATA));
            String reason = WearableGate.check(e != null, consent, r.metric(), r.date(), r.value(), today, maxAge);
            if (reason != null) {
                rejected.add(new Rejected(i, reason));
                continue;
            }
            WearableDaily d = daily.findByEnrolmentIdAndMetricAndMetricDate(e.getId(), r.metric(), r.date()).orElseGet(() -> {
                WearableDaily n = new WearableDaily();
                n.setId(UUID.randomUUID());
                n.setEnrolmentId(e.getId());
                n.setEmployeeId(e.getEmployeeId());
                n.setCampaignId(e.getCampaignId());
                n.setMetric(r.metric());
                n.setMetricDate(r.date());
                return n;
            });
            d.setValue(r.value().stripTrailingZeros().toPlainString());
            d.setReceivedAt(now);
            daily.save(d);
            accepted++;
        }
        audit.record("WEARABLE_DATA_IMPORT", "WEARABLE_ENROLMENT", null, AuditService.Outcome.SUCCESS,
                "received=" + rows.size() + " accepted=" + accepted + " rejected=" + rejected.size());
        return new IngestResult(rows.size(), accepted, unmatched, rejected);
    }
}
