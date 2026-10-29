package com.wellnessz.e08;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.documents.DocumentService;
import com.wellnessz.e08.model.Claim;
import com.wellnessz.e08.model.ClaimMessage;
import com.wellnessz.e08.model.DisclosureSetDocument;
import com.wellnessz.e08.model.E08ClaimMessageRepository;
import com.wellnessz.e08.model.E08ClaimRepository;
import com.wellnessz.e08.model.E08DisclosureSetDocumentRepository;
import com.wellnessz.e08.model.E08DisclosureSetRepository;
import com.wellnessz.e08.model.E08FirstReportRepository;
import com.wellnessz.e08.model.E08LegalRequestRepository;
import com.wellnessz.e08.model.FirstReport;
import com.wellnessz.e08.model.LegalRequest;
import com.wellnessz.e08.support.ClaimsSupport;

/**
 * Local / test profiles only: completes the E08 demo data (db/demo/R__demo_e08_claims.sql) with the values that are encrypted
 * at field level and therefore cannot be written by SQL – claim notes, first-report fields, secure messages, the medico-legal
 * questions – and the signed first-report document that fills the demo disclosure set. Idempotent; does nothing when the
 * demo claims are not present.
 */
@Component
@Profile({"local", "test"})
public class ClaimsDemoSeeder {

    static final UUID WC_CLAIM = UUID.fromString("8e080000-0000-4000-b000-000000000001");
    static final UUID EL_CLAIM = UUID.fromString("8e080000-0000-4000-b000-000000000002");
    static final UUID F2508_REPORT = UUID.fromString("8e080000-0000-4000-d000-000000000001");
    static final UUID FNOL_REPORT = UUID.fromString("8e080000-0000-4000-d000-000000000002");
    static final UUID DISCLOSURE_SET = UUID.fromString("8e080000-0000-4000-e000-000000000001");
    static final UUID LEGAL_REQUEST = UUID.fromString("8e080000-0000-4000-9000-000000000001");
    private static final Logger log = LoggerFactory.getLogger(ClaimsDemoSeeder.class);

    private final E08ClaimRepository claims;
    private final E08ClaimMessageRepository messages;
    private final E08FirstReportRepository reports;
    private final E08DisclosureSetRepository sets;
    private final E08DisclosureSetDocumentRepository setDocuments;
    private final E08LegalRequestRepository legalRequests;
    private final DocumentService documents;
    private final ClaimsSupport support;
    private final TransactionTemplate tx;

    public ClaimsDemoSeeder(E08ClaimRepository claims, E08ClaimMessageRepository messages, E08FirstReportRepository reports,
                            E08DisclosureSetRepository sets, E08DisclosureSetDocumentRepository setDocuments, E08LegalRequestRepository legalRequests,
                            DocumentService documents, ClaimsSupport support, TransactionTemplate tx) {
        this.claims = claims;
        this.messages = messages;
        this.reports = reports;
        this.sets = sets;
        this.setDocuments = setDocuments;
        this.legalRequests = legalRequests;
        this.documents = documents;
        this.support = support;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            tx.executeWithoutResult(s -> {
                claims.findById(WC_CLAIM).ifPresent(this::workersComp);
                claims.findById(EL_CLAIM).ifPresent(this::employerLiability);
            });
        } catch (RuntimeException e) {
            log.warn("E08 demo seeding skipped: {}", e.getClass().getSimpleName());
        }
    }

    private void workersComp(Claim c) {
        if (c.getNotes() == null) {
            c.setNotes("Lost time from the day after the incident; modified duties offered from week 1.");
        }
        if (messages.findByClaimIdOrderBySentAtAsc(c.getId()).isEmpty()) {
            message(c.getId(), "adjuster1", "ADJUSTER", "Please confirm the expected start date of phase 2 of the return-to-work plan.", 30);
            message(c.getId(), "clinician1", "NURSE_CM", "Phase 2 is planned for week 3 – I will confirm after the review next week.", 10);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("employer", support.config("claims.employer-name", "WellnessZ Airline"));
        fields.put("employerRef", support.config("claims.employer-ref", "WZ-EMP-0001"));
        fields.put("claimRef", c.getClaimRef());
        fields.put("policyNumber", c.getPolicyNumber());
        fields.put("employeeName", "Rami Ramp");
        fields.put("employeeId", "E1004");
        fields.put("jobTitle", "Ramp agent");
        fields.put("occurredAt", c.getIncidentDate() + " 06:40 UTC");
        fields.put("location", "LHR T5 stand 512 – baggage hold");
        fields.put("description", "Lifting a heavy suitcase out of the aircraft hold, the employee twisted and had to stop work.");
        fields.put("bodyPart", "Lower back");
        fields.put("injuryNature", "Strain / sprain");
        fields.put("lostTime", "Yes – time lost from work");
        fields.put("reportedBy", "Claims team");
        reports.findById(F2508_REPORT).filter(r -> r.getFields() == null).ifPresent(r -> {
            Map<String, String> f = new LinkedHashMap<>(fields);
            f.remove("claimRef");
            f.remove("policyNumber");
            f.put("reportRef", r.getReportRef());
            r.setFields(support.json(f));
        });
        reports.findById(FNOL_REPORT).filter(r -> r.getDocumentId() == null).ifPresent(r -> fnol(c, r, fields));
    }

    private void fnol(Claim c, FirstReport r, Map<String, String> fields) {
        Map<String, String> f = new LinkedHashMap<>(fields);
        f.remove("employerRef");
        f.put("reportRef", r.getReportRef());
        r.setFields(support.json(f));
        Map<String, String> merge = new LinkedHashMap<>();
        for (String k : new String[]{"employer", "employeeName", "employeeId", "jobTitle", "occurredAt", "location", "description", "bodyPart",
                "injuryNature", "lostTime", "reportRef", "reportedBy"}) {
            merge.put(k, f.get(k));
        }
        DocumentService.DocumentView doc = documents.generateSigned("claims1", "FIRST_REPORT_OF_INJURY", c.getEmployeeId(), c.getCaseId(), merge);
        r.setDocumentId(doc.id());
        sets.findById(DISCLOSURE_SET).filter(s -> setDocuments.findBySetIdOrderByPositionAsc(s.getId()).isEmpty()).ifPresent(s -> {
            DisclosureSetDocument e = DisclosureSetDocument.create();
            e.setSetId(s.getId());
            e.setPosition(0);
            e.setDocumentId(doc.id());
            e.setTitle(doc.title() + " (" + doc.documentNumber() + ")");
            e.setDocType(doc.type());
            e.setAddedBy("claims1");
            e.setAddedByOh(false);
            setDocuments.save(e);
        });
    }

    private void employerLiability(Claim c) {
        if (legalRequests.existsById(LEGAL_REQUEST)) {
            return;
        }
        LegalRequest r = new LegalRequest();
        r.setId(LEGAL_REQUEST);
        r.setRequestRef("LEG-" + LocalDate.now(support.clock().withZone(ZoneOffset.UTC)).getYear() + "-900001");
        r.setClaimId(c.getId());
        r.setCaseId(c.getCaseId());
        r.setRequestType("MEDICO_LEGAL_REPORT");
        r.setStatus("OPEN");
        r.setQuestions("1. Is the injury consistent with the account of the incident? 2. What is the expected duration of any work limitation?");
        r.setDueDate(support.today().plusDays(14));
        r.setCreatedBy("legal1");
        r.setCreatedAt(support.now().minusSeconds(9 * 86_400L));
        r.setUpdatedAt(support.now().minusSeconds(9 * 86_400L));
        legalRequests.save(r);
    }

    private void message(UUID claimId, String sender, String role, String text, int hoursAgo) {
        ClaimMessage m = ClaimMessage.create();
        m.setClaimId(claimId);
        m.setSender(sender);
        m.setSenderRole(role);
        m.setBody(text);
        m.setSentAt(support.now().minusSeconds(hoursAgo * 3600L));
        messages.save(m);
    }
}
