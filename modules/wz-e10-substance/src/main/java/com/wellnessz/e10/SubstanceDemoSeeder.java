package com.wellnessz.e10;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.e10.model.E10RtdPlanRepository;
import com.wellnessz.e10.model.E10TestRepository;
import com.wellnessz.e10.support.SubstanceSupport;
import com.wellnessz.e10.support.TestingViews.LabResult;

/**
 * Local / test profiles only: completes the E10 demo data (db/demo/R__demo_e10_substance.sql) with the values that are
 * encrypted at field level and therefore cannot be written by SQL – laboratory results, breath reading, MRO interview note and
 * the treatment provider of the demo return-to-duty plan. Idempotent; does nothing when the demo rows are not present.
 */
@Component("e10SubstanceDemoSeeder")
@Profile({"local", "test"})
public class SubstanceDemoSeeder {

    static final UUID NEGATIVE_TEST = UUID.fromString("e10de000-0000-4000-b000-000000000001");
    static final UUID MRO_REVIEW_TEST = UUID.fromString("e10de000-0000-4000-b000-000000000003");
    static final UUID POSITIVE_TEST = UUID.fromString("e10de000-0000-4000-b000-000000000006");
    static final UUID PLAN = UUID.fromString("e10de000-0000-4000-e000-000000000001");
    private static final Logger log = LoggerFactory.getLogger(SubstanceDemoSeeder.class);

    private final E10TestRepository tests;
    private final E10RtdPlanRepository plans;
    private final SubstanceSupport support;
    private final TransactionTemplate tx;

    public SubstanceDemoSeeder(E10TestRepository tests, E10RtdPlanRepository plans, SubstanceSupport support, TransactionTemplate tx) {
        this.tests = tests;
        this.plans = plans;
        this.support = support;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            tx.executeWithoutResult(s -> {
                tests.findById(NEGATIVE_TEST).filter(t -> t.getResults() == null)
                        .ifPresent(t -> t.setResults(support.json(panel10("NEGATIVE", null))));
                tests.findById(MRO_REVIEW_TEST).filter(t -> t.getResults() == null).ifPresent(t -> t.setResults(support.json(List.of(
                        new LabResult("AMPHETAMINES", "500", "0", "ng/mL", "NEGATIVE"),
                        new LabResult("COCAINE_METABOLITES", "150", "0", "ng/mL", "NEGATIVE"),
                        new LabResult("CANNABINOIDS", "50", "0", "ng/mL", "NEGATIVE"),
                        new LabResult("OPIATES", "2000", "2450", "ng/mL", "NON_NEGATIVE"),
                        new LabResult("PHENCYCLIDINE", "25", "0", "ng/mL", "NEGATIVE")))));
                tests.findById(POSITIVE_TEST).filter(t -> t.getResults() == null).ifPresent(t -> {
                    t.setResults(support.json(panel10("NEGATIVE", "CANNABINOIDS")));
                    t.setBreathResult("0.000");
                    t.setInterviewNote("Donor interviewed by telephone; no legitimate medical explanation offered.");
                });
                plans.findById(PLAN).filter(p -> p.getTreatmentProvider() == null)
                        .ifPresent(p -> p.setTreatmentProvider("Wellbeing Assist EAP – structured outpatient programme"));
            });
        } catch (RuntimeException e) {
            log.warn("E10 demo seeding skipped: {}", e.getClass().getSimpleName());
        }
    }

    private static List<LabResult> panel10(String outcome, String nonNegative) {
        String[][] analytes = {{"AMPHETAMINES", "500"}, {"COCAINE_METABOLITES", "150"}, {"CANNABINOIDS", "50"}, {"OPIATES", "2000"},
                {"PHENCYCLIDINE", "25"}, {"BENZODIAZEPINES", "300"}, {"BARBITURATES", "300"}, {"METHADONE", "300"}, {"PROPOXYPHENE", "300"},
                {"MDMA", "500"}};
        return java.util.Arrays.stream(analytes).map(a -> a[0].equals(nonNegative)
                ? new LabResult(a[0], a[1], "112", "ng/mL", "NON_NEGATIVE")
                : new LabResult(a[0], a[1], "0", "ng/mL", outcome)).toList();
    }
}
