package com.wellnessz.e06.lta;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.wellnessz.e06.model.AbsenceEnums.MilestoneType;

/**
 * Long-term absence milestone plan (E06-F04), relative to the first day of absence: week 2 contact, week 4 advice report,
 * week 8 advice report + case conference, week 12 HR review + advice report, week 26 ill-health retirement consideration.
 * The contact schedule after the first contact is fortnightly ({@code nextContactDue}).
 */
public final class MilestonePlan {

    public record Planned(int week, MilestoneType type, LocalDate dueDate) {
    }

    static final List<Object[]> PLAN = List.of(
            new Object[]{2, MilestoneType.CONTACT},
            new Object[]{4, MilestoneType.ADVICE_REPORT},
            new Object[]{8, MilestoneType.ADVICE_REPORT},
            new Object[]{8, MilestoneType.CASE_CONFERENCE},
            new Object[]{12, MilestoneType.HR_REVIEW},
            new Object[]{12, MilestoneType.ADVICE_REPORT},
            new Object[]{26, MilestoneType.IHR_CONSIDERATION});

    private MilestonePlan() {
    }

    public static List<Planned> plan(LocalDate absenceStart) {
        return PLAN.stream().map(p -> new Planned((Integer) p[0], (MilestoneType) p[1], absenceStart.plusWeeks((Integer) p[0]))).toList();
    }

    /** The RTW review milestone created when the absence closes: due on the return date, week counted from the absence start. */
    public static Planned rtwReview(LocalDate absenceStart, LocalDate returnDate) {
        int week = (int) Math.max(1, (ChronoUnit.DAYS.between(absenceStart, returnDate) + 6) / 7);
        return new Planned(week, MilestoneType.RTW_REVIEW, returnDate);
    }

    /** Whole weeks absent up to {@code asOf}. */
    public static long weeksAbsent(LocalDate absenceStart, LocalDate asOf) {
        return Math.max(0, ChronoUnit.DAYS.between(absenceStart, asOf) / 7);
    }
}
