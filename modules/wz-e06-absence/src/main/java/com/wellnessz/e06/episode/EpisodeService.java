package com.wellnessz.e06.episode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseLink;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e06.lta.LtaService;
import com.wellnessz.e06.model.AbsenceEnums.QueueStatus;
import com.wellnessz.e06.model.Certificate;
import com.wellnessz.e06.model.AbsenceCertificateRepository;
import com.wellnessz.e06.model.Episode;
import com.wellnessz.e06.model.E06EpisodeRepository;
import com.wellnessz.e06.model.AbsenceQueueItemRepository;
import com.wellnessz.e06.rules.AbsenceFacts;
import com.wellnessz.e06.rules.AbsencePatterns;
import com.wellnessz.e06.rules.RuleEngine;
import com.wellnessz.e06.support.AbsenceSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Absence history (E06-F01): OH sees episodes with the reason category; HR and managers in scope see status and dates
 * only; employees see their own episodes. Summary statistics (Bradford factor, patterns) for OH and HR; team view for
 * managers / HR; export of an episode to a case.
 */
@Service("absenceEpisodeService")
public class EpisodeService {

    static final Set<String> ENGAGED = Set.of(QueueStatus.ACCEPTED.name(), QueueStatus.CONTACTED.name());

    private final E06EpisodeRepository episodes;
    private final AbsenceCertificateRepository certificates;
    private final AbsenceQueueItemRepository queue;
    private final EpisodeViews views;
    private final LtaService lta;
    private final CaseService cases;
    private final CaseAccess caseAccess;
    private final AbsenceSupport support;
    private final AuditService audit;

    public EpisodeService(E06EpisodeRepository episodes, AbsenceCertificateRepository certificates, AbsenceQueueItemRepository queue, EpisodeViews views,
                          LtaService lta, CaseService cases, CaseAccess caseAccess, AbsenceSupport support, AuditService audit) {
        this.episodes = episodes;
        this.certificates = certificates;
        this.queue = queue;
        this.views = views;
        this.lta = lta;
        this.cases = cases;
        this.caseAccess = caseAccess;
        this.support = support;
        this.audit = audit;
    }

    public record OpenEpisode(LocalDate startDate, long days) {
    }

    public record Summary(String employeeId, int episodes12m, long daysLost12m, long bradfordFactor, long longestDays, OpenEpisode openEpisode,
                          List<String> patterns, UUID activeLtaCaseId) {
    }

    public record TeamMember(String employeeId, String displayName, OpenEpisode openEpisode, int episodes12m, boolean ohEngaged,
                             String certificateValidity) {
    }

    @Transactional(readOnly = true)
    public List<EpisodeViews.EpisodeView> forEmployee(CurrentUser user, String employeeId, LocalDate from, LocalDate to) {
        boolean oh = AbsenceSupport.isOh(user);
        if (oh) {
            support.requireClinical(user, employeeId, "ABSENCE_EPISODES_READ");
        } else {
            support.requireInScope(user, employeeId, "ABSENCE_EPISODES_READ");
        }
        List<Episode> list = episodes.findByEmployeeIdOrderByStartDateDesc(employeeId).stream()
                .filter(e -> to == null || !e.getStartDate().isAfter(to))
                .filter(e -> from == null || e.getEndDate() == null || !e.getEndDate().isBefore(from)).toList();
        audit.record("ABSENCE_EPISODES_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, oh ? "clinical" : "status-only");
        return views.views(list, oh);
    }

    @Transactional(readOnly = true)
    public List<EpisodeViews.EpisodeView> mine(CurrentUser user) {
        if (user.employeeId() == null) {
            return List.of();
        }
        audit.record("ABSENCE_EPISODES_SELF_READ", "EMPLOYEE", user.employeeId(), AuditService.Outcome.SUCCESS, null);
        return views.views(episodes.findByEmployeeIdOrderByStartDateDesc(user.employeeId()), false);
    }

    @Transactional(readOnly = true)
    public Summary summary(CurrentUser user, String employeeId) {
        if (AbsenceSupport.isOh(user)) {
            support.requireClinical(user, employeeId, "ABSENCE_SUMMARY_READ");
        } else if (user.hasRole(Roles.HR)) {
            support.requireInScope(user, employeeId, "ABSENCE_SUMMARY_READ");
        } else {
            throw ApiException.forbidden("Occupational Health and HR only");
        }
        audit.record("ABSENCE_SUMMARY_READ", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, null);
        return summary(employeeId);
    }

    Summary summary(String employeeId) {
        LocalDate today = support.today();
        List<Episode> all = episodes.findByEmployeeIdOrderByStartDateDesc(employeeId);
        List<RuleEngine.EpisodeFact> facts = all.stream().map(AbsenceFacts::fact).toList();
        List<RuleEngine.EpisodeFact> year = AbsencePatterns.lastYear(facts, today);
        long days = year.stream().mapToLong(e -> AbsencePatterns.daysWithin(e, today.minusDays(AbsencePatterns.WINDOW_DAYS - 1L), today)).sum();
        long longest = year.stream().mapToLong(e -> e.days(today)).max().orElse(0);
        OpenEpisode open = open(all, today).orElse(null);
        return new Summary(employeeId, year.size(), days, AbsencePatterns.bradford(facts, today), longest, open,
                AbsencePatterns.patterns(facts, today, support.publicHolidays()),
                lta.active(employeeId).map(p -> p.getCaseId()).orElse(null));
    }

    private static Optional<OpenEpisode> open(List<Episode> all, LocalDate today) {
        return all.stream().filter(e -> e.getEndDate() == null && !e.getStartDate().isAfter(today))
                .min(Comparator.comparing(Episode::getStartDate))
                .map(e -> new OpenEpisode(e.getStartDate(), AbsenceFacts.fact(e).days(today)));
    }

    @Transactional
    public EpisodeViews.EpisodeView linkCase(CurrentUser user, UUID episodeId, UUID caseId) {
        Episode e = episodes.findById(episodeId).orElseThrow(() -> ApiException.notFound("Episode"));
        support.requireClinicalUpdate(user, e.getEmployeeId(), "ABSENCE_EPISODE_LINK");
        OhCase c = caseAccess.clinical(user, caseId, AccessPolicy.Action.UPDATE, "ABSENCE_EPISODE_LINK");
        if (!c.getEmployeeId().equals(e.getEmployeeId())) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "case_employee_mismatch",
                    "The case belongs to a different employee");
        }
        e.setCaseId(caseId);
        e.setUpdatedAt(support.now());
        cases.link(user, caseId, CaseLink.Type.ABSENCE, episodeId.toString());
        audit.record("ABSENCE_EPISODE_LINK", "ABSENCE_EPISODE", episodeId.toString(), AuditService.Outcome.SUCCESS, caseId.toString());
        return views.views(List.of(e), true).get(0);
    }

    @Transactional(readOnly = true)
    public List<TeamMember> team(CurrentUser user) {
        if (!user.hasAnyRole(Roles.MANAGER, Roles.HR)) {
            throw ApiException.forbidden("Managers and HR only");
        }
        LocalDate today = support.today();
        List<TeamMember> out = new ArrayList<>();
        for (Employee e : support.employeesInScope(user)) {
            List<Episode> all = episodes.findByEmployeeIdOrderByStartDateDesc(e.getEmployeeId());
            List<RuleEngine.EpisodeFact> year = AbsencePatterns.lastYear(all.stream().map(AbsenceFacts::fact).toList(), today);
            out.add(new TeamMember(e.getEmployeeId(), e.getDisplayName(), open(all, today).orElse(null), year.size(), ohEngaged(e.getEmployeeId()),
                    certificateValidity(e.getEmployeeId(), all, today)));
            audit.record("ABSENCE_TEAM_READ", "EMPLOYEE", e.getEmployeeId(), AuditService.Outcome.SUCCESS, "status-only");
        }
        return out;
    }

    /** OH is engaged: an accepted / contacted queue item or an active long-term absence pathway. */
    public boolean ohEngaged(String employeeId) {
        return lta.active(employeeId).isPresent()
                || queue.findByEmployeeIdOrderByRaisedAtDesc(employeeId).stream().anyMatch(q -> ENGAGED.contains(q.getStatus()));
    }

    /** Validity of the certificate(s) of the open episode, else of the most recent certificate within 12 months. */
    private String certificateValidity(String employeeId, List<Episode> all, LocalDate today) {
        Optional<Episode> open = all.stream().filter(e -> e.getEndDate() == null).findFirst();
        if (open.isPresent()) {
            String v = views.validity(open.get());
            if (v != null) {
                return v;
            }
        }
        List<Certificate> recent = certificates.findByEmployeeIdOrderByFromDateDesc(employeeId).stream()
                .filter(c -> !c.getToDate().isBefore(today.minusDays(365))).toList();
        return recent.isEmpty() ? null : com.wellnessz.e06.model.AbsenceEnums.validity(recent.get(0).getStatus()).name();
    }
}
