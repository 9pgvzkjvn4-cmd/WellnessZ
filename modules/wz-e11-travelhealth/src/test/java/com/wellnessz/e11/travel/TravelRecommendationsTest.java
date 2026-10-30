package com.wellnessz.e11.travel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wellnessz.e11.travel.TravelRecommendations.DestinationInfo;
import com.wellnessz.e11.travel.TravelRecommendations.Dose;
import com.wellnessz.e11.travel.TravelRecommendations.Recommendation;
import com.wellnessz.e11.travel.TravelRecommendations.Result;

class TravelRecommendationsTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    static final DestinationInfo SG = new DestinationInfo("SG", "Singapore", List.of("HEP_A", "TYPHOID", "TDAP_IPV"), List.of());
    static final DestinationInfo NG = new DestinationInfo("NG", "Nigeria", List.of("YELLOW_FEVER", "HEP_A", "TYPHOID"),
            List.of("Malaria chemoprophylaxis"));

    static Recommendation find(Result r, String code) {
        return r.vaccines().stream().filter(v -> v.vaccineCode().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void nothingGivenMeansGiveEverything() {
        Result r = TravelRecommendations.recommend(List.of(SG), List.of(), TODAY, TODAY.plusDays(30), TODAY.plusDays(40));
        assertThat(r.vaccines()).extracting(Recommendation::status).containsOnly("NOT_GIVEN");
        assertThat(r.vaccines()).extracting(Recommendation::action).containsOnly("GIVE");
        assertThat(r.lateConsultation()).isFalse();
    }

    @Test
    void statusAgainstHistory() {
        List<Dose> history = List.of(new Dose("HEP_A", TODAY.minusMonths(8)), new Dose("TYPHOID", TODAY.minusMonths(35)),
                new Dose("TDAP_IPV", TODAY.minusYears(2)));
        Result r = TravelRecommendations.recommend(List.of(SG), history, TODAY, TODAY.plusDays(30), TODAY.plusDays(60));
        Recommendation hepA = find(r, "HEP_A");
        assertThat(hepA.status()).isEqualTo("INCOMPLETE");
        assertThat(hepA.action()).isEqualTo("COMPLETE_COURSE");
        assertThat(hepA.dosesGiven()).isEqualTo(1);
        assertThat(hepA.dosesRequired()).isEqualTo(2);
        // typhoid protection (36 months) lapses before the end of the trip
        Recommendation typhoid = find(r, "TYPHOID");
        assertThat(typhoid.status()).isEqualTo("BOOSTER_DUE");
        assertThat(typhoid.protectedUntil()).isEqualTo(TODAY.minusMonths(35).plusMonths(36));
        assertThat(find(r, "TDAP_IPV").status()).isEqualTo("UP_TO_DATE");
    }

    @Test
    void longTermVaccinesStayUpToDate() {
        List<Dose> history = List.of(new Dose("YELLOW_FEVER", TODAY.minusYears(15)), new Dose("HEP_A", TODAY.minusYears(6)),
                new Dose("HEP_A", TODAY.minusYears(5)), new Dose("TYPHOID", TODAY.minusMonths(2)));
        Result r = TravelRecommendations.recommend(List.of(NG), history, TODAY, TODAY.plusDays(30), TODAY.plusDays(45));
        assertThat(r.vaccines()).extracting(Recommendation::status).containsOnly("UP_TO_DATE");
        assertThat(r.prophylaxis()).containsExactly("Malaria chemoprophylaxis");
        assertThat(r.lateConsultation()).isFalse();
    }

    @Test
    void sharedVaccinesAreMergedAcrossDestinationsAndLateConsultationsFlagged() {
        Result r = TravelRecommendations.recommend(List.of(SG, NG), List.of(), TODAY, TODAY.plusDays(5), null);
        assertThat(find(r, "HEP_A").destinations()).containsExactly("SG", "NG");
        assertThat(r.vaccines()).extracting(Recommendation::vaccineCode).containsExactly("HEP_A", "TYPHOID", "TDAP_IPV", "YELLOW_FEVER");
        assertThat(r.lateConsultation()).isTrue();
    }

    @Test
    void unknownVaccinesFallBackToASingleLongTermDose() {
        assertThat(TravelRecommendations.rule("CHOLERA").primaryDoses()).isEqualTo(1);
        assertThat(TravelRecommendations.evaluate("CHOLERA", List.of("XX"), List.of(new Dose("CHOLERA", TODAY.minusDays(10))), TODAY).status())
                .isEqualTo("UP_TO_DATE");
    }
}
