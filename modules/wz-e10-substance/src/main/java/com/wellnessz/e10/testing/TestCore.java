package com.wellnessz.e10.testing;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e10.model.DaTest;
import com.wellnessz.e10.model.E10SpecimenRepository;
import com.wellnessz.e10.model.E10TestRepository;
import com.wellnessz.e10.model.SubstanceEnums.Panel;
import com.wellnessz.e10.model.SubstanceEnums.TestStatus;
import com.wellnessz.e10.support.SubstanceSupport;
import com.wellnessz.e10.support.TestingViews.Requirement;
import com.wellnessz.e10.support.TestingViews.SpecimenSummary;
import com.wellnessz.e10.trigger.TestWindows;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.audit.AuditService;

/**
 * Shared life-cycle of a test requirement: creation (idempotent on the source reference), notification of the employee
 * ({@code testing.selection-notice}), dispatch to the collection staff ({@code testing.collection-dispatch}), status changes and
 * the work-list projection. Notifications are content-free – never the reason or the outcome.
 */
@Component("e10TestCore")
public class TestCore {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final E10TestRepository tests;
    private final E10SpecimenRepository specimens;
    private final SubstanceSupport support;
    private final AuditService audit;

    public TestCore(E10TestRepository tests, E10SpecimenRepository specimens, SubstanceSupport support, AuditService audit) {
        this.tests = tests;
        this.specimens = specimens;
        this.support = support;
        this.audit = audit;
    }

    public record NewTest(String employeeId, String roleGroup, String reason, List<String> panels, String sourceRef, String actor) {
    }

    public DaTest load(UUID id) {
        return tests.findById(id).orElseThrow(() -> ApiException.notFound("Test"));
    }

    /** Creates a REQUIRED test – or returns the existing one for the same source reference (idempotent triggers). */
    public DaTest create(NewTest n) {
        return tests.findBySourceRef(n.sourceRef()).orElseGet(() -> {
            Instant now = support.now();
            DaTest t = DaTest.create();
            t.setRef(support.nextRef("DAT"));
            t.setEmployeeId(n.employeeId());
            t.setRoleGroup(n.roleGroup());
            t.setReason(n.reason());
            t.setPanels(String.join(",", n.panels()));
            t.setStatus(TestStatus.REQUIRED.name());
            t.setSourceRef(n.sourceRef());
            t.setSiteCode(support.defaultSite());
            t.setCreatedBy(n.actor());
            t.setCreatedAt(now);
            t.setUpdatedAt(now);
            DaTest saved = tests.save(t);
            audit.recordAs(n.actor(), "MODULE", "DA_TEST_CREATED", "DA_TEST", saved.getId().toString(), AuditService.Outcome.SUCCESS, n.reason());
            return saved;
        });
    }

    /** REQUIRED → NOTIFIED with a short lead time; the employee is told where and by when to report. */
    public void notifyEmployee(DaTest t, int leadTimeMinutes) {
        requireStatus(t, List.of(TestStatus.REQUIRED));
        Instant now = support.now();
        t.setNotifiedAt(now);
        if (t.getDueAt() == null) {
            t.setDueAt(now.plus(Duration.ofMinutes(Math.max(leadTimeMinutes, 0))));
        }
        transition(t, TestStatus.NOTIFIED);
        sendNotice(t, Priority.NORMAL);
        dispatchNotice(t, Priority.NORMAL, "ROUTINE");
    }

    /** Urgent dispatch (post-incident, for-cause): REQUIRED / NOTIFIED → DISPATCHED. */
    public void dispatch(DaTest t, boolean urgent) {
        requireStatus(t, List.of(TestStatus.REQUIRED, TestStatus.NOTIFIED));
        Instant now = support.now();
        if (t.getNotifiedAt() == null) {
            t.setNotifiedAt(now);
            sendNotice(t, urgent ? Priority.URGENT : Priority.NORMAL);
        }
        t.setDispatchedAt(now);
        transition(t, TestStatus.DISPATCHED);
        dispatchNotice(t, urgent ? Priority.URGENT : Priority.NORMAL, urgent ? "URGENT" : "ROUTINE");
    }

    private void sendNotice(DaTest t, Priority priority) {
        support.notifyEmployee(t.getEmployeeId(), "testing.selection-notice", Map.of("firstName", support.firstName(t.getEmployeeId()),
                "site", t.getSiteCode(), "appointmentTime", TIME.format(dueOrNow(t)), "appointmentDate", DATE.format(dueOrNow(t)),
                "reference", t.getRef()), priority);
    }

    private void dispatchNotice(DaTest t, Priority priority, String level) {
        support.notifyRole(Roles.COLLECTOR, "testing.collection-dispatch", Map.of("reference", t.getRef(), "site", t.getSiteCode(),
                "appointmentTime", TIME.format(dueOrNow(t)) + " UTC " + DATE.format(dueOrNow(t)), "level", level), "#/testing/collection", priority);
    }

    private Instant dueOrNow(DaTest t) {
        return t.getDueAt() != null ? t.getDueAt() : support.now();
    }

    public void transition(DaTest t, TestStatus next) {
        t.setStatus(next.name());
        t.setUpdatedAt(support.now());
    }

    public static void requireStatus(DaTest t, List<TestStatus> allowed) {
        if (allowed.stream().noneMatch(s -> s.name().equals(t.getStatus()))) {
            throw ApiException.conflict("invalid_state", "Test " + t.getRef() + " is " + t.getStatus());
        }
    }

    public static boolean hasAlcohol(DaTest t) {
        return SubstanceSupport.split(t.getPanels()).stream().anyMatch(p -> Panel.valueOf(p).alcohol());
    }

    public static boolean hasDrugs(DaTest t) {
        return SubstanceSupport.split(t.getPanels()).stream().anyMatch(p -> !Panel.valueOf(p).alcohol());
    }

    /** The deadline the countdown shows: the open alcohol window first, then the drug window, else the report-by time. */
    public static Instant effectiveDue(DaTest t) {
        if (t.getAlcoholDueAt() == null && t.getDrugDueAt() == null) {
            return t.getDueAt();
        }
        return new TestWindows.Windows(t.getAlcoholDueAt(), t.getDrugDueAt())
                .nextDue(hasAlcohol(t), t.getAlcoholCollectedAt() != null, hasDrugs(t));
    }

    /** Work-list projection; {@code withObservations} is false for collectors. Never contains results. */
    public Requirement requirement(DaTest t, boolean withObservations) {
        Instant due = effectiveDue(t);
        boolean open = TestStatus.PENDING_COLLECTION.contains(TestStatus.valueOf(t.getStatus()));
        List<SpecimenSummary> sp = specimens.findByTestIdOrderByCollectedAtAsc(t.getId()).stream()
                .map(s -> new SpecimenSummary(s.getSpecimenId(), s.getSpecimenType(), s.getKind(), s.getCollectedAt(), s.isOutOfWindow(),
                        s.getDocumentId())).toList();
        Employee e = support.employee(t.getEmployeeId()).orElse(null);
        return new Requirement(t.getId(), t.getRef(), t.getEmployeeId(), e == null ? t.getEmployeeId() : e.getDisplayName(), t.getReason(),
                SubstanceSupport.split(t.getPanels()), t.getStatus(), due, open ? TestWindows.hoursLeft(support.now(), due) : null, t.getSiteCode(),
                t.getDispatchedAt(), t.getNotifiedAt(), t.getAlcoholDueAt(), t.getDrugDueAt(), t.getScheduledFor(),
                withObservations ? SubstanceSupport.split(t.getObservations()) : null, sp);
    }
}
