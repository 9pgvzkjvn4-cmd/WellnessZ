package com.wellnessz.e10.collection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e10.model.DaTest;
import com.wellnessz.e10.model.E10SpecimenRepository;
import com.wellnessz.e10.model.Specimen;
import com.wellnessz.e10.model.SubstanceEnums.CustodyEvent;
import com.wellnessz.e10.model.SubstanceEnums.Panel;
import com.wellnessz.e10.model.SubstanceEnums.SpecimenType;
import com.wellnessz.e10.model.SubstanceEnums.TestStatus;
import com.wellnessz.e10.model.SubstanceEnums.Validity;
import com.wellnessz.e10.support.FieldProblem;
import com.wellnessz.e10.support.SubstanceSupport;
import com.wellnessz.e10.support.TestingViews.Analyte;
import com.wellnessz.e10.support.TestingViews.LabAck;
import com.wellnessz.e10.support.TestingViews.LabOrder;
import com.wellnessz.e10.support.TestingViews.LabResult;
import com.wellnessz.e10.testing.TestCore;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Laboratory interface (E10-BL-006) for the accredited laboratory's machine client – bound to the laboratory organisation
 * ({@code org_scope /PARTNERS/<substance.laboratory-code>}): expected specimens (orders, incl. split-specimen orders) and the
 * result upload that moves a test to MRO review. Orders carry no donor identity.
 */
@Service("e10LabService")
public class LabService {

    private static final TypeReference<List<Analyte>> ANALYTES = new TypeReference<>() {
    };

    private final E10SpecimenRepository specimens;
    private final TestCore core;
    private final CustodyLog custody;
    private final CollectionService collection;
    private final SubstanceSupport support;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public LabService(E10SpecimenRepository specimens, TestCore core, CustodyLog custody, CollectionService collection, SubstanceSupport support,
                      JdbcTemplate jdbc, AuditService audit) {
        this.specimens = specimens;
        this.core = core;
        this.custody = custody;
        this.collection = collection;
        this.support = support;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<LabOrder> orders(CurrentUser client) {
        String org = support.requireLaboratory(client, "DA_LAB_ORDERS_READ", null);
        List<LabOrder> out = new ArrayList<>();
        for (Specimen s : specimens.findByLabCodeAndResultedAtIsNullOrderByCollectedAtAsc(org)) {
            if (SpecimenType.BREATH.name().equals(s.getSpecimenType())) {
                continue;
            }
            DaTest t = core.load(s.getTestId());
            boolean split = "SPLIT".equals(s.getKind());
            if (!split && !List.of(TestStatus.COLLECTED.name(), TestStatus.IN_LAB.name()).contains(t.getStatus())) {
                continue;
            }
            List<String> panels = SubstanceSupport.split(t.getPanels()).stream().filter(p -> !Panel.valueOf(p).alcohol()).toList();
            List<Analyte> analytes = new ArrayList<>();
            panels.forEach(p -> analytes.addAll(analytes(p)));
            out.add(new LabOrder(s.getSpecimenId(), split ? "SPLIT" : "PRIMARY", s.getSpecimenType(), panels, analytes, s.getCollectedAt(),
                    s.getSiteCode(), s.isSplitSpecimen(), custody.events(s.getSpecimenId())));
        }
        audit.recordAs(client.username(), "INTEGRATION", "DA_LAB_ORDERS_READ", "LAB_INTERFACE", org, AuditService.Outcome.SUCCESS, "orders=" + out.size());
        return out;
    }

    private List<Analyte> analytes(String panel) {
        String json = jdbc.queryForObject("select analytes from substance.panel where code = ?", String.class, panel);
        return support.read(json, ANALYTES);
    }

    public record ResultCommand(String specimenId, String labRef, Instant receivedAt, String validity, List<LabResult> results) {
    }

    @Transactional
    public LabAck results(CurrentUser client, ResultCommand c) {
        String org = support.requireLaboratory(client, "DA_LAB_RESULT", c.specimenId());
        Specimen s = specimens.findBySpecimenId(c.specimenId()).orElse(null);
        if (s == null || !org.equals(s.getLabCode())) {
            audit.recordAs(client.username(), "INTEGRATION", "DA_LAB_RESULT", "SPECIMEN", c.specimenId(), AuditService.Outcome.DENIED,
                    "specimen not ordered from " + org);
            throw ApiException.notFound("Specimen");
        }
        if (s.getResultedAt() != null) {
            throw ApiException.conflict("already_resulted", "Results for this specimen have already been received");
        }
        Validity validity = Validity.valueOf(c.validity());
        List<LabResult> results = c.results() == null ? List.of() : c.results();
        if ((validity == Validity.VALID || validity == Validity.DILUTE) && results.isEmpty()) {
            throw new FieldProblem("results_required", "Analyte results are required for a valid specimen", "results");
        }
        if (c.receivedAt().isAfter(support.now().plusSeconds(300))) {
            throw new FieldProblem("invalid_time", "receivedAt cannot be in the future", "receivedAt");
        }
        DaTest t = core.load(s.getTestId());
        Instant now = support.now();
        boolean split = "SPLIT".equals(s.getKind());
        if (split) {
            t.setSplitResults(support.json(Map.of("labRef", c.labRef(), "validity", validity.name(), "receivedAt", c.receivedAt().toString(),
                    "results", results)));
            if (TestStatus.VERIFIED_POSITIVE.name().equals(t.getStatus())) {
                core.transition(t, TestStatus.MRO_REVIEW); // reconfirmation pending – duty status stays REMOVED
            }
        } else {
            TestCore.requireStatus(t, List.of(TestStatus.COLLECTED, TestStatus.IN_LAB));
            t.setLabRef(c.labRef());
            t.setLabValidity(validity.name());
            t.setLabReceivedAt(c.receivedAt());
            t.setResults(support.json(results));
            core.transition(t, TestStatus.MRO_REVIEW);
        }
        if (s.getLabReceivedAt() == null) {
            s.setLabReceivedAt(c.receivedAt());
            custody.append(s.getSpecimenId(), CustodyEvent.LAB_RECEIVED.name(), c.receivedAt(), org, client.username(), "LABORATORY:" + org, now);
        }
        s.setResultedAt(now);
        t.setUpdatedAt(now);
        collection.reviewQueue(t);
        audit.recordAs(client.username(), "INTEGRATION", "DA_LAB_RESULT", "DA_TEST", t.getId().toString(), AuditService.Outcome.SUCCESS,
                (split ? "split " : "") + "validity=" + validity);
        return new LabAck(s.getSpecimenId(), c.labRef(), "RECEIVED");
    }

    /** New laboratory order for the split (B) specimen of a primary specimen collected as split. */
    @Transactional
    public Specimen orderSplit(CurrentUser mro, UUID testId) {
        DaTest t = core.load(testId);
        TestCore.requireStatus(t, List.of(TestStatus.MRO_REVIEW, TestStatus.VERIFIED_POSITIVE));
        Specimen primary = specimens.findByTestIdOrderByCollectedAtAsc(testId).stream()
                .filter(s -> "PRIMARY".equals(s.getKind()) && s.isSplitSpecimen()).findFirst()
                .orElseThrow(() -> SubstanceSupport.unprocessable("no_split_specimen", "No split specimen was collected for this test"));
        if (t.getSplitRequestedAt() != null) {
            throw ApiException.conflict("split_already_requested", "The split specimen has already been ordered");
        }
        if (t.getResults() == null) {
            throw SubstanceSupport.unprocessable("no_primary_result", "The primary specimen has no laboratory result yet");
        }
        Instant now = support.now();
        Specimen b = Specimen.create();
        b.setSpecimenId(primary.getSpecimenId() + "-B");
        b.setTestId(testId);
        b.setSpecimenType(primary.getSpecimenType());
        b.setKind("SPLIT");
        b.setParentSpecimenId(primary.getSpecimenId());
        b.setCollectedAt(primary.getCollectedAt());
        b.setSiteCode(primary.getSiteCode());
        b.setTemperatureInRange(primary.getTemperatureInRange());
        b.setSealIntact(true);
        b.setDonorIdentityChecked(true);
        b.setSplitSpecimen(false);
        b.setCollector(primary.getCollector());
        b.setLabCode(primary.getLabCode());
        b.setDocumentId(primary.getDocumentId());
        b.setCreatedAt(now);
        specimens.save(b);
        custody.append(b.getSpecimenId(), CustodyEvent.SPLIT_ORDERED.name(), now, mro.displayName() == null ? mro.username() : mro.displayName(),
                mro.username(), "MRO", now);
        t.setSplitRequestedAt(now);
        t.setUpdatedAt(now);
        audit.record("DA_SPLIT_REQUESTED", "DA_TEST", testId.toString(), AuditService.Outcome.SUCCESS, b.getSpecimenId());
        return b;
    }
}
