package com.wellnessz.e04.referral;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e04.E04Events;
import com.wellnessz.e04.model.ProviderContract;
import com.wellnessz.e04.model.ProviderContractRepository;
import com.wellnessz.e04.model.ProviderProfile;
import com.wellnessz.e04.model.ProviderProfileRepository;
import com.wellnessz.e04.model.Referral;
import com.wellnessz.e04.model.ReferralHistory;
import com.wellnessz.e04.model.ServiceDefinition;
import com.wellnessz.e04.model.ServiceDefinitionRepository;
import com.wellnessz.e04.model.TreatmentReferralHistoryRepository;
import com.wellnessz.e04.provider.ContractStatus;
import com.wellnessz.e04.support.TreatmentSpis;
import com.wellnessz.e04.support.TreatmentSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.PartnerOrganisation;

/**
 * Shared referral mechanics: status transitions (history, timeline, {@code referral.status.v1}, SPI listeners) and the
 * OH worklist / detail projection.
 */
@Component
public class ReferralCore {

    private final TreatmentReferralHistoryRepository history;
    private final ServiceDefinitionRepository services;
    private final ProviderProfileRepository profiles;
    private final ProviderContractRepository contracts;
    private final OhCaseRepository cases;
    private final CaseTimeline timeline;
    private final TreatmentSpis spis;
    private final TreatmentSupport support;
    private final EventBus bus;

    public ReferralCore(TreatmentReferralHistoryRepository history, ServiceDefinitionRepository services, ProviderProfileRepository profiles,
                        ProviderContractRepository contracts, OhCaseRepository cases, CaseTimeline timeline, TreatmentSpis spis,
                        TreatmentSupport support, EventBus bus) {
        this.history = history;
        this.services = services;
        this.profiles = profiles;
        this.contracts = contracts;
        this.cases = cases;
        this.timeline = timeline;
        this.spis = spis;
        this.support = support;
        this.bus = bus;
    }

    public record Attachment(UUID documentId, String title) {
    }

    public record HistoryView(String status, Instant at, String by) {
    }

    public record ReferralView(UUID id, String ref, UUID caseId, String caseRef, String employeeId, String displayName, String serviceCode,
                               String serviceName, String providerCode, String providerName, boolean contracted, boolean preferred,
                               String urgency, String status, String channel, Instant createdAt, String createdBy, Instant sentAt,
                               Instant acceptedAt, Instant declinedAt, String declineReason, Instant appointmentAt, String appointmentLocation,
                               String appointmentInstructions, Instant seenAt, Instant reportDueAt, Instant reportReceivedAt,
                               UUID reportDocumentId, boolean overdue, int chaseCount, boolean escalated, Integer rating, String sourceType,
                               String sourceRef,
                               // detail only
                               String reason, String question, Map<String, Object> dataSet, UUID consentId, UUID letterDocumentId,
                               List<Attachment> attachments, String reportSummary, String ratingComment, List<HistoryView> history,
                               Map<String, Object> fhir) {
    }

    /** Lookup maps for projecting many referrals at once. */
    public final class Lookups {
        final Map<String, ServiceDefinition> svc = new HashMap<>();
        final Map<String, PartnerOrganisation> orgs;
        final Map<String, ProviderProfile> prof = new HashMap<>();
        final Map<String, String> contractStatus = new HashMap<>();
        final Map<UUID, OhCase> caseMap = new HashMap<>();
        final Map<String, Employee> emps;

        Lookups(Collection<Referral> list) {
            services.findAll().forEach(s -> svc.put(s.getCode(), s));
            orgs = support.partnersByCode();
            profiles.findAll().forEach(p -> prof.put(p.getCode(), p));
            int days = support.configInt("treatment.contract.expiring-days", 60);
            for (ProviderContract c : contracts.findByCurrentVersionTrue()) {
                contractStatus.put(c.getProviderCode(), ContractStatus.of(c, support.today(), days));
            }
            cases.findAllById(list.stream().map(Referral::getCaseId).distinct().toList()).forEach(c -> caseMap.put(c.getId(), c));
            emps = support.employeesById(list.stream().map(Referral::getEmployeeId).distinct().toList());
        }

        public Optional<OhCase> caseOf(Referral r) {
            return Optional.ofNullable(caseMap.get(r.getCaseId()));
        }

        public String serviceName(String code) {
            ServiceDefinition s = svc.get(code);
            return s == null ? code : s.getName();
        }
    }

    public Lookups lookups(Collection<Referral> list) {
        return new Lookups(list);
    }

    public ServiceDefinition service(String code) {
        return services.findById(code).filter(ServiceDefinition::isActive)
                .orElseThrow(() -> ApiException.badRequest("unknown_service", "Unknown referral service"));
    }

    public List<DataSetValidator.Field> fields(ServiceDefinition s) {
        return DataSetValidator.fields(support.readMaps(s.getRequiredFields()));
    }

    // ------------------------------------------------------------------ transitions

    /** Changes the status, writes history / timeline, publishes the event and informs SPI listeners (inside the transaction). */
    public void transition(Referral r, String status, String actor) {
        Instant now = support.now();
        r.setStatus(status);
        r.setUpdatedAt(now);
        ReferralHistory h = new ReferralHistory();
        h.setId(UUID.randomUUID());
        h.setReferralId(r.getId());
        h.setStatus(status);
        h.setAt(now);
        h.setByActor(actor == null ? "system" : actor);
        history.save(h);
        timeline.record(r.getCaseId(), "REFERRAL_" + status, "Referral " + r.getRef() + " " + status.toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        bus.publish(new E04Events.ReferralStatus(r.getId().toString(), r.getCaseId().toString(), status));
        spis.referralStatusChanged(r.getId(), r.getSourceType(), r.getSourceRef(), status,
                "REPORT_RECEIVED".equals(status) ? r.getReportDocumentId() : null);
    }

    public void requireStatus(Referral r, Collection<String> allowed, String action) {
        if (!allowed.contains(r.getStatus())) {
            throw TreatmentSupport.unprocessable("invalid_transition", "Cannot " + action + " a referral that is " + r.getStatus());
        }
    }

    // ------------------------------------------------------------------ projection

    public List<Attachment> attachments(Referral r) {
        return support.readMaps(r.getAttachments()).stream()
                .map(m -> new Attachment(UUID.fromString(String.valueOf(m.get("documentId"))), String.valueOf(m.get("title")))).toList();
    }

    public ReferralView view(Referral r, Lookups l, boolean detail) {
        PartnerOrganisation org = r.getProviderCode() == null ? null : l.orgs.get(r.getProviderCode());
        ProviderProfile p = r.getProviderCode() == null ? null : l.prof.get(r.getProviderCode());
        String cs = r.getProviderCode() == null ? "NONE" : l.contractStatus.getOrDefault(r.getProviderCode(), "NONE");
        OhCase c = l.caseMap.get(r.getCaseId());
        Employee e = l.emps.get(r.getEmployeeId());
        Map<String, Object> fhir = detail && r.getFhir() != null ? support.readMap(r.getFhir()) : null;
        List<HistoryView> hv = detail ? history.findByReferralIdOrderByAtAsc(r.getId()).stream()
                .map(h -> new HistoryView(h.getStatus(), h.getAt(), h.getByActor())).toList() : null;
        return new ReferralView(r.getId(), r.getRef(), r.getCaseId(), c == null ? null : c.getCaseRef(), r.getEmployeeId(),
                e == null ? r.getEmployeeId() : e.getDisplayName(), r.getServiceCode(), l.serviceName(r.getServiceCode()), r.getProviderCode(),
                org == null ? r.getProviderCode() : org.getName(), ContractStatus.contracted(cs), p != null && p.isPreferred(), r.getUrgency(),
                r.getStatus(), r.getChannel(), r.getCreatedAt(), r.getCreatedBy(), r.getSentAt(), r.getAcceptedAt(), r.getDeclinedAt(),
                r.getDeclineReason(), r.getAppointmentAt(), r.getAppointmentLocation(), r.getAppointmentInstructions(), r.getSeenAt(),
                r.getReportDueAt(), r.getReportReceivedAt(), r.getReportDocumentId(),
                ChaseRules.overdue(r.getStatus(), r.getReportDueAt(), r.getReportReceivedAt(), support.now()), r.getChaseCount(),
                r.isEscalated(), r.getRating(), r.getSourceType(), r.getSourceRef(),
                detail ? r.getReason() : null, detail ? r.getQuestion() : null, detail ? dataSet(r) : null, detail ? r.getConsentId() : null,
                detail ? r.getLetterDocumentId() : null, detail ? attachments(r) : null, detail ? r.getReportSummary() : null,
                detail ? r.getRatingComment() : null, hv, fhir);
    }

    public Map<String, Object> dataSet(Referral r) {
        return r.getDataSet() == null ? new LinkedHashMap<>() : support.readMap(r.getDataSet());
    }

    /** FHIR R4 ServiceRequest of the referral as it stands now. */
    public Map<String, Object> fhir(Referral r) {
        Lookups l = lookups(List.of(r));
        PartnerOrganisation org = r.getProviderCode() == null ? null : l.orgs.get(r.getProviderCode());
        List<UUID> docs = new java.util.ArrayList<>();
        if (r.getLetterDocumentId() != null) {
            docs.add(r.getLetterDocumentId());
        }
        attachments(r).forEach(a -> docs.add(a.documentId()));
        return FhirMapper.serviceRequest(new FhirMapper.Source(r.getId(), r.getRef(), r.getStatus(), r.getUrgency(), r.getServiceCode(),
                l.serviceName(r.getServiceCode()), r.getProviderCode(), org == null ? null : org.getName(), r.getEmployeeId(),
                support.displayName(r.getEmployeeId()), support.staffName(r.getSentBy() == null ? r.getCreatedBy() : r.getSentBy()), r.getReason(),
                r.getQuestion(), dataSet(r), r.getSentAt() == null ? r.getCreatedAt() : r.getSentAt(), r.getAppointmentAt(), docs));
    }
}
