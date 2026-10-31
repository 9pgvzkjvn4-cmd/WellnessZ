package com.wellnessz.e32.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wellnessz.e32.support.Docs;

class QcRulesTest {

    static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");
    static final LocalDate TODAY = LocalDate.parse("2026-10-31");
    static final QcRules.Schedule SYRINGE = new QcRules.Schedule("SPIROMETRY", "SPIRO_SYRINGE_3L", 24, 0, true, 2, false, "daily");
    static final QcRules.Schedule LINEARITY = new QcRules.Schedule("SPIROMETRY", "SPIRO_LINEARITY", 168, 0, true, 24, false, "weekly");
    static final QcRules.Schedule LOT = new QcRules.Schedule("POC", "POC_QC_LOT", 720, 0, true, 72, true, "lot");

    static QcRules.Check check(String type, Duration ago, String outcome) {
        return new QcRules.Check(type, NOW.minus(ago), outcome, null, null);
    }

    @Test
    void dailyCheckIsValidDueOrFailed() {
        assertThat(QcRules.evaluate(SYRINGE, List.of(), NOW, null, TODAY).verdict()).isEqualTo("DUE");
        assertThat(QcRules.evaluate(SYRINGE, List.of(check("SPIRO_SYRINGE_3L", Duration.ofHours(3), "PASS")), NOW, null, TODAY).verdict())
                .isEqualTo("VALID");
        QcRules.Verdict overdue = QcRules.evaluate(SYRINGE, List.of(check("SPIRO_SYRINGE_3L", Duration.ofHours(26), "PASS")), NOW, null, TODAY);
        assertThat(overdue.verdict()).isEqualTo("DUE");
        assertThat(overdue.blocks()).isTrue();
        assertThat(QcRules.evaluate(SYRINGE, List.of(check("SPIRO_SYRINGE_3L", Duration.ofHours(1), "FAIL"),
                check("SPIRO_SYRINGE_3L", Duration.ofHours(2), "PASS")), NOW, null, TODAY).verdict()).isEqualTo("FAILED");
        assertThat(QcRules.evaluate(SYRINGE, List.of(check("SPIRO_SYRINGE_3L", Duration.ofHours(1), "PASS"),
                check("SPIRO_SYRINGE_3L", Duration.ofHours(2), "FAIL")), NOW, null, TODAY).verdict()).isEqualTo("VALID");
        QcRules.Schedule grace = new QcRules.Schedule("SPIROMETRY", "SPIRO_SYRINGE_3L", 24, 180, true, 2, false, "daily");
        assertThat(QcRules.evaluate(grace, List.of(check("SPIRO_SYRINGE_3L", Duration.ofHours(26), "PASS")), NOW, null, TODAY).verdict())
                .isEqualTo("VALID");
    }

    @Test
    void qcLotsAreEvaluatedPerLotAndExpire() {
        QcRules.Check lotA = new QcRules.Check("POC_QC_LOT", NOW.minus(Duration.ofDays(2)), "PASS", "LOT-A", TODAY.plusDays(30));
        QcRules.Check expired = new QcRules.Check("POC_QC_LOT", NOW.minus(Duration.ofDays(1)), "PASS", "LOT-B", TODAY.minusDays(1));
        assertThat(QcRules.evaluate(LOT, List.of(lotA, expired), NOW, "LOT-A", TODAY).verdict()).isEqualTo("VALID");
        assertThat(QcRules.evaluate(LOT, List.of(lotA, expired), NOW, "LOT-B", TODAY).verdict()).isEqualTo("FAILED");
        assertThat(QcRules.evaluate(LOT, List.of(lotA, expired), NOW, "LOT-C", TODAY).verdict()).isEqualTo("DUE");
    }

    @Test
    void aggregatePrefersFailedThenDueThenEarliestDue() {
        QcRules.Verdict valid = new QcRules.Verdict("A", "VALID", NOW, NOW.plusSeconds(100), null, null, true);
        QcRules.Verdict valid2 = new QcRules.Verdict("B", "VALID", NOW, NOW.plusSeconds(50), null, null, true);
        QcRules.Verdict due = new QcRules.Verdict("C", "DUE", null, null, null, null, true);
        QcRules.Verdict failed = new QcRules.Verdict("D", "FAILED", NOW, NOW, null, null, true);
        assertThat(QcRules.aggregate(List.of(valid, due, failed)).checkType()).isEqualTo("D");
        assertThat(QcRules.aggregate(List.of(valid, due)).checkType()).isEqualTo("C");
        assertThat(QcRules.aggregate(List.of(valid, valid2)).checkType()).isEqualTo("B");
        assertThat(QcRules.aggregate(List.of()).verdict()).isEqualTo("NOT_REQUIRED");
        QcRules.Verdict noLock = new QcRules.Verdict("E", "DUE", null, null, null, null, false);
        assertThat(noLock.blocks()).isFalse();
    }

    @Test
    void instanceOverridesOnlyTightenAndRelaxingNeedsApproval() {
        List<QcRules.Schedule> eff = QcRules.effective(List.of(SYRINGE, LINEARITY),
                List.of(Docs.of("checkType", "SPIRO_SYRINGE_3L", "intervalHours", 48, "lockout", false),
                        Docs.of("checkType", "SPIRO_LINEARITY", "intervalHours", 72, "lockout", true),
                        Docs.of("checkType", "LEAK_TEST", "intervalHours", 12, "lockout", true)));
        assertThat(eff).extracting(QcRules.Schedule::checkType).containsExactly("SPIRO_SYRINGE_3L", "SPIRO_LINEARITY", "LEAK_TEST");
        assertThat(eff.get(0).intervalHours()).isEqualTo(24);
        assertThat(eff.get(0).lockout()).isTrue();
        assertThat(eff.get(1).intervalHours()).isEqualTo(72);
        assertThat(QcRules.relaxes(SYRINGE, 48, 0, true)).isTrue();
        assertThat(QcRules.relaxes(SYRINGE, 24, 0, false)).isTrue();
        assertThat(QcRules.relaxes(SYRINGE, 12, 0, true)).isFalse();
    }
}
