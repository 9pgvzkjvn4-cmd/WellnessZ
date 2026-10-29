package com.wellnessz.e07;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e07.compliance.ComplianceMaths;
import com.wellnessz.e07.crew.CrewAlerts;
import com.wellnessz.e07.due.NoShowPolicy;
import com.wellnessz.e07.followup.EvaluationRules;
import com.wellnessz.e07.immunisation.Gs1Lot;
import com.wellnessz.e07.immunisation.ImmunisationRules;

/** Unit tests of the E07 rule engines (contract §9). */
class SurveillanceRulesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    // ------------------------------------------------------------------ result evaluation

    @Test
    void stsNeedsAbnormalFlagAndShiftInEitherEar() {
        assertThat(EvaluationRules.sts("ABNORMAL", Map.of("sts", Map.of("left", true, "right", false)))).isTrue();
        assertThat(EvaluationRules.sts("ABNORMAL", Map.of("sts", Map.of("left", false, "right", false)))).isFalse();
        assertThat(EvaluationRules.sts("NORMAL", Map.of("sts", Map.of("left", true)))).isFalse();
        assertThat(EvaluationRules.sts("ABNORMAL", null)).isFalse();
    }

    @Test
    void spirometryDeclineAgainstPreviousOrRatio() {
        assertThat(EvaluationRules.spirometryDecline(3.4, 4.5, 4.0, 15, 0.70)).isTrue();   // 15 % drop
        assertThat(EvaluationRules.spirometryDecline(3.5, 4.5, 4.0, 15, 0.70)).isFalse();  // 12.5 %
        assertThat(EvaluationRules.spirometryDecline(2.8, 4.5, null, 15, 0.70)).isTrue();  // ratio 0.62
        assertThat(EvaluationRules.spirometryDecline(3.6, 4.5, null, 15, 0.70)).isFalse();
        assertThat(EvaluationRules.spirometryDecline(null, 4.5, 4.0, 15, 0.70)).isFalse();
    }

    @Test
    void biomonitoringLimitAndTestMapping() {
        assertThat(EvaluationRules.biomonitoringLimit("32.5", 30)).isTrue();
        assertThat(EvaluationRules.biomonitoringLimit("30 ug/dL", 30)).isTrue();
        assertThat(EvaluationRules.biomonitoringLimit("<5", 30)).isFalse();
        assertThat(EvaluationRules.biomonitoringLimit(null, 30)).isFalse();
        assertThat(EvaluationRules.testsFor("LAB", "5671-3")).containsExactly("LAB:5671-3", "BIOMONITORING:5671-3");
        assertThat(EvaluationRules.testsFor("AUDIOMETRY", "28539-6")).containsExactly("AUDIOMETRY");
        assertThat(EvaluationRules.matchesTest("*", List.of("ECG"))).isTrue();
        assertThat(EvaluationRules.vision("ABNORMAL")).isTrue();
        assertThat(EvaluationRules.flagAbnormal("HIGH")).isTrue();
        assertThat(EvaluationRules.flagAbnormal("NORMAL")).isFalse();
    }

    // ------------------------------------------------------------------ GS1 lot barcodes

    @Test
    void gs1HumanReadableAndRawForms() {
        Gs1Lot.Lot a = Gs1Lot.parse("(01)05012345678900(17)270131(10)AB123C");
        assertThat(a.gtin()).isEqualTo("05012345678900");
        assertThat(a.lotNumber()).isEqualTo("AB123C");
        assertThat(a.expiry()).isEqualTo(LocalDate.of(2027, 1, 31));
        Gs1Lot.Lot b = Gs1Lot.parse("(17)270200(10)LOT-9");
        assertThat(b.expiry()).isEqualTo(LocalDate.of(2027, 2, 28)); // day 00 = end of month
        Gs1Lot.Lot c = Gs1Lot.parse("]d2" + "01" + "05012345678900" + "17" + "261231" + "10" + "XYZ" + "\u001d" + "21SER1");
        assertThat(c.lotNumber()).isEqualTo("XYZ");
        assertThat(c.expiry()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(c.serial()).isEqualTo("SER1");
        assertThatThrownBy(() -> Gs1Lot.parse("(17)271331(10)X")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Gs1Lot.parse("(17)270131")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Gs1Lot.parse("not a barcode")).isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------------ crew alert bands

    @Test
    void crewStatusAndAlertBands() {
        assertThat(CrewAlerts.status(TODAY.plusDays(120), false, TODAY)).isEqualTo("VALID");
        assertThat(CrewAlerts.status(TODAY.plusDays(90), false, TODAY)).isEqualTo("EXPIRING");
        assertThat(CrewAlerts.status(TODAY, false, TODAY)).isEqualTo("EXPIRING");
        assertThat(CrewAlerts.status(TODAY.minusDays(1), false, TODAY)).isEqualTo("EXPIRED");
        assertThat(CrewAlerts.status(TODAY.plusDays(200), true, TODAY)).isEqualTo("SUSPENDED");

        assertThat(CrewAlerts.dueBand(TODAY.plusDays(91), TODAY, Set.of())).isEmpty();
        assertThat(CrewAlerts.dueBand(TODAY.plusDays(90), TODAY, Set.of())).contains(90);
        assertThat(CrewAlerts.dueBand(TODAY.plusDays(70), TODAY, Set.of(90))).isEmpty();
        assertThat(CrewAlerts.dueBand(TODAY.plusDays(60), TODAY, Set.of(90))).contains(60);
        // a certificate first seen 45 days before expiry gets the 60-day alert only
        assertThat(CrewAlerts.dueBand(TODAY.plusDays(45), TODAY, Set.of())).contains(60);
        assertThat(CrewAlerts.markSent(Set.of(), 60)).containsExactlyInAnyOrder(60, 90);
        assertThat(CrewAlerts.dueBand(TODAY.plusDays(30), TODAY, Set.of(60, 90))).contains(30);
        assertThat(CrewAlerts.dueBand(TODAY.plusDays(10), TODAY, Set.of(30, 60, 90))).isEmpty();
        assertThat(CrewAlerts.dueBand(TODAY.minusDays(1), TODAY, Set.of())).isEmpty();
    }

    // ------------------------------------------------------------------ no-show policy

    @Test
    void noShowReinvitesThenEscalatesOnce() {
        assertThat(NoShowPolicy.onIgnored(1, 2, false)).isEqualTo(NoShowPolicy.Action.REINVITE);
        assertThat(NoShowPolicy.onIgnored(2, 2, false)).isEqualTo(NoShowPolicy.Action.ESCALATE);
        assertThat(NoShowPolicy.onIgnored(3, 2, false)).isEqualTo(NoShowPolicy.Action.ESCALATE);
        assertThat(NoShowPolicy.onIgnored(2, 2, true)).isEqualTo(NoShowPolicy.Action.NONE);
        assertThat(NoShowPolicy.onIgnored(1, 0, false)).isEqualTo(NoShowPolicy.Action.ESCALATE);
    }

    // ------------------------------------------------------------------ compliance suppression

    @Test
    void complianceSuppressesSmallGroups() {
        ComplianceMaths.Counts c = ComplianceMaths.ZERO;
        for (String s : List.of("CURRENT", "CURRENT", "DUE", "OVERDUE", "CURRENT")) {
            c = c.add(s);
        }
        ComplianceMaths.Row published = ComplianceMaths.row("HEARING", "Hearing", c, 5);
        assertThat(published.suppressed()).isFalse();
        assertThat(published.enrolled()).isEqualTo(5);
        assertThat(published.overdue()).isEqualTo(1);
        assertThat(published.compliancePct()).isEqualTo(80.0);
        ComplianceMaths.Row hidden = ComplianceMaths.row("VDU", "VDU", new ComplianceMaths.Counts(4, 4, 0, 0), 5);
        assertThat(hidden.suppressed()).isTrue();
        assertThat(hidden.enrolled()).isNull();
        assertThat(hidden.compliancePct()).isNull();
        assertThat(ComplianceMaths.overall(List.of(published, hidden))).isEqualTo(80.0);
        assertThat(ComplianceMaths.pct(0, 0)).isNull();
    }

    // ------------------------------------------------------------------ immunisation status

    @Test
    void immunisationCourseTitreAndBooster() {
        ImmunisationRules.Requirement hepB = new ImmunisationRules.Requirement("HEP_B", 3, List.of(0, 1, 6), null, true);
        assertThat(ImmunisationRules.evaluate(hepB, List.of(), null, TODAY).status()).isEqualTo("DUE");
        ImmunisationRules.Status partial = ImmunisationRules.evaluate(hepB,
                List.of(new ImmunisationRules.Dose(1, TODAY.minusMonths(3)), new ImmunisationRules.Dose(2, TODAY.minusMonths(2))), null, TODAY);
        assertThat(partial.status()).isEqualTo("DUE");
        assertThat(partial.nextDueOn()).isEqualTo(TODAY.minusMonths(3).plusMonths(6));
        List<ImmunisationRules.Dose> course = List.of(new ImmunisationRules.Dose(1, TODAY.minusMonths(9)),
                new ImmunisationRules.Dose(2, TODAY.minusMonths(8)), new ImmunisationRules.Dose(3, TODAY.minusMonths(3)));
        assertThat(ImmunisationRules.evaluate(hepB, course, null, TODAY).status()).isEqualTo("TITRE_NEEDED");
        assertThat(ImmunisationRules.evaluate(hepB, course, new ImmunisationRules.Titre(true, TODAY.minusMonths(1)), TODAY).status())
                .isEqualTo("COMPLETE");
        ImmunisationRules.Requirement typhoid = new ImmunisationRules.Requirement("TYPHOID", 1, List.of(0), 36, false);
        assertThat(ImmunisationRules.evaluate(typhoid, List.of(new ImmunisationRules.Dose(1, TODAY.minusMonths(37))), null, TODAY).status())
                .isEqualTo("OVERDUE");
        assertThat(ImmunisationRules.evaluate(typhoid, List.of(new ImmunisationRules.Dose(1, TODAY.minusMonths(12))), null, TODAY).status())
                .isEqualTo("COMPLETE");
        assertThat(ImmunisationRules.titreFor("HEP_B", "ANTI_HBS")).isTrue();
    }
}
