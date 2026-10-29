package com.wellnessz.e05.crew;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.FitnessPublicationListener;
import com.wellnessz.casekernel.model.CaseEvents;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e02.safety.CrewStatusEnricher;
import com.wellnessz.e05.model.CrewProfile;
import com.wellnessz.e05.model.CrewProfileRepository;
import com.wellnessz.e05.model.RosterFeedMessage;
import com.wellnessz.e05.model.RosterFeedMessageRepository;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Crew roster-status feed log (E05-BL-012/013): every status-only message rostering receives, its acknowledgement and
 * the 15-minute reconciliation; the status-only enrichment of the crew-scheduling view (E02 SPI) and the CMO dashboard
 * with small-cell suppression. Deliberately depends on nothing that depends on the case kernel (it is a kernel listener).
 */
@Component
public class RosterFeedLog implements FitnessPublicationListener, CrewStatusEnricher {

    static final Duration ACK_SLA = Duration.ofMinutes(15);
    static final int MIN_CELL = 5;

    private final RosterFeedMessageRepository feed;
    private final CrewProfileRepository profiles;
    private final FitnessSupport support;
    private final AuditService audit;

    public RosterFeedLog(RosterFeedMessageRepository feed, CrewProfileRepository profiles, FitnessSupport support, AuditService audit) {
        this.feed = feed;
        this.profiles = profiles;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ roster feed log (E05-BL-012)

    /** Called by the kernel inside the publishing transaction: logs the message for acknowledgement and reconciliation. */
    @Override
    public void rosterStatusPublished(OhCase c, CaseEvents.RosterStatusChanged m) {
        RosterFeedMessage r = new RosterFeedMessage();
        r.setMessageId(m.messageId());
        r.setEmployeeId(m.employeeId());
        r.setCaseId(c.getId());
        r.setFitnessStatus(m.fitnessStatus());
        r.setRosterStatus(m.rosterStatus());
        r.setValidFrom(m.validFrom());
        r.setValidUntil(m.validUntil());
        r.setOperationalLimitations(m.operationalLimitations());
        r.setSentAt(support.clock().instant());
        feed.save(r);
    }

    public record FeedMessage(String messageId, String employeeId, String rosterStatus, LocalDate validFrom, LocalDate validUntil,
                              List<String> operationalLimitations, Instant sentAt, Instant ackAt) {
    }

    @Transactional(readOnly = true)
    public List<FeedMessage> messages(Instant since) {
        Instant from = since == null ? support.clock().instant().minus(Duration.ofDays(1)) : since;
        List<FeedMessage> out = feed.findBySentAtGreaterThanEqualOrderBySentAtAsc(from, PageRequest.of(0, 1000)).stream()
                .map(RosterFeedLog::message).toList();
        audit.record("ROSTER_FEED_READ", "ROSTER_FEED", null, AuditService.Outcome.SUCCESS, "messages=" + out.size());
        return out;
    }

    @Transactional
    public FeedMessage acknowledge(CurrentUser user, String messageId) {
        RosterFeedMessage m = feed.findById(messageId).orElseThrow(() -> ApiException.notFound("Roster message"));
        if (m.getAckAt() == null) {
            m.setAckAt(support.clock().instant());
            m.setAckBy(user.username());
            audit.record("ROSTER_FEED_ACK", "ROSTER_FEED", messageId, AuditService.Outcome.SUCCESS,
                    "latencySeconds=" + Duration.between(m.getSentAt(), m.getAckAt()).toSeconds());
        }
        return message(m);
    }

    public record Outstanding(String messageId, String employeeId, String rosterStatus, Instant sentAt, long minutesOutstanding) {
    }

    public record Reconciliation(Instant from, Instant to, long sent, long acknowledged, List<Outstanding> unacknowledgedOver15Min,
                                 Long p95LatencySeconds) {
    }

    @Transactional(readOnly = true)
    public Reconciliation reconciliation(Instant from, Instant to) {
        Instant now = support.clock().instant();
        Instant end = to == null ? now : to;
        Instant start = from == null ? end.minus(Duration.ofDays(1)) : from;
        if (!start.isBefore(end) || Duration.between(start, end).toDays() > 31) {
            throw ApiException.badRequest("invalid_window", "The reconciliation window must be between 1 second and 31 days");
        }
        List<RosterFeedMessage> msgs = feed.findBySentAtBetweenOrderBySentAtAsc(start, end);
        List<Long> latencies = new ArrayList<>();
        List<Outstanding> outstanding = new ArrayList<>();
        for (RosterFeedMessage m : msgs) {
            if (m.getAckAt() != null) {
                latencies.add(Duration.between(m.getSentAt(), m.getAckAt()).toSeconds());
            } else if (m.getSentAt().plus(ACK_SLA).isBefore(now)) {
                outstanding.add(new Outstanding(m.getMessageId(), m.getEmployeeId(), m.getRosterStatus(), m.getSentAt(),
                        Duration.between(m.getSentAt(), now).toMinutes()));
            }
        }
        audit.record("ROSTER_FEED_RECONCILIATION", "ROSTER_FEED", null, AuditService.Outcome.SUCCESS,
                "sent=" + msgs.size() + " outstanding=" + outstanding.size());
        return new Reconciliation(start, end, msgs.size(), latencies.size(), outstanding, p95(latencies));
    }

    /** Nearest-rank 95th percentile; {@code null} when nothing was acknowledged. */
    static Long p95(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        int rank = (int) Math.ceil(0.95 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    /** Flags (audits) messages that rostering has not acknowledged within 15 minutes; the outbox keeps retrying delivery. */
    @Scheduled(fixedDelayString = "${wellnessz.fitness.roster-reconciliation-interval:PT5M}")
    @Transactional
    public void flagUnacknowledged() {
        Instant now = support.clock().instant();
        for (RosterFeedMessage m : feed.findByAckAtIsNullAndAlertedAtIsNullAndSentAtBefore(now.minus(ACK_SLA))) {
            m.setAlertedAt(now);
            audit.recordAs("system", "SYSTEM", "ROSTER_FEED_UNACKNOWLEDGED", "ROSTER_FEED", m.getMessageId(), AuditService.Outcome.FAILURE,
                    "not acknowledged within 15 minutes");
        }
    }

    private static FeedMessage message(RosterFeedMessage m) {
        return new FeedMessage(m.getMessageId(), m.getEmployeeId(), m.getRosterStatus(), m.getValidFrom(), m.getValidUntil(),
                split(m.getOperationalLimitations()), m.getSentAt(), m.getAckAt());
    }

    static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.split(","));
    }

    // ------------------------------------------------------------------ crew-scheduling status enrichment (E02 SPI)

    @Override
    @Transactional(readOnly = true)
    public Map<String, RosterInfo> rosterInfo(Collection<String> employeeIds) {
        Map<String, RosterInfo> out = new LinkedHashMap<>();
        if (employeeIds.isEmpty()) {
            return out;
        }
        for (RosterFeedMessage m : feed.findByEmployeeIdInOrderBySentAtDesc(employeeIds)) {
            out.putIfAbsent(m.getEmployeeId(), new RosterInfo(m.getRosterStatus(), m.getValidFrom(), m.getValidUntil(),
                    split(m.getOperationalLimitations())));
        }
        return out;
    }

    // ------------------------------------------------------------------ CMO dashboard (E05-BL-013)

    @Transactional(readOnly = true)
    public CrewDashboard.Result dashboard(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? support.today() : to;
        LocalDate start = from == null ? end.minusDays(90) : from;
        if (start.isAfter(end) || start.plusDays(366).isBefore(end)) {
            throw ApiException.badRequest("invalid_window", "The dashboard window must be between 1 and 366 days");
        }
        Instant endExclusive = end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<CrewDashboard.Status> history = feed.findBySentAtLessThanOrderByEmployeeIdAscSentAtAsc(endExclusive).stream()
                .map(m -> new CrewDashboard.Status(m.getEmployeeId(), m.getRosterStatus(), m.getSentAt())).toList();
        Map<String, String[]> attributes = new LinkedHashMap<>();
        history.stream().map(CrewDashboard.Status::employeeId).distinct().forEach(id -> {
            CrewProfile p = profiles.findById(id).orElse(null);
            String base = p != null ? p.getBase() : support.employee(id).map(Employee::getLocationCode).orElse("UNKNOWN");
            attributes.put(id, new String[] {base == null ? "UNKNOWN" : base, p != null ? p.getFleet() : "UNKNOWN"});
        });
        CrewDashboard.Result r = CrewDashboard.compute(history, attributes, start, end, MIN_CELL);
        audit.record("CREW_DASHBOARD_READ", "CREW", null, AuditService.Outcome.SUCCESS, start + ".." + end);
        return r;
    }
}
