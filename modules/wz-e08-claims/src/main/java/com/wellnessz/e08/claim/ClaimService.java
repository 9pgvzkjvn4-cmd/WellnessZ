package com.wellnessz.e08.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseLink;
import com.wellnessz.casekernel.model.CaseLinkRepository;
import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseWorkService;
import com.wellnessz.casekernel.support.ClaimContextProvider;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e08.E08Events;
import com.wellnessz.e08.model.Claim;
import com.wellnessz.e08.model.ClaimEnums.ClaimStatus;
import com.wellnessz.e08.model.ClaimEnums.ClaimType;
import com.wellnessz.e08.model.ClaimEnums.CostCategory;
import com.wellnessz.e08.model.ClaimEnums.ReportStatus;
import com.wellnessz.e08.model.ClaimHistory;
import com.wellnessz.e08.model.E08ClaimHistoryRepository;
import com.wellnessz.e08.model.E08ClaimRepository;
import com.wellnessz.e08.model.E08ClaimTaskRepository;
import com.wellnessz.e08.model.E08DisclosureSetDocumentRepository;
import com.wellnessz.e08.model.E08DisclosureSetRepository;
import com.wellnessz.e08.model.E08FirstReportRepository;
import com.wellnessz.e08.model.E08LegalRequestRepository;
import com.wellnessz.e08.model.FirstReport;
import com.wellnessz.e08.support.ClaimAccess;
import com.wellnessz.e08.support.ClaimsSupport;
import com.wellnessz.e08.support.Money;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessDecision;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Claim record and lifecycle (E08-F01): creation on a case (one per case and type), list / detail by role, updates of payer,
 * adjuster, lost-time flag and reserves, status workflow with history, subrogation flag with a legal task, the case header
 * badge, the HR status-only view (disclosure-logged) and the {@link ClaimContextProvider} SPI for E04 and the case header.
 */
@Service
public class ClaimService implements ClaimContextProvider {

    private static final TypeReference<Map<String, Money>> RESERVES = new TypeReference<>() {
    };
    private static final TypeReference<ClaimViews.Subrogation> SUBROGATION = new TypeReference<>() {
    };

    private final E08ClaimRepository claims;
    private final E08ClaimHistoryRepository history;
    private final E08ClaimTaskRepository tasks;
    private final E08FirstReportRepository reports;
    private final E08DisclosureSetRepository sets;
    private final E08DisclosureSetDocumentRepository setDocuments;
    private final E08LegalRequestRepository legalRequests;
    private final OhCaseRepository cases;
    private final CaseAccess caseAccess;
    private final CaseTimeline timeline;
    private final CaseLinkRepository links;
    private final CaseWorkService work;
    private final EmployeeRecordAccess records;
    private final NurseAssignmentService nurses;
    private final ClaimAccess access;
    private final ClaimsSupport support;
    private final DisclosureLogService disclosures;
    private final EventBus bus;
    private final AuditService audit;

    public ClaimService(E08ClaimRepository claims, E08ClaimHistoryRepository history, E08ClaimTaskRepository tasks, E08FirstReportRepository reports,
                        E08DisclosureSetRepository sets, E08DisclosureSetDocumentRepository setDocuments, E08LegalRequestRepository legalRequests,
                        OhCaseRepository cases, CaseAccess caseAccess, CaseTimeline timeline, CaseLinkRepository links, CaseWorkService work,
                        EmployeeRecordAccess records, NurseAssignmentService nurses, ClaimAccess access, ClaimsSupport support,
                        DisclosureLogService disclosures, EventBus bus, AuditService audit) {
        this.claims = claims;
        this.history = history;
        this.tasks = tasks;
        this.reports = reports;
        this.sets = sets;
        this.setDocuments = setDocuments;
        this.legalRequests = legalRequests;
        this.cases = cases;
        this.caseAccess = caseAccess;
        this.timeline = timeline;
        this.links = links;
        this.work = work;
        this.records = records;
        this.nurses = nurses;
        this.access = access;
        this.support = support;
        this.disclosures = disclosures;
        this.bus = bus;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ commands

    public record CreateCommand(String claimType, String payerCode, String policyNumber, String adjusterName, String adjusterEmail,
                                String adjusterUsername, boolean lostTime, LocalDate incidentDate, String notes) {
    }

    @Transactional
    public ClaimViews.ClaimView create(CurrentUser user, UUID caseId, CreateCommand cmd) {
        OhCase oc = loadCaseForClaims(user, caseId, AccessPolicy.Action.UPDATE, "CLAIM_CREATE");
        ClaimType type = parse(ClaimType.class, cmd.claimType(), "claimType");
        support.requirePayer(cmd.payerCode());
        if (claims.findByCaseIdAndClaimType(caseId, type.name()).isPresent()) {
            throw ApiException.conflict("claim_exists", "This case already has a " + type.name() + " claim");
        }
        Instant now = support.now();
        Claim c = Claim.create();
        c.setClaimRef(support.nextRef("CLM"));
        c.setCaseId(oc.getId());
        c.setCaseRef(oc.getCaseRef());
        c.setEmployeeId(oc.getEmployeeId());
        c.setClaimType(type.name());
        c.setStatus(ClaimStatus.NOTIFIED.name());
        c.setPayerCode(cmd.payerCode());
        c.setPolicyNumber(blankToNull(cmd.policyNumber()));
        c.setAdjusterName(ClaimsSupport.screened("adjusterName", cmd.adjusterName(), 200));
        c.setAdjusterEmail(blankToNull(cmd.adjusterEmail()));
        c.setAdjusterUsername(blankToNull(cmd.adjusterUsername()));
        c.setLostTime(cmd.lostTime());
        c.setLostTimeAt(cmd.lostTime() ? now : null);
        c.setIncidentDate(cmd.incidentDate() != null ? cmd.incidentDate() : oc.getOccurredOn());
        c.setNotes(ClaimsSupport.screened("notes", cmd.notes(), 4000));
        c.setNotifiedAt(now);
        c.setLitigationHold(false);
        c.setPrivileged(false);
        c.setCreatedBy(user.username());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        claims.save(c);
        record(c, "STATUS", null, ClaimStatus.NOTIFIED.name(), "Claim notified (" + type.name() + ", payer " + c.getPayerCode() + ")", user.username());
        links.save(new CaseLink(oc.getId(), CaseLink.Type.CLAIM, c.getClaimRef(), user.username(), now));
        timeline.record(oc.getId(), "CLAIM_NOTIFIED", "Claim " + c.getClaimRef() + " notified (" + type.name() + ")");
        bus.publish(new E08Events.ClaimStatusChanged(c.getId().toString(), oc.getId().toString(), c.getStatus()));
        audit.record("CLAIM_CREATE", "CLAIM", c.getId().toString(), AuditService.Outcome.SUCCESS, type.name());
        nurses.assignIfNeeded(c, user.username());
        return view(user, c);
    }

    public record UpdateCommand(String payerCode, String policyNumber, String adjusterName, String adjusterEmail, String adjusterUsername,
                                Boolean lostTime, Map<String, Money> reserves, Money paidTotal) {
    }

    @Transactional
    public ClaimViews.ClaimView update(CurrentUser user, UUID id, UpdateCommand cmd) {
        Claim c = access.require(user, id, ClaimAccess.roles(user, Roles.CLAIMS_COORDINATOR), "CLAIM_UPDATE");
        List<String> changed = new ArrayList<>();
        if (cmd.payerCode() != null && !cmd.payerCode().equals(c.getPayerCode())) {
            support.requirePayer(cmd.payerCode());
            c.setPayerCode(cmd.payerCode());
            changed.add("payer");
        }
        if (cmd.policyNumber() != null) {
            c.setPolicyNumber(blankToNull(cmd.policyNumber()));
            changed.add("policy");
        }
        if (cmd.adjusterName() != null || cmd.adjusterEmail() != null || cmd.adjusterUsername() != null) {
            if (cmd.adjusterName() != null) {
                c.setAdjusterName(ClaimsSupport.screened("adjusterName", cmd.adjusterName(), 200));
            }
            if (cmd.adjusterEmail() != null) {
                c.setAdjusterEmail(blankToNull(cmd.adjusterEmail()));
            }
            if (cmd.adjusterUsername() != null) {
                c.setAdjusterUsername(blankToNull(cmd.adjusterUsername()));
            }
            changed.add("adjuster");
        }
        boolean flaggedLostTime = false;
        if (cmd.lostTime() != null && cmd.lostTime() != c.isLostTime()) {
            c.setLostTime(cmd.lostTime());
            if (cmd.lostTime() && c.getLostTimeAt() == null) {
                c.setLostTimeAt(support.now());
            }
            flaggedLostTime = cmd.lostTime();
            changed.add("lostTime=" + cmd.lostTime());
        }
        if (cmd.reserves() != null) {
            Map<String, Money> clean = new LinkedHashMap<>();
            cmd.reserves().forEach((k, v) -> {
                CostCategory cat = parse(CostCategory.class, k, "reserves");
                if (v == null) {
                    return;
                }
                clean.put(cat.name(), validMoney(v, "reserves." + cat.name()));
            });
            c.setReserves(clean.isEmpty() ? null : support.json(clean));
            changed.add("reserves");
        }
        if (cmd.paidTotal() != null) {
            Money m = validMoney(cmd.paidTotal(), "paidTotal");
            c.setPaidTotal(m.value());
            c.setPaidCurrency(m.currency());
            changed.add("paidTotal");
        }
        c.setUpdatedAt(support.now());
        record(c, "UPDATED", c.getStatus(), c.getStatus(), String.join(", ", changed), user.username());
        audit.record("CLAIM_UPDATE", "CLAIM", id.toString(), AuditService.Outcome.SUCCESS, String.join(",", changed));
        if (flaggedLostTime) {
            nurses.assignIfNeeded(c, user.username());
        }
        return view(user, c);
    }

    @Transactional
    public ClaimViews.ClaimView transition(CurrentUser user, UUID id, String status, String reason) {
        ClaimStatus to = parse(ClaimStatus.class, status, "status");
        boolean legalOnly = !user.hasRole(Roles.CLAIMS_COORDINATOR);
        Claim c = access.require(user, id, x -> user.hasRole(Roles.CLAIMS_COORDINATOR) || user.hasRole(Roles.LEGAL_COUNSEL), "CLAIM_STATUS");
        if (legalOnly && to != ClaimStatus.LITIGATED) {
            throw ApiException.forbidden("Legal counsel may only mark a claim as litigated");
        }
        ClaimStatus from = ClaimStatus.valueOf(c.getStatus());
        if (!ClaimWorkflow.allowed(from, to)) {
            throw ClaimsSupport.unprocessable("invalid_transition", "A claim cannot move from " + from + " to " + to);
        }
        String why = ClaimsSupport.screened("reason", reason, 1000);
        if (ClaimWorkflow.reasonRequired(from, to) && why == null) {
            throw ClaimsSupport.unprocessable("reason_required", "A reason is required for this status change");
        }
        Instant now = support.now();
        c.setStatus(to.name());
        switch (to) {
            case OPEN -> {
                if (c.getOpenedAt() == null) {
                    c.setOpenedAt(now);
                }
                c.setClosedAt(null);
            }
            case SETTLED -> c.setSettledAt(now);
            case CLOSED -> c.setClosedAt(now);
            default -> {
                // UNDER_REVIEW / LITIGATED carry no timestamp of their own (history has it)
            }
        }
        c.setUpdatedAt(now);
        record(c, from == ClaimStatus.CLOSED ? "REOPENED" : "STATUS", from.name(), to.name(), why, user.username());
        timeline.record(c.getCaseId(), "CLAIM_STATUS", "Claim " + c.getClaimRef() + " " + from + " → " + to);
        bus.publish(new E08Events.ClaimStatusChanged(c.getId().toString(), c.getCaseId().toString(), to.name()));
        audit.record("CLAIM_STATUS", "CLAIM", id.toString(), AuditService.Outcome.SUCCESS, from + "->" + to);
        return view(user, c);
    }

    public record SubrogationCommand(String thirdPartyName, String thirdPartyType, String thirdPartyInsurer, String reference, String description) {
    }

    @Transactional
    public ClaimViews.ClaimView subrogation(CurrentUser user, UUID id, SubrogationCommand cmd) {
        Claim c = access.require(user, id, ClaimAccess.roles(user, Roles.CLAIMS_COORDINATOR), "CLAIM_SUBROGATION");
        if (cmd.thirdPartyType() == null || !cmd.thirdPartyType().matches("CONTRACTOR|VEHICLE_OWNER|OTHER")) {
            throw ApiException.badRequest("invalid_third_party_type", "thirdPartyType must be CONTRACTOR, VEHICLE_OWNER or OTHER");
        }
        ClaimViews.Subrogation s = new ClaimViews.Subrogation(ClaimsSupport.screened("thirdPartyName", cmd.thirdPartyName(), 200),
                cmd.thirdPartyType(), ClaimsSupport.screened("thirdPartyInsurer", cmd.thirdPartyInsurer(), 200),
                ClaimsSupport.screened("reference", cmd.reference(), 100), ClaimsSupport.screened("description", cmd.description(), 2000),
                support.now(), user.username());
        if (s.thirdPartyName() == null || s.description() == null) {
            throw ApiException.badRequest("missing_field", "thirdPartyName and description are required");
        }
        c.setSubrogation(support.json(s));
        c.setUpdatedAt(support.now());
        record(c, "SUBROGATION", c.getStatus(), c.getStatus(), "Subrogation potential: " + s.thirdPartyType(), user.username());
        LocalDate due = support.today().plusDays(14);
        work.systemTask(c.getCaseId(), CaseTask.Type.REMINDER, "Legal review of subrogation potential – claim " + c.getClaimRef(),
                due.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), "legal", user.username());
        support.notifyLegal("legal.request", Map.of("reference", c.getClaimRef(), "caseRef", c.getCaseRef(), "dueDate", due.toString()),
                "#/legal");
        audit.record("CLAIM_SUBROGATION", "CLAIM", id.toString(), AuditService.Outcome.SUCCESS, s.thirdPartyType());
        return view(user, c);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<ClaimViews.ClaimView> list(CurrentUser user, String status, String type, Boolean lostTime, String payerCode, String q) {
        String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);
        List<ClaimViews.ClaimView> out = new ArrayList<>();
        for (Claim c : claims.findAllByOrderByNotifiedAtDesc()) {
            if (!ClaimAccess.staffReader(user).test(c)
                    || (status != null && !status.equals(c.getStatus()))
                    || (type != null && !type.equals(c.getClaimType()))
                    || (lostTime != null && lostTime != c.isLostTime())
                    || (payerCode != null && !payerCode.equals(c.getPayerCode()))) {
                continue;
            }
            ClaimViews.ClaimView v = view(user, c);
            if (needle != null && !(c.getClaimRef().toLowerCase(Locale.ROOT).contains(needle)
                    || c.getCaseRef().toLowerCase(Locale.ROOT).contains(needle)
                    || c.getEmployeeId().toLowerCase(Locale.ROOT).contains(needle)
                    || (v.displayName() != null && v.displayName().toLowerCase(Locale.ROOT).contains(needle)))) {
                continue;
            }
            out.add(v);
        }
        audit.record("CLAIM_LIST", "CLAIM", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public ClaimViews.ClaimDetail get(CurrentUser user, UUID id) {
        Claim c = access.require(user, id, ClaimAccess.staffReader(user), "CLAIM_READ");
        ClaimViews.ClaimView v = view(user, c);
        List<ClaimViews.HistoryEntry> h = history.findByClaimIdOrderByOccurredAtAsc(id).stream()
                .map(x -> new ClaimViews.HistoryEntry(x.getEventType(), x.getFromStatus(), x.getToStatus(), x.getReason(), x.getActor(),
                        x.getOccurredAt())).toList();
        List<ClaimViews.ReportSummary> rs = reports.findByClaimIdOrderByCreatedAtAsc(id).stream()
                .map(r -> new ClaimViews.ReportSummary(r.getId(), r.getReportRef(), r.getFormCode(), r.getRecipientCode(), r.getStatus(),
                        r.getDueAt(), r.getSubmittedAt(), r.getAcknowledgedAt())).toList();
        List<ClaimViews.DisclosureSetSummary> ds = sets.findByClaimIdOrderByCreatedAtAsc(id).stream()
                .map(s -> new ClaimViews.DisclosureSetSummary(s.getId(), s.getRecipientCode(), s.getStatus(),
                        setDocuments.findBySetIdOrderByPositionAsc(s.getId()).size(), s.getSentAt())).toList();
        List<ClaimViews.LegalRequestSummary> lr = legalRequests.findByClaimIdOrderByCreatedAtAsc(id).stream()
                .map(r -> new ClaimViews.LegalRequestSummary(r.getId(), r.getRequestRef(), r.getRequestType(), r.getStatus(), r.getDueDate()))
                .toList();
        List<String> next = ClaimWorkflow.next(ClaimStatus.valueOf(c.getStatus())).stream().map(Enum::name).sorted().toList();
        audit.record("CLAIM_READ", "CLAIM", id.toString(), AuditService.Outcome.SUCCESS, null);
        return ClaimViews.ClaimDetail.of(v, c.getHoldReason(), c.getPrivilegeBasis(), next, h, rs, ds, lr);
    }

    /** Case header badge; empty → 204. OH roles need case access; the claims team sees the badge of any case. */
    @Transactional(readOnly = true)
    public Optional<ClaimViews.CaseClaimStatus> caseStatus(CurrentUser user, UUID caseId) {
        loadCaseForClaims(user, caseId, AccessPolicy.Action.READ, "CLAIM_STATUS_READ");
        return primaryClaim(caseId).map(c -> new ClaimViews.CaseClaimStatus(c.getId(), c.getClaimRef(), c.getClaimType(), c.getStatus(),
                c.isLostTime(), c.isLitigationHold(),
                user.hasRole(Roles.CLAIMS_COORDINATOR) ? Money.of(c.getPaidTotal(), c.getPaidCurrency()) : null));
    }

    /** HR workspace: claims of employees inside the HR user's org scope, status only; every claim shown is disclosure-logged. */
    @Transactional
    public List<ClaimViews.WorkspaceClaim> workspace(CurrentUser user) {
        List<ClaimViews.WorkspaceClaim> out = new ArrayList<>();
        Map<String, Boolean> scope = new LinkedHashMap<>();
        for (Claim c : claims.findAllByOrderByNotifiedAtDesc()) {
            boolean visible = scope.computeIfAbsent(c.getEmployeeId(), e -> inHrScope(user, e));
            if (!visible) {
                continue;
            }
            String name = support.name(c.getEmployeeId());
            out.add(new ClaimViews.WorkspaceClaim(c.getClaimRef(), c.getEmployeeId(), name, c.getClaimType(), c.getStatus(), c.isLostTime(),
                    c.getIncidentDate()));
            String recipient = "HR " + user.username();
            String title = "Claim status " + c.getClaimRef() + " (" + c.getStatus() + ")";
            if (!support.alreadyDisclosed("CLAIM_STATUS", c.getId().toString(), recipient, title)) {
                disclosures.record(c.getEmployeeId(), "HR", recipient, "CLAIM_STATUS", c.getId().toString(), title, "CLAIM_STATUS", null,
                        user.username());
            }
        }
        audit.record("WORKSPACE_CLAIMS_READ", "CLAIM", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    private boolean inHrScope(CurrentUser user, String employeeId) {
        Employee e = support.employee(employeeId).orElse(null);
        if (e == null) {
            return false;
        }
        AccessDecision d = records.decide(user, AccessPolicy.Action.READ, "EMPLOYEE_RECORD", e, DataClassification.CONFIDENTIAL);
        return d.permitted() && d.disclosure().atLeast(DisclosureLevel.STATUS_ONLY);
    }

    // ------------------------------------------------------------------ SPI

    @Override
    @Transactional(readOnly = true)
    public Optional<ClaimContext> forCase(UUID caseId) {
        return primaryClaim(caseId).map(c -> new ClaimContext(c.getId(), c.getClaimRef(), c.getClaimType(), c.getStatus(), c.getPayerCode(),
                c.isLostTime(), c.isLitigationHold()));
    }

    /** The case's claim that is still active (most recent), else the most recent claim. */
    public Optional<Claim> primaryClaim(UUID caseId) {
        List<Claim> list = claims.findByCaseIdOrderByNotifiedAtDesc(caseId);
        return list.stream().filter(c -> !ClaimStatus.CLOSED.name().equals(c.getStatus())).findFirst().or(() -> list.stream().findFirst());
    }

    // ------------------------------------------------------------------ helpers

    /** The claims team works on any case (no clinical read); OH roles go through case ABAC. */
    OhCase loadCaseForClaims(CurrentUser user, UUID caseId, AccessPolicy.Action action, String auditAction) {
        if (user.hasAnyRole(Roles.OH_TEAM)) {
            return caseAccess.clinical(user, caseId, action, auditAction);
        }
        if (user.hasRole(Roles.CLAIMS_COORDINATOR)) {
            return cases.findById(caseId).orElseThrow(() -> ApiException.notFound("Case"));
        }
        audit.record(auditAction, "CASE", caseId.toString(), AuditService.Outcome.DENIED, "Not permitted");
        throw ApiException.notFound("Case");
    }

    public ClaimViews.ClaimView view(CurrentUser user, Claim c) {
        boolean money = ClaimsSupport.seesMoney(user);
        Map<String, Money> reserves = money && c.getReserves() != null ? support.read(c.getReserves(), RESERVES) : null;
        ClaimViews.Subrogation sub = c.getSubrogation() == null ? null : support.read(c.getSubrogation(), SUBROGATION);
        ClaimViews.Adjuster adj = c.getAdjusterName() == null && c.getAdjusterEmail() == null && c.getAdjusterUsername() == null ? null
                : new ClaimViews.Adjuster(c.getAdjusterName(), c.getAdjusterEmail(), c.getAdjusterUsername());
        boolean notes = user.hasAnyRole(Roles.CLAIMS_COORDINATOR) || ClaimsSupport.isOh(user);
        return new ClaimViews.ClaimView(c.getId(), c.getClaimRef(), c.getCaseId(), c.getCaseRef(), c.getEmployeeId(), support.name(c.getEmployeeId()),
                support.unit(c.getEmployeeId()), c.getClaimType(), c.getStatus(), c.getPayerCode(), support.partnerName(c.getPayerCode()),
                c.getPolicyNumber(), adj, c.isLostTime(), c.getIncidentDate(), c.getNotifiedAt(), c.getOpenedAt(), c.getSettledAt(), c.getClosedAt(),
                c.getNurseUsername(), c.getNurseUsername() == null ? null : support.staffName(c.getNurseUsername()), c.getNurseAssignedAt(),
                reserves, money ? Money.of(c.getPaidTotal(), c.getPaidCurrency()) : null, sub, c.isLitigationHold(), c.isPrivileged(),
                tasks.countByClaimIdAndStatus(c.getId(), "OPEN"), reportStatus(c.getId()), notes ? c.getNotes() : null);
    }

    /** Most pressing first-report status: LATE, DRAFT, REJECTED, SUBMITTED, ACKNOWLEDGED. */
    String reportStatus(UUID claimId) {
        List<String> order = List.of(ReportStatus.LATE.name(), ReportStatus.DRAFT.name(), ReportStatus.REJECTED.name(),
                ReportStatus.SUBMITTED.name(), ReportStatus.ACKNOWLEDGED.name());
        return reports.findByClaimIdOrderByCreatedAtAsc(claimId).stream().map(FirstReport::getStatus)
                .min(Comparator.comparingInt(order::indexOf)).orElse(null);
    }

    void record(Claim c, String type, String from, String to, String reason, String actor) {
        ClaimHistory h = ClaimHistory.create();
        h.setClaimId(c.getId());
        h.setEventType(type);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setReason(reason == null ? null : reason.substring(0, Math.min(reason.length(), 1000)));
        h.setActor(actor);
        h.setOccurredAt(support.now());
        history.save(h);
    }

    Money validMoney(Money m, String field) {
        if (m.amount() == null || m.currency() == null || !m.currency().matches("[A-Z]{3}")) {
            throw ApiException.badRequest("invalid_money", "Field '" + field + "' needs an amount and an ISO 4217 currency");
        }
        BigDecimal v;
        try {
            v = new BigDecimal(m.amount().trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_money", "Field '" + field + "' has an invalid amount");
        }
        if (v.signum() < 0 || v.compareTo(new BigDecimal("1000000000")) >= 0) {
            throw ApiException.badRequest("invalid_money", "Field '" + field + "' is out of range");
        }
        return Money.of(v, m.currency());
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_" + field, "Invalid value for '" + field + "'");
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
