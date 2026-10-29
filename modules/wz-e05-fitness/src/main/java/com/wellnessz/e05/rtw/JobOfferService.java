package com.wellnessz.e05.rtw;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e05.E05Events;
import com.wellnessz.e05.model.JobOffer;
import com.wellnessz.e05.model.JobOfferRepository;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.e05.support.RestrictionRef;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Electronic alternative-duty job offers (E05-BL-008): HR issues an offer stating duties, pay, shift, restrictions and
 * contact (JOB_OFFER document, employee notified); the employee accepts or declines in the portal and HRIS receives a
 * status-only note ({@code JobOfferResponded}); HR may withdraw; unanswered offers expire after the respond-by date.
 */
@Service
public class JobOfferService {

    static final Set<String> PAY_BASIS = Set.of("SAME_PAY", "PROTECTED_PAY", "ADJUSTED_PAY");

    private final JobOfferRepository offers;
    private final CaseAccess access;
    private final CaseTimeline timeline;
    private final DocumentService documents;
    private final FitnessSupport support;
    private final EventBus bus;
    private final AuditService audit;

    public JobOfferService(JobOfferRepository offers, CaseAccess access, CaseTimeline timeline, DocumentService documents,
                           FitnessSupport support, EventBus bus, AuditService audit) {
        this.offers = offers;
        this.access = access;
        this.timeline = timeline;
        this.documents = documents;
        this.support = support;
        this.bus = bus;
        this.audit = audit;
    }

    public record OfferCommand(String jobCode, String jobTitle, String duties, String location, String shift, BigDecimal hoursPerWeek,
                               String payBasis, String payNote, List<RestrictionRef.Input> restrictions, LocalDate startDate, LocalDate endDate,
                               String contactName, String contactEmail, LocalDate respondBy) {
    }

    public record OfferView(UUID id, UUID caseId, String caseRef, String employeeId, String employeeName, String jobCode, String jobTitle,
                            String duties, String location, String shift, BigDecimal hoursPerWeek, String payBasis, String payNote,
                            List<RestrictionRef.View> restrictions, LocalDate startDate, LocalDate endDate, String contactName,
                            String contactEmail, LocalDate respondBy, String status, String issuedBy, Instant issuedAt, Instant respondedAt,
                            String comment, UUID documentId) {
    }

    @Transactional
    public OfferView issue(CurrentUser user, UUID caseId, OfferCommand cmd) {
        if (!user.hasRole(Roles.HR)) {
            throw ApiException.forbidden("Job offers are issued by HR");
        }
        OhCase c = access.require(user, caseId, AccessPolicy.Action.READ, DisclosureLevel.STATUS_ONLY, "JOB_OFFER_ISSUE");
        LocalDate today = support.today();
        String jobTitle = required("jobTitle", FitnessSupport.functional("jobTitle", cmd.jobTitle(), 200));
        String duties = required("duties", FitnessSupport.functional("duties", cmd.duties(), 2000));
        String location = required("location", FitnessSupport.functional("location", cmd.location(), 200));
        String shift = required("shift", FitnessSupport.functional("shift", cmd.shift(), 100));
        String contact = required("contactName", FitnessSupport.functional("contactName", cmd.contactName(), 200));
        if (cmd.payBasis() == null || !PAY_BASIS.contains(cmd.payBasis())) {
            throw ApiException.badRequest("invalid_pay_basis", "payBasis must be SAME_PAY, PROTECTED_PAY or ADJUSTED_PAY");
        }
        if (cmd.hoursPerWeek() == null || cmd.hoursPerWeek().signum() <= 0 || cmd.hoursPerWeek().compareTo(BigDecimal.valueOf(60)) > 0) {
            throw ApiException.badRequest("invalid_hours", "hoursPerWeek must be between 0 and 60");
        }
        if (cmd.startDate() == null || (cmd.endDate() != null && cmd.endDate().isBefore(cmd.startDate()))) {
            throw ApiException.badRequest("invalid_dates", "startDate is required and endDate must not be before it");
        }
        if (cmd.respondBy() == null || cmd.respondBy().isBefore(today)) {
            throw ApiException.badRequest("invalid_respond_by", "respondBy must be today or later");
        }
        List<RestrictionRef.View> restrictions = support.resolve(cmd.restrictions());
        JobOffer o = JobOffer.create();
        o.setCaseId(c.getId());
        o.setCaseRef(c.getCaseRef());
        o.setEmployeeId(c.getEmployeeId());
        o.setJobCode(cmd.jobCode());
        o.setJobTitle(jobTitle);
        o.setDuties(duties);
        o.setLocation(location);
        o.setShift(shift);
        o.setHoursPerWeek(cmd.hoursPerWeek());
        o.setPayBasis(cmd.payBasis());
        o.setPayNote(FitnessSupport.functional("payNote", cmd.payNote(), 500));
        o.setRestrictions(support.write(restrictions));
        o.setStartDate(cmd.startDate());
        o.setEndDate(cmd.endDate());
        o.setContactName(contact);
        o.setContactEmail(cmd.contactEmail());
        o.setRespondBy(cmd.respondBy());
        o.setStatus("ISSUED");
        o.setIssuedBy(user.username());
        o.setIssuedAt(support.clock().instant());
        Map<String, String> f = new LinkedHashMap<>();
        f.put("employeeName", support.employeeName(c.getEmployeeId()));
        f.put("jobTitle", jobTitle);
        f.put("duties", duties);
        f.put("location", location);
        f.put("shift", shift);
        f.put("hoursPerWeek", cmd.hoursPerWeek().stripTrailingZeros().toPlainString());
        f.put("payBasis", payLabel(cmd.payBasis()));
        if (o.getPayNote() != null) {
            f.put("payNote", o.getPayNote());
        }
        f.put("restrictions", FitnessSupport.labels(restrictions));
        f.put("startDate", cmd.startDate().toString());
        if (cmd.endDate() != null) {
            f.put("endDate", cmd.endDate().toString());
        }
        f.put("contactName", contact);
        if (cmd.contactEmail() != null) {
            f.put("contactEmail", cmd.contactEmail());
        }
        f.put("respondBy", cmd.respondBy().toString());
        java.util.UUID documentId = documents.generate(user, "JOB_OFFER", c.getEmployeeId(), c.getId(), f).id();
        documents.sign(user, documentId); // HR is the signer of its own offer letter
        documents.share(user, documentId, com.wellnessz.documents.model.DocumentShare.Target.EMPLOYEE, null, null);
        o.setDocumentId(documentId);
        offers.save(o);
        support.notifyEmployee(c.getEmployeeId(), "job-offer.issued", Map.of("dueDate", cmd.respondBy().toString()));
        timeline.record(c.getId(), "JOB_OFFER", "Alternative-duty offer issued by HR: " + jobTitle);
        audit.record("JOB_OFFER_ISSUE", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, o.getId().toString());
        return view(o);
    }

    private static String required(String field, String value) {
        if (value == null) {
            throw ApiException.badRequest("missing_field", "Required field missing: " + field);
        }
        return value;
    }

    static String payLabel(String basis) {
        return switch (basis) {
            case "SAME_PAY" -> "Same pay as your substantive role";
            case "PROTECTED_PAY" -> "Pay protected at your current level";
            default -> "Adjusted pay (see note)";
        };
    }

    @Transactional(readOnly = true)
    public List<OfferView> forCase(CurrentUser user, UUID caseId) {
        if (user.hasAnyRole(Roles.OH_TEAM)) {
            access.clinical(user, caseId, AccessPolicy.Action.READ, "JOB_OFFERS_READ");
        } else if (user.hasRole(Roles.HR)) {
            access.require(user, caseId, AccessPolicy.Action.READ, DisclosureLevel.STATUS_ONLY, "JOB_OFFERS_READ");
            audit.record("JOB_OFFERS_READ", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, "HR status read");
        } else {
            throw ApiException.forbidden("OH and HR only");
        }
        return offers.findByCaseIdOrderByIssuedAtDesc(caseId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<OfferView> mine(CurrentUser user) {
        if (user.employeeId() == null) {
            return List.of();
        }
        return offers.findByEmployeeIdOrderByIssuedAtDesc(user.employeeId()).stream().map(this::view).toList();
    }

    @Transactional
    public OfferView respond(CurrentUser user, UUID id, boolean accept, String comment) {
        JobOffer o = offers.findById(id).filter(x -> user.employeeId() != null && user.employeeId().equals(x.getEmployeeId()))
                .orElseThrow(() -> ApiException.notFound("Job offer"));
        if (!"ISSUED".equals(o.getStatus())) {
            throw ApiException.conflict("offer_not_open", "This offer is no longer open");
        }
        if (o.getRespondBy().isBefore(support.today())) {
            throw ApiException.conflict("offer_expired", "The response deadline has passed – please contact HR");
        }
        Instant now = support.clock().instant();
        o.setStatus(accept ? "ACCEPTED" : "DECLINED");
        o.setRespondedAt(now);
        o.setResponseComment(FitnessSupport.functional("comment", comment, 1000));
        bus.publish(new E05Events.JobOfferResponded(UUID.randomUUID().toString(), o.getEmployeeId(), o.getId().toString(), accept, now),
                E05Events.HR_DESTINATION);
        timeline.record(o.getCaseId(), "JOB_OFFER_RESPONSE", "Employee " + (accept ? "accepted" : "declined") + " the alternative-duty offer");
        audit.record("JOB_OFFER_RESPOND", "JOB_OFFER", id.toString(), AuditService.Outcome.SUCCESS, o.getStatus());
        return view(o);
    }

    @Transactional
    public OfferView withdraw(CurrentUser user, UUID id) {
        if (!user.hasRole(Roles.HR)) {
            throw ApiException.forbidden("Job offers are withdrawn by HR");
        }
        JobOffer o = offers.findById(id).orElseThrow(() -> ApiException.notFound("Job offer"));
        access.require(user, o.getCaseId(), AccessPolicy.Action.READ, DisclosureLevel.STATUS_ONLY, "JOB_OFFER_WITHDRAW");
        if (!"ISSUED".equals(o.getStatus())) {
            throw ApiException.conflict("offer_not_open", "Only open offers can be withdrawn");
        }
        o.setStatus("WITHDRAWN");
        timeline.record(o.getCaseId(), "JOB_OFFER", "Alternative-duty offer withdrawn by HR");
        audit.record("JOB_OFFER_WITHDRAW", "JOB_OFFER", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(o);
    }

    /** Daily: offers past their respond-by date without an answer expire. */
    @Transactional
    public int expire(LocalDate today) {
        List<JobOffer> due = offers.findByStatusAndRespondByBefore("ISSUED", today);
        due.forEach(o -> {
            o.setStatus("EXPIRED");
            timeline.record(o.getCaseId(), "JOB_OFFER", "Alternative-duty offer expired without a response");
        });
        return due.size();
    }

    private OfferView view(JobOffer o) {
        return new OfferView(o.getId(), o.getCaseId(), o.getCaseRef(), o.getEmployeeId(), support.employeeName(o.getEmployeeId()), o.getJobCode(),
                o.getJobTitle(), o.getDuties(), o.getLocation(), o.getShift(), o.getHoursPerWeek(), o.getPayBasis(), o.getPayNote(),
                support.read(o.getRestrictions()), o.getStartDate(), o.getEndDate(), o.getContactName(), o.getContactEmail(), o.getRespondBy(),
                o.getStatus(), o.getIssuedBy(), o.getIssuedAt(), o.getRespondedAt(), o.getResponseComment(), o.getDocumentId());
    }
}
