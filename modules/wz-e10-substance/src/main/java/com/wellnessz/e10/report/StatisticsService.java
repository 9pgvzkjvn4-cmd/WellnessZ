package com.wellnessz.e10.report;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.documents.PdfRenderer;
import com.wellnessz.e10.model.DaTest;
import com.wellnessz.e10.model.E10TestRepository;
import com.wellnessz.e10.model.SubstanceEnums.Reason;
import com.wellnessz.e10.model.SubstanceEnums.RoleGroup;
import com.wellnessz.e10.model.SubstanceEnums.TestStatus;
import com.wellnessz.e10.support.SubstanceSupport;
import com.wellnessz.security.audit.AuditService;

/**
 * Programme statistics and the anonymised annual regulatory report (E10-F05, E10-BL-009): completed tests by reason × role
 * group with verified positives and refusals; cells below {@code wellnessz.analytics.min-group-size} are suppressed. No
 * identifiers ever leave this service.
 */
@Service("e10StatisticsService")
public class StatisticsService {

    public static final String UNASSIGNED = "UNASSIGNED";

    private final E10TestRepository tests;
    private final PdfRenderer renderer;
    private final SubstanceSupport support;
    private final AuditService audit;

    public StatisticsService(E10TestRepository tests, PdfRenderer renderer, SubstanceSupport support, AuditService audit) {
        this.tests = tests;
        this.renderer = renderer;
        this.support = support;
        this.audit = audit;
    }

    /** Raw counts of one cell before suppression. */
    record Counts(int tests, int positives, int refusals) {
        Counts add(DaTest t) {
            return new Counts(tests + 1, positives + (TestStatus.VERIFIED_POSITIVE.name().equals(t.getStatus()) ? 1 : 0),
                    refusals + (TestStatus.REFUSAL.name().equals(t.getStatus()) ? 1 : 0));
        }
    }

    @Transactional(readOnly = true)
    public List<Suppression.Cell> statistics(int year) {
        List<Suppression.Cell> out = cells(year);
        audit.record("DA_STATISTICS_READ", "DA_PROGRAMME", String.valueOf(year), AuditService.Outcome.SUCCESS, "cells=" + out.size());
        return out;
    }

    List<Suppression.Cell> cells(int year) {
        Map<String, Map<String, Counts>> grid = new TreeMap<>(Comparator.comparingInt(r -> Reason.valueOf(r).ordinal()));
        for (DaTest t : tests.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(start(year), start(year + 1))) {
            if (!TestStatus.COMPLETED.contains(TestStatus.valueOf(t.getStatus()))) {
                continue;
            }
            String group = t.getRoleGroup() == null ? UNASSIGNED : t.getRoleGroup();
            grid.computeIfAbsent(t.getReason(), k -> new TreeMap<>(StatisticsService::groupOrder))
                    .merge(group, new Counts(0, 0, 0).add(t), (a, b) -> new Counts(a.tests() + b.tests(), a.positives() + b.positives(),
                            a.refusals() + b.refusals()));
        }
        List<Suppression.Cell> out = new ArrayList<>();
        grid.forEach((reason, groups) -> groups.forEach((group, c) ->
                out.add(Suppression.cell(reason, group, c.tests(), c.positives(), c.refusals(), support.minGroupSize()))));
        return out;
    }

    private static int groupOrder(String a, String b) {
        return Integer.compare(rank(a), rank(b));
    }

    private static int rank(String group) {
        return UNASSIGNED.equals(group) ? Integer.MAX_VALUE : RoleGroup.valueOf(group).ordinal();
    }

    private static java.time.Instant start(int year) {
        return LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** The anonymised annual report as CSV or PDF. */
    public record Report(byte[] content, String contentType, String filename) {
    }

    @Transactional(readOnly = true)
    public Report annual(int year, String format) {
        List<Suppression.Cell> cells = cells(year);
        audit.record("DA_ANNUAL_REPORT", "DA_PROGRAMME", String.valueOf(year), AuditService.Outcome.SUCCESS, format);
        if ("csv".equals(format)) {
            StringBuilder csv = new StringBuilder("year,reason,roleGroup,tests,verifiedPositive,refusals,positiveRatePct,suppressed\n");
            for (Suppression.Cell c : cells) {
                csv.append(year).append(',').append(c.reason()).append(',').append(c.roleGroup()).append(',')
                        .append(n(c.tests())).append(',').append(n(c.verifiedPositive())).append(',').append(n(c.refusals())).append(',')
                        .append(c.positiveRatePct() == null ? "" : c.positiveRatePct().toPlainString()).append(',').append(c.suppressed()).append('\n');
            }
            return new Report(csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv", "da-programme-report-" + year + ".csv");
        }
        List<PdfRenderer.Section> sections = new ArrayList<>();
        sections.add(new PdfRenderer.Section("Scope", "Drug and alcohol testing programme – calendar year " + year
                + ". Completed tests (verified negative, verified positive, refusal) by reason and role group. Cells with fewer than "
                + support.minGroupSize() + " tests are suppressed. The report contains no personal identifiers."));
        StringBuilder table = new StringBuilder();
        if (cells.isEmpty()) {
            table.append("No completed tests in the reporting period.");
        }
        for (Suppression.Cell c : cells) {
            table.append(c.reason()).append(" / ").append(c.roleGroup()).append(": ");
            if (c.suppressed()) {
                table.append("suppressed (small cell)");
            } else {
                table.append(c.tests()).append(" tests, ").append(c.verifiedPositive()).append(" verified positive, ").append(c.refusals())
                        .append(" refusals, positive rate ").append(c.positiveRatePct()).append(" %");
            }
            table.append('\n');
        }
        sections.add(new PdfRenderer.Section("Results by reason and role group", table.toString()));
        PdfRenderer.Rendered pdf = renderer.render("Drug & alcohol programme – annual report " + year, "DAP-" + year, 1, sections,
                "Anonymised regulatory report – restricted to programme roles.");
        return new Report(pdf.pdf(), "application/pdf", "da-programme-report-" + year + ".pdf");
    }

    private static String n(Integer v) {
        return v == null ? "" : v.toString();
    }
}
