package com.wellnessz.security.breakglass;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Break-glass (E03-BL-021): a clinician states a justification and receives 4 hours of access to the employee's
 * restricted-class record. Every use alerts the DPO, creates a 24-hour review item and is fully audited.
 */
@Service
public class BreakGlassService {

    static final Duration VALIDITY = Duration.ofHours(4);
    static final Duration REVIEW_WINDOW = Duration.ofHours(24);

    private final BreakGlassRepository grants;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public BreakGlassService(BreakGlassRepository grants, AuditService audit, ApplicationEventPublisher events, Clock clock) {
        this.grants = grants;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public record GrantView(UUID id, String employeeId, String grantedTo, String reason, Instant grantedAt, Instant expiresAt,
                            String review, String reviewedBy, Instant reviewedAt, String reviewNote, Instant reviewDueAt, boolean reviewOverdue) {
    }

    @Transactional
    public GrantView grant(CurrentUser user, String employeeId, String reason) {
        if (!user.hasAnyRole(Roles.OH_CLINICIAN, Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO, Roles.NURSING_HEAD)) {
            throw ApiException.forbidden("Break-glass is available to OH clinicians only");
        }
        if (reason == null || reason.strip().length() < 20) {
            throw ApiException.badRequest("reason_required", "State the emergency justification (at least 20 characters)");
        }
        if (employeeId != null && employeeId.equals(user.employeeId())) {
            throw ApiException.forbidden("Break-glass on your own record is not permitted");
        }
        Instant now = clock.instant();
        BreakGlassGrant g = grants.save(new BreakGlassGrant(employeeId, user.username(), reason.strip(), now, now.plus(VALIDITY),
                now.plus(REVIEW_WINDOW)));
        audit.record("BREAK_GLASS", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "grant=" + g.getId());
        events.publishEvent(new BreakGlassGranted(g.getId(), g.getGrantedTo(), g.getReviewDueAt()));
        return view(g);
    }

    /** Used by the ABAC restricted-class policy on every evaluation of a restricted resource. */
    @Transactional(readOnly = true)
    public boolean hasActiveGrant(String username, String employeeId) {
        return username != null && employeeId != null
                && grants.existsByGrantedToAndEmployeeIdAndExpiresAtAfter(username, employeeId, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<GrantView> list(String review) {
        List<BreakGlassGrant> list = review == null ? grants.findAllByOrderByGrantedAtDesc()
                : grants.findByReviewOrderByGrantedAtAsc(BreakGlassGrant.Review.valueOf(review));
        audit.record("BREAK_GLASS_REVIEW_LIST", "BREAK_GLASS", null, AuditService.Outcome.SUCCESS, "rows=" + list.size());
        return list.stream().map(this::view).toList();
    }

    @Transactional
    public GrantView review(CurrentUser user, UUID id, BreakGlassGrant.Review outcome, String note) {
        if (outcome == BreakGlassGrant.Review.PENDING) {
            throw ApiException.badRequest("invalid_review", "Choose APPROPRIATE or INAPPROPRIATE");
        }
        BreakGlassGrant g = grants.findById(id).orElseThrow(() -> ApiException.notFound("Break-glass grant"));
        if (g.getGrantedTo().equals(user.username())) {
            throw ApiException.forbidden("A break-glass use cannot be reviewed by the person who used it");
        }
        g.review(outcome, user.username(), note, clock.instant());
        audit.record("BREAK_GLASS_REVIEW", "BREAK_GLASS", id.toString(), AuditService.Outcome.SUCCESS, outcome.name());
        return view(g);
    }

    public record MonthlyReport(String month, int total, int appropriate, int inappropriate, int pending, int overdueReviews,
                                List<Map<String, Object>> byUser) {
    }

    @Transactional(readOnly = true)
    public MonthlyReport report(YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<BreakGlassGrant> list = grants.findByGrantedAtBetweenOrderByGrantedAtAsc(from, to);
        Instant now = clock.instant();
        Map<String, Integer> byUser = new LinkedHashMap<>();
        list.forEach(g -> byUser.merge(g.getGrantedTo(), 1, Integer::sum));
        return new MonthlyReport(month.toString(), list.size(),
                (int) list.stream().filter(g -> g.getReview() == BreakGlassGrant.Review.APPROPRIATE).count(),
                (int) list.stream().filter(g -> g.getReview() == BreakGlassGrant.Review.INAPPROPRIATE).count(),
                (int) list.stream().filter(g -> g.getReview() == BreakGlassGrant.Review.PENDING).count(),
                (int) list.stream().filter(g -> g.getReview() == BreakGlassGrant.Review.PENDING && g.getReviewDueAt().isBefore(now)).count(),
                byUser.entrySet().stream().map(e -> Map.<String, Object>of("username", e.getKey(), "count", e.getValue())).toList());
    }

    private GrantView view(BreakGlassGrant g) {
        return new GrantView(g.getId(), g.getEmployeeId(), g.getGrantedTo(), g.getReason(), g.getGrantedAt(), g.getExpiresAt(),
                g.getReview().name(), g.getReviewedBy(), g.getReviewedAt(), g.getReviewNote(), g.getReviewDueAt(),
                g.getReview() == BreakGlassGrant.Review.PENDING && g.getReviewDueAt().isBefore(clock.instant()));
    }
}
