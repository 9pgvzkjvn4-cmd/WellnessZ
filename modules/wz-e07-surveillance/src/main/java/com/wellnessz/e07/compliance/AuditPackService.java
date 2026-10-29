package com.wellnessz.e07.compliance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.PdfRenderer;
import com.wellnessz.e07.crew.CrewCertificateService;
import com.wellnessz.e07.model.AuditPack;
import com.wellnessz.e07.model.AuditPackRepository;
import com.wellnessz.e07.model.Completion;
import com.wellnessz.e07.model.CompletionRepository;
import com.wellnessz.e07.model.FollowUp;
import com.wellnessz.e07.model.FollowUpRepository;
import com.wellnessz.e07.model.Invitation;
import com.wellnessz.e07.model.InvitationRepository;
import com.wellnessz.e07.model.Programme;
import com.wellnessz.e07.programme.ProgrammeCatalog;
import com.wellnessz.e07.support.SurveillanceSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Audit packs (E07-F08): IOSA / CAA / food safety / labour inspectorate evidence for one programme and period – programme
 * definition, compliance figures, examinations, invitations and follow-ups – as PDF and CSV. AUDITOR and HSE_OFFICER always
 * receive anonymised packs (pseudonymous subject ids, no names, small groups suppressed).
 */
@Service
public class AuditPackService {

    public record Template(String code, String title, List<String> sections) {
    }

    static final List<Template> TEMPLATES = List.of(
            new Template("IOSA", "IOSA audit – occupational health surveillance (ORG / CAB / GRH)",
                    List.of("Programme definition", "Population and compliance", "Examinations in period", "Invitations and escalations",
                            "Follow-ups", "Crew medical certificates")),
            new Template("CAA", "Civil Aviation Authority – aeromedical oversight",
                    List.of("Programme definition", "Population and compliance", "Examinations in period", "Crew medical certificates", "Follow-ups")),
            new Template("FOOD_SAFETY", "Food safety authority – food handler fitness",
                    List.of("Programme definition", "Population and compliance", "Examinations in period", "Invitations and escalations")),
            new Template("LABOUR_INSPECTORATE", "Labour inspectorate – statutory health surveillance",
                    List.of("Programme definition", "Population and compliance", "Examinations in period", "Follow-ups",
                            "Regulatory notifications")));

    private final AuditPackRepository packs;
    private final ProgrammeCatalog catalog;
    private final ComplianceService compliance;
    private final CompletionRepository completions;
    private final InvitationRepository invitations;
    private final FollowUpRepository followUps;
    private final PdfRenderer renderer;
    private final SurveillanceSupport support;
    private final AuditService audit;

    public AuditPackService(AuditPackRepository packs, ProgrammeCatalog catalog, ComplianceService compliance, CompletionRepository completions,
                            InvitationRepository invitations, FollowUpRepository followUps, PdfRenderer renderer, SurveillanceSupport support,
                            AuditService audit) {
        this.packs = packs;
        this.catalog = catalog;
        this.compliance = compliance;
        this.completions = completions;
        this.invitations = invitations;
        this.followUps = followUps;
        this.renderer = renderer;
        this.support = support;
        this.audit = audit;
    }

    public List<Template> templates() {
        return TEMPLATES;
    }

    public record PackFile(String format, String path) {
    }

    public record PackView(UUID id, Instant generatedAt, String programmeCode, String template, boolean anonymised, List<PackFile> files) {
    }

    static boolean forcedAnonymous(CurrentUser user) {
        return !user.hasAnyRole(Roles.CMO, Roles.NURSING_HEAD);
    }

    @Transactional
    public PackView generate(CurrentUser user, String template, String programmeCode, LocalDate from, LocalDate to, boolean anonymise) {
        Template t = TEMPLATES.stream().filter(x -> x.code().equals(template)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("unknown_template", "Unknown audit pack template"));
        Programme p = catalog.current(programmeCode).orElseThrow(() -> ApiException.notFound("Programme"));
        if (from == null || to == null || from.isAfter(to) || from.plusYears(3).isBefore(to)) {
            throw ApiException.badRequest("invalid_window", "The audit period must be between 1 day and 3 years");
        }
        boolean anonymised = anonymise || forcedAnonymous(user);
        UUID id = UUID.randomUUID();
        int k = support.minGroupSize();
        List<Completion> done = completions.findByProgrammeCodeAndPerformedOnBetweenOrderByPerformedOnAsc(p.getCode(), from, to);
        Instant fromI = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toI = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<FollowUp> fus = followUps.findByProgrammeCodeAndCreatedAtBetween(p.getCode(), fromI, toI);
        ComplianceService.Compliance c = compliance.compliance(user, "PROGRAMME", p.getCode());
        List<Invitation> inv = invitations.findBySentAtBetween(fromI, toI);
        Map<String, Employee> emps = support.employeesById(done.stream().map(Completion::getEmployeeId).distinct().toList());

        List<PdfRenderer.Section> sections = new ArrayList<>();
        sections.add(new PdfRenderer.Section("Programme definition", "Programme " + p.getCode() + " version " + p.getVersion() + " (" + p.getStatus()
                + "): " + p.getTitle() + ". Frequency " + p.getFrequencyMonths() + " months"
                + (p.getFrequencyMonthsOver40() == null ? "" : " (" + p.getFrequencyMonthsOver40() + " months from age 40)")
                + ". Panel: " + String.join(", ", catalog.panel(p).stream().map(ProgrammeCatalog.PanelItem::test).toList())
                + ". Responsible role: " + p.getResponsibleRole() + ". Regulatory basis: " + (p.getRegulatoryBasis() == null ? "n/a" : p.getRegulatoryBasis())
                + ". Approved by " + (p.getApprovedBy() == null ? "n/a" : p.getApprovedBy()) + "."));
        StringBuilder comp = new StringBuilder();
        for (ComplianceMaths.Row r : c.rows()) {
            comp.append(r.label()).append(": ").append(r.suppressed() ? "fewer than " + k + " people (suppressed)"
                    : r.enrolled() + " enrolled, " + r.current() + " current, " + r.due() + " due, " + r.overdue() + " overdue, compliance "
                    + r.compliancePct() + " %").append(". ");
        }
        sections.add(new PdfRenderer.Section("Population and compliance", comp.isEmpty() ? "No enrolments." : comp.toString().strip()));
        Map<String, Integer> outcomes = new TreeMap<>();
        done.forEach(d -> outcomes.merge(d.getOutcome(), 1, Integer::sum));
        sections.add(new PdfRenderer.Section("Examinations in period", from + " to " + to + ": " + done.size() + " examinations recorded"
                + (outcomes.isEmpty() ? "." : " (" + summary(outcomes, anonymised, k) + ").")));
        if (t.sections().contains("Invitations and escalations")) {
            Map<String, Integer> byStatus = new TreeMap<>();
            inv.forEach(i -> byStatus.merge(i.getStatus(), 1, Integer::sum));
            sections.add(new PdfRenderer.Section("Invitations and escalations", inv.isEmpty() ? "No invitations sent in the period."
                    : inv.size() + " invitations sent across all programmes (" + summary(byStatus, false, 0) + ")."));
        }
        if (t.sections().contains("Follow-ups") || t.sections().contains("Regulatory notifications")) {
            Map<String, Integer> byRule = new TreeMap<>();
            fus.forEach(f -> byRule.merge(f.getRuleCode() + " " + f.getStatus(), 1, Integer::sum));
            long regulatory = fus.stream().filter(FollowUp::isRegulatoryNotificationRequired).count();
            sections.add(new PdfRenderer.Section("Follow-ups", fus.size() + " follow-ups raised" + (byRule.isEmpty() ? "" : " (" + summary(byRule, anonymised, k) + ")")
                    + "; " + regulatory + " flagged for regulatory notification assessment."));
        }
        if (t.sections().contains("Crew medical certificates")) {
            sections.add(new PdfRenderer.Section("Crew medical certificates",
                    "Certificate validity is tracked per crew member with alerts at 90, 60 and 30 days and roster status UNFIT on expiry; "
                            + "see the crew certificate register for the current list."));
        }
        sections.add(new PdfRenderer.Section("Disclosure control", anonymised
                ? "Anonymised pack: subjects are pseudonymous, names are omitted and groups below " + k + " people are suppressed."
                : "Identifiable pack for the CMO / nursing head: handle as medical-in-confidence."));

        StringBuilder csv = new StringBuilder("recordType,subject,subsidiaryCode,departmentCode,date,outcome\n");
        for (Completion d : done) {
            Employee e = emps.get(d.getEmployeeId());
            csv.append("EXAMINATION,").append(CrewCertificateService.csvCell(anonymised ? pseudonym(id, d.getEmployeeId()) : d.getEmployeeId()))
                    .append(',').append(CrewCertificateService.csvCell(e == null ? "" : e.getSubsidiaryCode()))
                    .append(',').append(anonymised ? "" : CrewCertificateService.csvCell(e == null ? "" : e.getDepartmentCode()))
                    .append(',').append(d.getPerformedOn()).append(',').append(d.getOutcome()).append('\n');
        }
        for (FollowUp f : fus) {
            csv.append("FOLLOW_UP,").append(CrewCertificateService.csvCell(anonymised ? pseudonym(id, f.getEmployeeId()) : f.getEmployeeId()))
                    .append(",,,").append(LocalDate.ofInstant(f.getCreatedAt(), ZoneOffset.UTC)).append(',').append(f.getRuleCode()).append(' ')
                    .append(f.getStatus()).append('\n');
        }
        String number = "AP-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        PdfRenderer.Rendered pdf = renderer.render(t.title() + " - " + p.getTitle(), number, 1, sections,
                anonymised ? "Anonymised audit pack - contains no identifiable health information" : "CONFIDENTIAL - Occupational Health");
        AuditPack pack = new AuditPack();
        pack.setId(id);
        pack.setTemplate(t.code());
        pack.setProgrammeCode(p.getCode());
        pack.setFromDate(from);
        pack.setToDate(to);
        pack.setAnonymised(anonymised);
        pack.setPdf(pdf.pdf());
        pack.setCsv(csv.toString());
        pack.setGeneratedBy(user.username());
        pack.setGeneratedAt(support.now());
        packs.save(pack);
        audit.record("SURVEILLANCE_AUDIT_PACK", "AUDIT_PACK", id.toString(), AuditService.Outcome.SUCCESS,
                t.code() + " " + p.getCode() + (anonymised ? " anonymised" : " identifiable"));
        return view(pack);
    }

    static String summary(Map<String, Integer> counts, boolean suppress, int k) {
        List<String> parts = new ArrayList<>();
        counts.forEach((key, n) -> parts.add(key + " " + (suppress && n < k ? "<" + k : String.valueOf(n))));
        return String.join(", ", parts);
    }

    static String pseudonym(UUID packId, String employeeId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((packId + "|" + employeeId).getBytes(StandardCharsets.UTF_8));
            return "P-" + HexFormat.of().formatHex(h).substring(0, 10).toUpperCase(java.util.Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static PackView view(AuditPack p) {
        String base = "/api/v1/surveillance/audit-packs/" + p.getId() + "/content?format=";
        return new PackView(p.getId(), p.getGeneratedAt(), p.getProgrammeCode(), p.getTemplate(), p.isAnonymised(),
                List.of(new PackFile("pdf", base + "pdf"), new PackFile("csv", base + "csv")));
    }

    public record Content(String fileName, String contentType, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public Content content(CurrentUser user, UUID id, String format) {
        AuditPack p = packs.findById(id).orElseThrow(() -> ApiException.notFound("Audit pack"));
        if (!p.isAnonymised() && forcedAnonymous(user)) {
            audit.record("SURVEILLANCE_AUDIT_PACK_READ", "AUDIT_PACK", id.toString(), AuditService.Outcome.DENIED, "identifiable pack");
            throw ApiException.notFound("Audit pack");
        }
        audit.record("SURVEILLANCE_AUDIT_PACK_READ", "AUDIT_PACK", id.toString(), AuditService.Outcome.SUCCESS, format);
        String name = "audit-pack-" + p.getTemplate().toLowerCase(java.util.Locale.ROOT) + "-" + p.getProgrammeCode().toLowerCase(java.util.Locale.ROOT);
        return "csv".equals(format) ? new Content(name + ".csv", "text/csv", p.getCsv().getBytes(StandardCharsets.UTF_8))
                : new Content(name + ".pdf", "application/pdf", p.getPdf());
    }
}
