package com.wellnessz.e11;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.documents.DocumentService;
import com.wellnessz.e05.food.FoodHandlerService;
import com.wellnessz.e05.model.FoodHandlerEpisode;
import com.wellnessz.e05.model.FoodHandlerEpisodeRepository;
import com.wellnessz.e11.model.CdCase;
import com.wellnessz.e11.model.E11AdverseEventRepository;
import com.wellnessz.e11.model.E11CdCaseRepository;
import com.wellnessz.e11.model.E11ConsultationRepository;
import com.wellnessz.e11.model.E11DeclineRepository;
import com.wellnessz.e11.model.E11ExposureRepository;
import com.wellnessz.e11.model.E11PhReportRepository;
import com.wellnessz.e11.support.TravelHealthSupport;
import com.wellnessz.e11.travel.TravelService;

/**
 * Local / test profiles only: completes the E11 demo data (db/demo/R__demo_e11_travelhealth.sql) with the values that are
 * encrypted at field level and therefore cannot be written by SQL – consultation advice, exposure details, decline reason,
 * adverse-event actions, the OH-only food-handler exclusion reason – and with the signed documents (travel health certificate,
 * public-health report). Idempotent; does nothing when the demo rows are not present.
 */
@Component
@Profile({"local", "test"})
public class TravelHealthDemoSeeder {

    static final UUID CONSULTATION = UUID.fromString("e11de000-0000-4000-8000-000000000301");
    static final UUID CD_CASE = UUID.fromString("e11de000-0000-4000-8000-000000000101");
    static final UUID NEEDLESTICK = UUID.fromString("e11de000-0000-4000-8000-000000000201");
    static final UUID FUME = UUID.fromString("e11de000-0000-4000-8000-000000000202");
    static final UUID DECLINE = UUID.fromString("e11de000-0000-4000-8000-000000000451");
    static final UUID ADVERSE_EVENT = UUID.fromString("e11de000-0000-4000-8000-000000000461");
    static final UUID PH_REPORT = UUID.fromString("e11de000-0000-4000-8000-000000000601");
    private static final Logger log = LoggerFactory.getLogger(TravelHealthDemoSeeder.class);

    private final E11ConsultationRepository consultations;
    private final E11CdCaseRepository cdCases;
    private final E11ExposureRepository exposures;
    private final E11DeclineRepository declines;
    private final E11AdverseEventRepository adverseEvents;
    private final E11PhReportRepository reports;
    private final FoodHandlerEpisodeRepository foodEpisodes;
    private final TravelService travel;
    private final DocumentService documents;
    private final TravelHealthSupport support;
    private final TransactionTemplate tx;

    public TravelHealthDemoSeeder(E11ConsultationRepository consultations, E11CdCaseRepository cdCases, E11ExposureRepository exposures,
                                  E11DeclineRepository declines, E11AdverseEventRepository adverseEvents, E11PhReportRepository reports,
                                  FoodHandlerEpisodeRepository foodEpisodes, TravelService travel, DocumentService documents,
                                  TravelHealthSupport support, TransactionTemplate tx) {
        this.consultations = consultations;
        this.cdCases = cdCases;
        this.exposures = exposures;
        this.declines = declines;
        this.adverseEvents = adverseEvents;
        this.reports = reports;
        this.foodEpisodes = foodEpisodes;
        this.travel = travel;
        this.documents = documents;
        this.support = support;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            tx.executeWithoutResult(s -> {
                consultations.findById(CONSULTATION).ifPresent(c -> {
                    if (c.getAdvice() == null) {
                        c.setAdvice("Layover pattern Singapore every 2–3 weeks. Day-biting mosquitoes (dengue): repellent and covering clothing; "
                                + "hepatitis A booster due in 6–12 months to complete the course.");
                    }
                    travel.completeConsultation(c.getId(), "nurse1");
                });
                cdCases.findById(CD_CASE).ifPresent(this::foodHandlerEpisode);
                exposures.findById(NEEDLESTICK).filter(x -> x.getDetails() == null).ifPresent(x -> x.setDetails(
                        "Needle-stick to the left index finger from a used needle found in a seat pocket during cabin cleaning. "
                                + "Wound bled freely and was washed; the needle was retained for source assessment."));
                exposures.findById(FUME).filter(x -> x.getDetails() == null).ifPresent(x -> x.setDetails(
                        "Oily smell in the forward cabin during descent into SIN; headache and eye irritation, resolved after landing. "
                                + "Captain filed an air safety report."));
                declines.findById(DECLINE).filter(d -> d.getReason() == null).ifPresent(d -> d.setReason("Prefers vaccination by own GP"));
                adverseEvents.findById(ADVERSE_EVENT).filter(a -> a.getActions() == null).ifPresent(a -> a.setActions(
                        "Soreness and redness at the injection site; cold compress advised, resolved within 48 hours."));
                reports.findById(PH_REPORT).filter(r -> r.getDocumentId() == null).ifPresent(r -> {
                    Map<String, String> f = new LinkedHashMap<>();
                    f.put("reportRef", r.getReportRef());
                    f.put("authority", "Local Health Protection Team");
                    f.put("condition", "Norovirus gastroenteritis (food handler, catering line 2)");
                    f.put("onsetDate", LocalDate.now(support.clock().withZone(ZoneOffset.UTC)).minusDays(1).toString());
                    f.put("employeeName", "Erin Employee");
                    f.put("employeeId", "E1001");
                    f.put("contacts", "Contact tracing in progress");
                    f.put("measures", "Excluded from food handling until 48 hours symptom-free; catering line 2 deep clean requested");
                    f.put("reportedBy", "Dr Phil Physician");
                    r.setDocumentId(documents.generateSigned("physician1", "PUBLIC_HEALTH_REPORT", r.getEmployeeId(), r.getCaseId(), f).id());
                });
            });
        } catch (RuntimeException e) {
            log.warn("E11 demo seeding skipped: {}", e.getClass().getSimpleName());
        }
    }

    private void foodHandlerEpisode(CdCase c) {
        if (!foodEpisodes.findByCaseIdOrderByCreatedAtDesc(c.getCaseId()).isEmpty()) {
            return;
        }
        FoodHandlerEpisode e = FoodHandlerEpisode.create();
        e.setCaseId(c.getCaseId());
        e.setEmployeeId(c.getEmployeeId());
        e.setDepartmentCode("CATERING");
        e.setStatus(FoodHandlerService.EXCLUDED);
        e.setReasonCategory("GI_ILLNESS");
        e.setExcludedFrom(c.getExclusionFrom());
        e.setExpectedReturn(c.getExclusionUntil());
        e.setCreatedBy(c.getCreatedBy());
        e.setCreatedAt(c.getUpdatedAt());
        e.setUpdatedAt(c.getUpdatedAt());
        foodEpisodes.save(e);
    }
}
