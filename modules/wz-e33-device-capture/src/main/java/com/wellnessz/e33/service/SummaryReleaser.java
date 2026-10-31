package com.wellnessz.e33.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.e33.domain.PlainLanguage;
import com.wellnessz.e33.domain.VerificationRule;
import com.wellnessz.e33.model.CaptureObservation;
import com.wellnessz.e33.model.CaptureSession;
import com.wellnessz.e33.model.E33EmployeeSummaryRepository;
import com.wellnessz.e33.model.EmployeeSummary;
import com.wellnessz.e33.model.FormRule;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;

/**
 * Releases the plain-language summary of an accepted (or auto-posted) capture to the employee when the form's release rule
 * allows it (E33-HLR-09): {@code release_to_employee} on the form, never for restricted readings, and – for forms whose
 * interpretation needs physician sign-off – only after sign-off. One content-free {@code device.summary-available}
 * notification per session.
 */
@Component
public class SummaryReleaser {

    private final E33EmployeeSummaryRepository summaries;
    private final CaptureSupport support;
    private final AuditService audit;

    public SummaryReleaser(E33EmployeeSummaryRepository summaries, CaptureSupport support, AuditService audit) {
        this.summaries = summaries;
        this.support = support;
        this.audit = audit;
    }

    /** Values of a canonical observation as stored for comparison / summaries: key → number, or key → text. */
    public static Map<String, Object> snapshot(DeviceObservation o) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (DeviceObservation.Component c : o.components()) {
            if (c.key() == null) {
                continue;
            }
            if (c.value() != null) {
                out.put(c.key(), c.value());
            } else if (c.text() != null) {
                out.put(c.key(), c.text());
            }
        }
        return out;
    }

    /** Numeric values of a stored snapshot. */
    public static Map<String, BigDecimal> numeric(Map<String, Object> snapshot) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        snapshot.forEach((k, v) -> {
            if (v instanceof Number n) {
                out.put(k, new BigDecimal(n.toString()));
            } else if (v instanceof String s && s.matches("-?\\d{1,6}(\\.\\d{1,4})?")) {
                out.put(k, new BigDecimal(s));
            }
        });
        return out;
    }

    public boolean releasable(FormRule rule, CaptureSession s) {
        if (!rule.isReleaseToEmployee() || s.isRestricted() || rule.isRestricted()) {
            return false;
        }
        return VerificationRule.roles(rule.getSignOffRoles()).isEmpty() || "SIGNED".equals(s.getInterpretationStatus());
    }

    /** Releases summaries for the given observations of a session (idempotent per observation). */
    public int release(FormRule rule, CaptureSession s, List<CaptureObservation> observations, String actor) {
        if (!releasable(rule, s)) {
            return 0;
        }
        Optional<Employee> emp = support.employee(s.getEmployeeId());
        if (emp.isEmpty()) {
            return 0;
        }
        boolean notified = summaries.findByEmployeeIdOrderByMeasuredAtDesc(s.getEmployeeId()).stream()
                .anyMatch(x -> x.getSessionId().equals(s.getId()));
        int released = 0;
        for (CaptureObservation o : observations) {
            if (summaries.findByObservationId(o.getObservationId()).isPresent()) {
                continue;
            }
            Map<String, BigDecimal> values = numeric(support.readMap(o.getValuesJson()));
            Instant measured = o.getEffectiveAt() == null ? o.getReceivedAt() : o.getEffectiveAt();
            PlainLanguage.Summary text = PlainLanguage.summarise(o.getCategory(), values, LocalDate.ofInstant(measured, ZoneOffset.UTC),
                    emp.get().getPreferredLanguage());
            summaries.save(new EmployeeSummary(s.getEmployeeId(), s.getId(), o.getObservationId(), o.getCategory(), measured, text.language(),
                    text.text(), text.educationLink(), actor, support.now()));
            audit.recordAs(actor, "MODULE", "DEVICE_SUMMARY_RELEASE", "DEVICE_CAPTURE_SESSION", s.getId().toString(),
                    AuditService.Outcome.SUCCESS, o.getCategory() + " " + text.language());
            released++;
        }
        if (released > 0 && !notified) {
            support.notifyEmployee(s.getEmployeeId(), "device.summary-available", "/#/health-checks");
        }
        return released;
    }
}
