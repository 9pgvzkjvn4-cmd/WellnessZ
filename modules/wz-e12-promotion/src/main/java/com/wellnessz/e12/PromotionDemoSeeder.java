package com.wellnessz.e12;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.e12.model.E12ScreeningResultRepository;
import com.wellnessz.e12.model.E12WearableDailyRepository;
import com.wellnessz.e12.model.E12WearableEnrolmentRepository;
import com.wellnessz.e12.model.ScreeningResult;
import com.wellnessz.e12.model.WearableDaily;
import com.wellnessz.e12.rules.ScreeningThresholds;
import com.wellnessz.e12.screening.ScreeningService;
import com.wellnessz.e12.support.PromotionSupport;

/**
 * Local / test profiles only: completes the E12 demo data (db/demo/R__demo_e12_promotion.sql) with the values that are
 * encrypted at field level – the measures and flags of the past MSK screening results and the wearable step counts of the
 * demo enrolment – and opens the follow-up case (with result letter) of the abnormal demo result. Idempotent; does nothing
 * when the demo rows are not present.
 */
@Component
@Profile({"local", "test"})
public class PromotionDemoSeeder {

    static final UUID ENROLMENT = UUID.fromString("e12de000-0000-4000-8000-000000000502");
    static final Map<UUID, Map<String, BigDecimal>> MEASURES = new LinkedHashMap<>();

    static {
        MEASURES.put(UUID.fromString("e12de000-0000-4000-8000-000000000221"), Map.of("mskScore", new BigDecimal("8"), "bmi", new BigDecimal("27.4")));
        MEASURES.put(UUID.fromString("e12de000-0000-4000-8000-000000000222"), Map.of("mskScore", new BigDecimal("3"), "bmi", new BigDecimal("24.1")));
        MEASURES.put(UUID.fromString("e12de000-0000-4000-8000-000000000223"), Map.of("mskScore", new BigDecimal("4"), "bmi", new BigDecimal("26.0")));
        MEASURES.put(UUID.fromString("e12de000-0000-4000-8000-000000000224"), Map.of("mskScore", new BigDecimal("5"), "bmi", new BigDecimal("23.5")));
    }

    private static final Logger log = LoggerFactory.getLogger(PromotionDemoSeeder.class);

    private final E12ScreeningResultRepository results;
    private final E12WearableEnrolmentRepository enrolments;
    private final E12WearableDailyRepository daily;
    private final ScreeningService screening;
    private final PromotionSupport support;
    private final TransactionTemplate tx;

    public PromotionDemoSeeder(E12ScreeningResultRepository results, E12WearableEnrolmentRepository enrolments, E12WearableDailyRepository daily,
                               ScreeningService screening, PromotionSupport support, TransactionTemplate tx) {
        this.results = results;
        this.enrolments = enrolments;
        this.daily = daily;
        this.screening = screening;
        this.support = support;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            tx.executeWithoutResult(s -> {
                screeningResults();
                wearableSteps();
            });
        } catch (RuntimeException e) {
            log.warn("E12 demo seeding skipped: {}", e.getClass().getSimpleName());
        }
    }

    private void screeningResults() {
        var thresholds = screening.thresholds();
        for (Map.Entry<UUID, Map<String, BigDecimal>> m : MEASURES.entrySet()) {
            results.findById(m.getKey()).filter(r -> r.getMeasures() == null).ifPresent(r -> {
                Map<String, BigDecimal> ordered = new LinkedHashMap<>();
                for (String k : ScreeningThresholds.FLAGS.keySet()) {
                    if (m.getValue().containsKey(k)) {
                        ordered.put(k, m.getValue().get(k));
                    }
                }
                r.setMeasures(support.write(ordered));
                r.setFlags(PromotionSupport.joinCsv(ScreeningThresholds.evaluate(ordered, thresholds)));
            });
        }
        for (UUID id : MEASURES.keySet()) {
            results.findById(id).filter(r -> "ABNORMAL".equals(r.getOutcome()) && r.getCaseId() == null)
                    .ifPresent((ScreeningResult r) -> screening.openFollowUp(r.getId(), "nurse1", "Nina Nurse"));
        }
    }

    private void wearableSteps() {
        enrolments.findById(ENROLMENT).filter(e -> "ACTIVE".equals(e.getStatus()) && daily.findByEnrolmentId(e.getId()).isEmpty()).ifPresent(e -> {
            LocalDate today = support.today();
            Instant now = support.now();
            List<Integer> steps = List.of(5200, 6100, 5800, 6400, 7000, 6900, 7300, 7800, 8100, 7600, 8400, 8800, 9100, 9400);
            for (int i = 0; i < steps.size(); i++) {
                WearableDaily d = new WearableDaily();
                d.setId(UUID.randomUUID());
                d.setEnrolmentId(e.getId());
                d.setEmployeeId(e.getEmployeeId());
                d.setCampaignId(e.getCampaignId());
                d.setMetric("STEPS");
                d.setMetricDate(today.minusDays(steps.size() - (long) i));
                d.setValue(String.valueOf(steps.get(i)));
                d.setReceivedAt(now);
                daily.save(d);
            }
        });
    }
}
