package com.wellnessz.e10.collection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e10.model.DaTest;
import com.wellnessz.e10.model.E10SpecimenRepository;
import com.wellnessz.e10.model.Specimen;
import com.wellnessz.e10.model.SubstanceEnums.CustodyEvent;
import com.wellnessz.e10.model.SubstanceEnums.SpecimenType;
import com.wellnessz.e10.model.SubstanceEnums.TestStatus;
import com.wellnessz.e10.support.FieldProblem;
import com.wellnessz.e10.support.SubstanceSupport;
import com.wellnessz.e10.support.TestingViews.CustodyView;
import com.wellnessz.e10.support.TestingViews.Requirement;
import com.wellnessz.e10.testing.TestCore;
import com.wellnessz.e10.trigger.TestWindows;
import com.wellnessz.masterdata.PartnerOrganisation;
import com.wellnessz.masterdata.PartnerOrganisationRepository;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Specimen collection with the electronic chain-of-custody form (E10-BL-005): the collector records the specimen, seal,
 * identity check and split; the signed {@code CHAIN_OF_CUSTODY_FORM} is generated in the restricted class; every hand-over is
 * an append-only custody event. Breath readings go straight to the MRO; collectors never see an outcome.
 */
@Service("e10CollectionService")
public class CollectionService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final List<CustodyEvent> SEQUENCE = List.of(CustodyEvent.COLLECTED, CustodyEvent.SEALED, CustodyEvent.COURIER_PICKUP,
            CustodyEvent.LAB_RECEIVED);

    private final TestCore core;
    private final E10SpecimenRepository specimens;
    private final CustodyLog custody;
    private final DocumentService documents;
    private final PartnerOrganisationRepository partners;
    private final SubstanceSupport support;
    private final AuditService audit;

    public CollectionService(TestCore core, E10SpecimenRepository specimens, CustodyLog custody, DocumentService documents,
                             PartnerOrganisationRepository partners, SubstanceSupport support, AuditService audit) {
        this.core = core;
        this.specimens = specimens;
        this.custody = custody;
        this.documents = documents;
        this.partners = partners;
        this.support = support;
        this.audit = audit;
    }

    public record CollectionCommand(String specimenId, String specimenType, Instant collectedAt, String siteCode, Boolean temperatureInRange,
                                    boolean sealIntact, boolean donorIdentityChecked, boolean splitSpecimen, BigDecimal breathScreenResult,
                                    String remarks) {
    }

    @Transactional
    public Requirement collect(CurrentUser user, UUID testId, CollectionCommand c) {
        DaTest t = core.load(testId);
        TestCore.requireStatus(t, List.of(TestStatus.REQUIRED, TestStatus.NOTIFIED, TestStatus.DISPATCHED, TestStatus.COLLECTED, TestStatus.IN_LAB));
        SpecimenType type = SpecimenType.valueOf(c.specimenType());
        boolean breath = type == SpecimenType.BREATH;
        Instant now = support.now();
        if (breath && !TestCore.hasAlcohol(t) || !breath && !TestCore.hasDrugs(t)) {
            throw SubstanceSupport.unprocessable("specimen_not_required", "The test panels do not require a " + type + " specimen");
        }
        if (breath ? t.getAlcoholCollectedAt() != null : t.getCollectedAt() != null) {
            throw ApiException.conflict("already_collected", "This specimen type has already been collected");
        }
        if (specimens.findBySpecimenId(c.specimenId()).isPresent()) {
            throw ApiException.conflict("duplicate_specimen", "Specimen ID already in use");
        }
        if (!c.donorIdentityChecked()) {
            throw new FieldProblem("identity_not_checked", "The donor's identity must be checked", "donorIdentityChecked");
        }
        if (!c.sealIntact()) {
            throw new FieldProblem("seal_not_intact", "A specimen with a broken seal cannot be accepted", "sealIntact");
        }
        if (c.collectedAt().isAfter(now.plusSeconds(300))) {
            throw new FieldProblem("collected_in_future", "collectedAt cannot be in the future", "collectedAt");
        }
        if (breath != (c.breathScreenResult() != null)) {
            throw new FieldProblem("breath_result", breath ? "A breath reading is required" : "Only breath tests carry a reading",
                    "breathScreenResult");
        }
        if (type == SpecimenType.URINE && c.temperatureInRange() == null) {
            throw new FieldProblem("temperature_required", "The urine temperature check is required", "temperatureInRange");
        }
        Instant due = breath ? (t.getAlcoholDueAt() != null ? t.getAlcoholDueAt() : t.getDueAt()) : (t.getDrugDueAt() != null ? t.getDrugDueAt() : t.getDueAt());
        boolean outOfWindow = !TestWindows.inWindow(c.collectedAt(), due);

        Specimen s = Specimen.create();
        s.setSpecimenId(c.specimenId());
        s.setTestId(t.getId());
        s.setSpecimenType(type.name());
        s.setKind("PRIMARY");
        s.setCollectedAt(c.collectedAt());
        s.setSiteCode(c.siteCode());
        s.setTemperatureInRange(c.temperatureInRange());
        s.setSealIntact(true);
        s.setDonorIdentityChecked(true);
        s.setSplitSpecimen(!breath && c.splitSpecimen());
        s.setCollector(user.username());
        s.setRemarks(c.remarks() == null || c.remarks().isBlank() ? null : c.remarks().strip());
        s.setLabCode(breath ? null : support.laboratoryCode());
        s.setOutOfWindow(outOfWindow);
        s.setCreatedAt(now);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("specimenId", c.specimenId());
        fields.put("donorId", t.getEmployeeId());
        fields.put("testReason", t.getReason());
        fields.put("specimenType", type.name() + (s.isSplitSpecimen() ? " (split A/B)" : ""));
        fields.put("collectedAt", STAMP.format(c.collectedAt()));
        fields.put("collectionSite", c.siteCode());
        fields.put("collector", user.displayName() == null ? user.username() : user.displayName());
        fields.put("sealIntact", "Yes");
        fields.put("laboratory", breath ? "Not applicable – evidential breath test at the collection site" : laboratoryName());
        if (s.getRemarks() != null) {
            fields.put("remarks", s.getRemarks());
        }
        DocumentService.DocumentView ccf = documents.generateSigned(user.username(), "CHAIN_OF_CUSTODY_FORM", t.getEmployeeId(), null, fields,
                DataClassification.RESTRICTED_CLINICAL);
        s.setDocumentId(ccf.id());
        specimens.save(s);
        custody.append(s.getSpecimenId(), CustodyEvent.COLLECTED.name(), c.collectedAt(), fields.get("collector"), user.username(), Roles.COLLECTOR, now);

        t.setSiteCode(c.siteCode());
        if (breath) {
            t.setAlcoholCollectedAt(c.collectedAt());
            t.setBreathResult(c.breathScreenResult().stripTrailingZeros().toPlainString());
        } else {
            t.setCollectedAt(c.collectedAt());
        }
        TestStatus status = TestStatus.valueOf(t.getStatus());
        if (breath && !TestCore.hasDrugs(t)) {
            core.transition(t, TestStatus.MRO_REVIEW);
            reviewQueue(t);
        } else if (TestStatus.PENDING_COLLECTION.contains(status)) {
            core.transition(t, TestStatus.COLLECTED);
        } else {
            t.setUpdatedAt(now);
        }
        audit.record("DA_SPECIMEN_COLLECTED", "DA_TEST", t.getId().toString(), AuditService.Outcome.SUCCESS,
                type + (outOfWindow ? " out-of-window" : ""));
        return core.requirement(t, false);
    }

    private String laboratoryName() {
        String code = support.laboratoryCode();
        return partners.findById(code).map(PartnerOrganisation::getName).orElse(code);
    }

    void reviewQueue(DaTest t) {
        support.notifyRole(Roles.MRO, "testing.review-queue", Map.of("reference", t.getRef(), "level", "ROUTINE"), "#/testing/mro", Priority.NORMAL);
    }

    // ------------------------------------------------------------------ custody

    public record CustodyCommand(String event, Instant at, String by) {
    }

    @Transactional
    public List<CustodyView> custody(CurrentUser user, String specimenId, CustodyCommand c) {
        boolean lab = user.hasRole(Roles.INTEGRATION) && !user.hasRole(Roles.COLLECTOR);
        String org = lab ? support.requireLaboratory(user, "DA_CUSTODY_EVENT", specimenId) : null;
        Specimen s = specimens.findBySpecimenId(specimenId).orElse(null);
        if (s == null || lab && !org.equals(s.getLabCode())) {
            if (lab) {
                audit.recordAs(user.username(), "INTEGRATION", "DA_CUSTODY_EVENT", "SPECIMEN", specimenId, AuditService.Outcome.DENIED, "unknown specimen");
            }
            throw ApiException.notFound("Specimen");
        }
        CustodyEvent event = CustodyEvent.valueOf(c.event());
        if (SpecimenType.BREATH.name().equals(s.getSpecimenType())) {
            throw SubstanceSupport.unprocessable("not_applicable", "Breath tests have no laboratory chain of custody");
        }
        if (lab && event != CustodyEvent.LAB_RECEIVED) {
            throw SubstanceSupport.unprocessable("event_not_permitted", "The laboratory records LAB_RECEIVED only");
        }
        Instant now = support.now();
        if (c.at().isAfter(now.plusSeconds(300)) || c.at().isBefore(s.getCollectedAt())) {
            throw new FieldProblem("invalid_time", "The event time must lie between collection and now", "at");
        }
        List<CustodyView> events = custody.events(specimenId);
        if (events.stream().anyMatch(e -> e.event().equals(event.name()))) {
            throw ApiException.conflict("duplicate_event", event + " is already recorded for this specimen");
        }
        int last = events.stream().mapToInt(e -> SEQUENCE.indexOf(CustodyEvent.valueOf(e.event()))).max().orElse(-1);
        if (SEQUENCE.indexOf(event) < last) {
            throw SubstanceSupport.unprocessable("custody_sequence", event + " cannot follow a later hand-over");
        }
        if (!events.isEmpty() && c.at().isBefore(events.get(events.size() - 1).at())) {
            throw new FieldProblem("invalid_time", "Hand-overs must be recorded in time order", "at");
        }
        custody.append(specimenId, event.name(), c.at(), c.by().strip(), user.username(), lab ? "LABORATORY:" + org : Roles.COLLECTOR, now);
        if (event == CustodyEvent.LAB_RECEIVED) {
            s.setLabReceivedAt(c.at());
            DaTest t = core.load(s.getTestId());
            if (TestStatus.COLLECTED.name().equals(t.getStatus())) {
                core.transition(t, TestStatus.IN_LAB);
            }
        }
        if (lab) {
            audit.recordAs(user.username(), "INTEGRATION", "DA_CUSTODY_EVENT", "SPECIMEN", specimenId, AuditService.Outcome.SUCCESS, event.name());
        } else {
            audit.record("DA_CUSTODY_EVENT", "SPECIMEN", specimenId, AuditService.Outcome.SUCCESS, event.name());
        }
        return custody.events(specimenId);
    }

    @Transactional(readOnly = true)
    public List<CustodyView> custodyEvents(String specimenId) {
        if (specimens.findBySpecimenId(specimenId).isEmpty()) {
            throw ApiException.notFound("Specimen");
        }
        audit.record("DA_CUSTODY_READ", "SPECIMEN", specimenId, AuditService.Outcome.SUCCESS, null);
        return custody.events(specimenId);
    }

    // ------------------------------------------------------------------ refusal

    @Transactional
    public Requirement refusal(CurrentUser user, UUID testId, String reason, String observations) {
        DaTest t = core.load(testId);
        TestCore.requireStatus(t, List.copyOf(EnumSet.of(TestStatus.REQUIRED, TestStatus.NOTIFIED, TestStatus.DISPATCHED, TestStatus.COLLECTED)));
        t.setRefusalReason(reason);
        t.setRefusalObservations(observations == null || observations.isBlank() ? null : observations.strip());
        core.transition(t, TestStatus.MRO_REVIEW);
        reviewQueue(t);
        audit.record("DA_REFUSAL_RECORDED", "DA_TEST", t.getId().toString(), AuditService.Outcome.SUCCESS, reason);
        return core.requirement(t, false);
    }
}
