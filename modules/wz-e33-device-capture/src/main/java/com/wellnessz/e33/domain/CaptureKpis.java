package com.wellnessz.e33.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Capture KPIs (E33 success KPIs, E33-BL-016): capture-from-device usage (target ≥ 90 % of eligible tests), nurse minutes saved
 * (target ≥ 3 min per device test, from the form's time-and-motion estimate), re-test rate attributable to capture (< 1 %),
 * unmatched rate of engine observations (< 0.5 %), and the distribution of fall-back paths. Counts only – no person data.
 */
public final class CaptureKpis {

    /**
     * @param state        final or current session state
     * @param path         DEVICE | ALTERNATE_DEVICE | OCR_VERIFIED | MANUAL_ATTESTED, {@code null} while undecided
     * @param minutesSaved time-and-motion estimate of the form per device capture
     * @param retests      re-tests requested in the session
     */
    public record SessionFact(String state, String path, BigDecimal minutesSaved, int retests) {
    }

    /** @param unmatched the observation was UNMATCHED at some point */
    public record NoticeFact(boolean unmatched) {
    }

    /**
     * @param usagePct          accepted captures through a device (incl. an alternate device) / all accepted captures
     * @param minutesSaved      estimate: full saving for device paths, half for OCR (values still checked), none for manual
     * @param retestRatePct     re-tests / (accepted + rejected + re-tests)
     * @param unmatchedRatePct  engine observations ever unmatched / all observations noticed
     */
    public record Result(int sessions, int accepted, int deviceCaptures, BigDecimal usagePct, BigDecimal minutesSaved,
                         BigDecimal minutesSavedPerDeviceTest, int retests, BigDecimal retestRatePct, int observations, int unmatched,
                         BigDecimal unmatchedRatePct, Map<String, Integer> byPath, Map<String, Integer> byState) {
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private CaptureKpis() {
    }

    public static Result compute(List<SessionFact> sessions, List<NoticeFact> notices) {
        int accepted = 0;
        int device = 0;
        int rejected = 0;
        int retests = 0;
        BigDecimal minutes = BigDecimal.ZERO;
        Map<String, Integer> byPath = new TreeMap<>();
        Map<String, Integer> byState = new TreeMap<>();
        for (SessionFact s : sessions) {
            byState.merge(s.state(), 1, Integer::sum);
            retests += s.retests();
            if ("REJECTED".equals(s.state())) {
                rejected++;
            }
            if (!"ACCEPTED".equals(s.state())) {
                continue;
            }
            accepted++;
            String path = s.path() == null ? FallbackChain.DEVICE : s.path();
            byPath.merge(path, 1, Integer::sum);
            BigDecimal m = s.minutesSaved() == null ? BigDecimal.ZERO : s.minutesSaved();
            switch (path) {
                case FallbackChain.DEVICE, FallbackChain.ALTERNATE_DEVICE -> {
                    device++;
                    minutes = minutes.add(m);
                }
                case FallbackChain.OCR_VERIFIED -> minutes = minutes.add(m.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
                default -> {
                    // manual entry saves nothing
                }
            }
        }
        int unmatched = (int) notices.stream().filter(NoticeFact::unmatched).count();
        return new Result(sessions.size(), accepted, device, pct(device, accepted), minutes.setScale(1, RoundingMode.HALF_UP),
                device == 0 ? BigDecimal.ZERO.setScale(1) : minutes.divide(BigDecimal.valueOf(device), 1, RoundingMode.HALF_UP),
                retests, pct(retests, accepted + rejected + retests), notices.size(), unmatched, pct(unmatched, notices.size()), byPath, byState);
    }

    static BigDecimal pct(int part, int whole) {
        return whole == 0 ? BigDecimal.ZERO.setScale(2) : BigDecimal.valueOf(part).multiply(HUNDRED).divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
    }
}
