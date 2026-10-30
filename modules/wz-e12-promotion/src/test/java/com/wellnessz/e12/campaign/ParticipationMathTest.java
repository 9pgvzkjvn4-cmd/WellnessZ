package com.wellnessz.e12.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wellnessz.e12.ergonomics.ErgonomicsService;
import com.wellnessz.e12.model.CampaignInvitation;
import com.wellnessz.e12.model.ErgonomicAction;

class ParticipationMathTest {

    static CampaignInvitation inv(String employee, String unit) {
        CampaignInvitation i = new CampaignInvitation();
        i.setId(UUID.randomUUID());
        i.setEmployeeId(employee);
        i.setUnitCode(unit);
        return i;
    }

    static List<CampaignInvitation> population(String unit, int n) {
        List<CampaignInvitation> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(inv(unit + i, unit));
        }
        return out;
    }

    @Test
    void participationPerUnitWithSuppression() {
        List<CampaignInvitation> invited = new ArrayList<>();
        invited.addAll(population("GROUND", 10));
        invited.addAll(population("CATERING", 8));
        invited.addAll(population("CARGO", 3));
        Set<String> participants = Set.of("GROUND0", "GROUND1", "GROUND2", "GROUND3", "GROUND4", "GROUND5", "CATERING0", "CATERING1", "CATERING2",
                "CATERING3", "CATERING4", "CARGO0", "UNINVITED");
        List<PromotionAnalyticsService.ParticipationRow> rows = PromotionAnalyticsService.participation(invited, participants,
                CampaignInvitation::getUnitCode, 5);
        assertThat(rows).extracting(PromotionAnalyticsService.ParticipationRow::key).containsExactly("CARGO", "CATERING", "GROUND");
        // CARGO (3 invited) is hidden; CATERING (8, smallest visible) is hidden as the complementary cell
        assertThat(rows.get(0).suppressed()).isTrue();
        assertThat(rows.get(0).pct()).isNull();
        assertThat(rows.get(1).suppressed()).isTrue();
        assertThat(rows.get(1).invited()).isNull();
        PromotionAnalyticsService.ParticipationRow ground = rows.get(2);
        assertThat(ground.invited()).isEqualTo(10L);
        assertThat(ground.participated()).isEqualTo(6L);
        assertThat(ground.pct()).isEqualByComparingTo("60.0");
    }

    @Test
    void totalRowAndSmallParticipantCounts() {
        assertThat(PromotionAnalyticsService.row("TOTAL", 21, 12, 5).pct()).isEqualByComparingTo("57.1");
        assertThat(PromotionAnalyticsService.row("X", 30, 2, 5).suppressed()).isTrue();
        assertThat(PromotionAnalyticsService.row("X", 30, 0, 5).pct()).isEqualByComparingTo("0.0");
    }

    @Test
    void outcomeRowsNeedAMinimumGroup() {
        List<BigDecimal> four = List.of(BigDecimal.ONE, BigDecimal.TWO, BigDecimal.TEN, BigDecimal.ONE);
        assertThat(PromotionAnalyticsService.outcome("bmi", four, four, 5).suppressed()).isTrue();
        PromotionAnalyticsService.OutcomeRow r = PromotionAnalyticsService.outcome("systolic",
                List.of(new BigDecimal("150"), new BigDecimal("140"), new BigDecimal("145")),
                List.of(new BigDecimal("138"), new BigDecimal("136"), new BigDecimal("134")), 3);
        assertThat(r.baseline()).isEqualByComparingTo("145");
        assertThat(r.current()).isEqualByComparingTo("136");
        assertThat(r.delta()).isEqualByComparingTo("-9");
        assertThat(r.n()).isEqualTo(3L);
    }

    @Test
    void groupByIsNormalised() {
        assertThat(PromotionAnalyticsService.normaliseGroupBy(null)).isEqualTo("unit");
        assertThat(PromotionAnalyticsService.normaliseGroupBy("JOB_CODE")).isEqualTo("jobCode");
        assertThat(PromotionAnalyticsService.normaliseGroupBy("Subsidiary")).isEqualTo("subsidiary");
    }

    @Test
    void csvCellsAreQuotedAndNeutralised() {
        assertThat(PromotionAnalyticsService.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(PromotionAnalyticsService.cell("GROUND")).isEqualTo("\"GROUND\"");
    }

    static ErgonomicAction action(LocalDate created, Integer closedAfterDays, LocalDate due) {
        ErgonomicAction a = new ErgonomicAction();
        a.setCreatedAt(created.atStartOfDay().toInstant(ZoneOffset.UTC));
        a.setDueDate(due);
        if (closedAfterDays != null) {
            a.setStatus("DONE");
            a.setClosedAt(created.plusDays(closedAfterDays).atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(60));
        } else {
            a.setStatus("OPEN");
        }
        return a;
    }

    @Test
    void ergonomicClosureKpi() {
        LocalDate today = LocalDate.of(2026, 10, 30);
        List<ErgonomicAction> list = List.of(
                action(today.minusDays(100), 30, today.minusDays(70)),  // closed in time
                action(today.minusDays(100), 75, today.minusDays(70)),  // closed late
                action(today.minusDays(90), null, today.minusDays(5)),  // open beyond window, overdue
                action(today.minusDays(10), null, today.plusDays(20)),  // open, window not elapsed – not yet eligible
                action(today.minusDays(5), 2, today.plusDays(20)));     // closed early
        ErgonomicsService.Kpi kpi = ErgonomicsService.kpi(list, today, 60, 80);
        assertThat(kpi.actions()).isEqualTo(5);
        assertThat(kpi.open()).isEqualTo(2);
        assertThat(kpi.overdue()).isEqualTo(1);
        assertThat(kpi.eligible()).isEqualTo(4);
        assertThat(kpi.closedWithinTarget()).isEqualTo(2);
        assertThat(kpi.pct()).isEqualByComparingTo("50.0");
    }
}
