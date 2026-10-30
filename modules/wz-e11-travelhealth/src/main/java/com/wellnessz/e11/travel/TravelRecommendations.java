package com.wellnessz.e11.travel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Travel vaccine recommendation engine (E11-F01, pure): the vaccines recommended for the destinations are compared with the
 * traveller's immunisation history (E07 registry / E03 record). A vaccine is {@code UP_TO_DATE} when the primary course is
 * complete and protection lasts until the end of the trip, {@code BOOSTER_DUE} when protection lapses before, {@code INCOMPLETE}
 * when the primary course is unfinished and {@code NOT_GIVEN} otherwise. Late consultations (less than the lead time before
 * departure) are flagged because protection may not be complete in time.
 */
public final class TravelRecommendations {

    public static final int LEAD_TIME_DAYS = 14;

    /** Primary course and duration of protection after the last dose (months, {@code null} = long-term). */
    public record VaccineRule(String code, String name, int primaryDoses, Integer protectionMonths) {
    }

    public static final Map<String, VaccineRule> RULES = Map.ofEntries(
            Map.entry("HEP_A", new VaccineRule("HEP_A", "Hepatitis A", 2, null)),
            Map.entry("HEP_B", new VaccineRule("HEP_B", "Hepatitis B", 3, null)),
            Map.entry("TYPHOID", new VaccineRule("TYPHOID", "Typhoid", 1, 36)),
            Map.entry("YELLOW_FEVER", new VaccineRule("YELLOW_FEVER", "Yellow fever", 1, null)),
            Map.entry("TDAP_IPV", new VaccineRule("TDAP_IPV", "Tetanus, diphtheria and polio", 1, 120)),
            Map.entry("MMR", new VaccineRule("MMR", "MMR", 2, null)),
            Map.entry("INFLUENZA", new VaccineRule("INFLUENZA", "Influenza", 1, 12)),
            Map.entry("COVID_19", new VaccineRule("COVID_19", "COVID-19", 1, 12)),
            Map.entry("VARICELLA", new VaccineRule("VARICELLA", "Varicella", 2, null)),
            Map.entry("RABIES", new VaccineRule("RABIES", "Rabies (pre-exposure)", 2, null)),
            Map.entry("MENACWY", new VaccineRule("MENACWY", "Meningococcal ACWY", 1, 60)));

    private TravelRecommendations() {
    }

    public record Dose(String vaccineCode, LocalDate givenOn) {
    }

    public record DestinationInfo(String countryCode, String name, List<String> vaccines, List<String> prophylaxis) {
    }

    public record Recommendation(String vaccineCode, String vaccineName, List<String> destinations, String status, int dosesGiven,
                                 int dosesRequired, LocalDate lastDoseOn, LocalDate protectedUntil, String action) {
    }

    public record Result(List<Recommendation> vaccines, List<String> prophylaxis, boolean lateConsultation) {
    }

    public static VaccineRule rule(String code) {
        return RULES.getOrDefault(code, new VaccineRule(code, code, 1, null));
    }

    public static Recommendation evaluate(String code, List<String> destinations, List<Dose> history, LocalDate travelUntil) {
        VaccineRule r = rule(code);
        List<Dose> given = history.stream().filter(d -> code.equals(d.vaccineCode())).sorted(Comparator.comparing(Dose::givenOn)).toList();
        LocalDate last = given.isEmpty() ? null : given.get(given.size() - 1).givenOn();
        int n = given.size();
        if (n == 0) {
            return new Recommendation(code, r.name(), destinations, "NOT_GIVEN", 0, r.primaryDoses(), null, null, "GIVE");
        }
        if (n < r.primaryDoses()) {
            return new Recommendation(code, r.name(), destinations, "INCOMPLETE", n, r.primaryDoses(), last, null, "COMPLETE_COURSE");
        }
        LocalDate until = r.protectionMonths() == null ? null : last.plusMonths(r.protectionMonths());
        if (until != null && until.isBefore(travelUntil)) {
            return new Recommendation(code, r.name(), destinations, "BOOSTER_DUE", n, r.primaryDoses(), last, until, "GIVE_BOOSTER");
        }
        return new Recommendation(code, r.name(), destinations, "UP_TO_DATE", n, r.primaryDoses(), last, until, "NONE");
    }

    /**
     * @param destinations destination content (recommended vaccine codes and prophylaxis)
     * @param history      doses already given
     * @param today        consultation day
     * @param departDate   departure (lead-time check); {@code null} = today
     * @param returnDate   protection must last until this day; {@code null} = departure
     */
    public static Result recommend(List<DestinationInfo> destinations, List<Dose> history, LocalDate today, LocalDate departDate,
                                   LocalDate returnDate) {
        LocalDate depart = departDate == null ? today : departDate;
        LocalDate until = returnDate == null ? depart : returnDate;
        Map<String, List<String>> byVaccine = new LinkedHashMap<>();
        Set<String> prophylaxis = new LinkedHashSet<>();
        for (DestinationInfo d : destinations) {
            for (String v : d.vaccines()) {
                byVaccine.computeIfAbsent(v, k -> new ArrayList<>()).add(d.countryCode());
            }
            prophylaxis.addAll(d.prophylaxis());
        }
        List<Recommendation> out = new ArrayList<>();
        byVaccine.forEach((code, dests) -> out.add(evaluate(code, List.copyOf(dests), history, until)));
        boolean actionNeeded = out.stream().anyMatch(r -> !"UP_TO_DATE".equals(r.status()));
        boolean late = actionNeeded && depart.isBefore(today.plusDays(LEAD_TIME_DAYS));
        return new Result(out, new ArrayList<>(prophylaxis), late);
    }
}
