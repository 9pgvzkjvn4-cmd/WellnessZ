package com.wellnessz.e06.lta;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wellnessz.e06.model.AbsenceEnums.MilestoneType;

class MilestonePlanTest {

    static final LocalDate START = LocalDate.of(2026, 9, 1);

    @Test
    void plannedRelativeToTheFirstDayOfAbsence() {
        List<MilestonePlan.Planned> plan = MilestonePlan.plan(START);
        assertThat(plan).extracting(MilestonePlan.Planned::week).containsExactly(2, 4, 8, 8, 12, 12, 26);
        assertThat(plan).extracting(MilestonePlan.Planned::type).containsExactly(MilestoneType.CONTACT, MilestoneType.ADVICE_REPORT,
                MilestoneType.ADVICE_REPORT, MilestoneType.CASE_CONFERENCE, MilestoneType.HR_REVIEW, MilestoneType.ADVICE_REPORT,
                MilestoneType.IHR_CONSIDERATION);
        assertThat(plan.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(plan.get(6).dueDate()).isEqualTo(START.plusWeeks(26));
    }

    @Test
    void rtwReviewAndWeeksAbsent() {
        MilestonePlan.Planned rtw = MilestonePlan.rtwReview(START, START.plusDays(45));
        assertThat(rtw.type()).isEqualTo(MilestoneType.RTW_REVIEW);
        assertThat(rtw.dueDate()).isEqualTo(START.plusDays(45));
        assertThat(rtw.week()).isEqualTo(7);
        assertThat(MilestonePlan.rtwReview(START, START.plusDays(1)).week()).isEqualTo(1);
        assertThat(MilestonePlan.weeksAbsent(START, START.plusDays(41))).isEqualTo(5);
        assertThat(MilestonePlan.weeksAbsent(START, START.plusDays(42))).isEqualTo(6);
        assertThat(MilestonePlan.weeksAbsent(START, START.minusDays(3))).isZero();
    }

    @Test
    void conferenceTimesAcceptDatesAndDateTimes() {
        assertThat(LtaService.parseWhen("2026-11-05").toString()).isEqualTo("2026-11-05T09:00:00Z");
        assertThat(LtaService.parseWhen("2026-11-05T10:30:00Z").toString()).isEqualTo("2026-11-05T10:30:00Z");
        assertThat(LtaService.parseWhen("2026-11-05T10:30:00-05:00").toString()).isEqualTo("2026-11-05T15:30:00Z");
        assertThat(LtaService.parseWhen("2026-11-05T10:30").toString()).isEqualTo("2026-11-05T10:30:00Z");
    }
}
