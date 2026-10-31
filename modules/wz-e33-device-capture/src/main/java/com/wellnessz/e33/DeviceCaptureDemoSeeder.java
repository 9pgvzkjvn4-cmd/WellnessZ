package com.wellnessz.e33;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

import com.wellnessz.e33.domain.PlainLanguage;
import com.wellnessz.e33.model.CaptureEvent;
import com.wellnessz.e33.model.CaptureObservation;
import com.wellnessz.e33.model.CaptureSession;
import com.wellnessz.e33.model.E33CaptureEventRepository;
import com.wellnessz.e33.model.E33CaptureObservationRepository;
import com.wellnessz.e33.model.E33CaptureSessionRepository;
import com.wellnessz.e33.model.E33EmployeeSummaryRepository;
import com.wellnessz.e33.model.E33FormRuleRepository;
import com.wellnessz.e33.model.EmployeeSummary;
import com.wellnessz.e33.service.CaptureSupport;

/**
 * Local / test profiles only: synthetic capture history for the demo (contract 19) – accepted vitals, body-composition and
 * spirometry captures of the demo employee {@code E1001} at {@code LHR} (comparison and trend panel, E33-F11), one capture via
 * each fall-back path (photo / OCR verified, manual attested – KPI path distribution), and the released plain-language summary
 * the employee app shows under "Health checks" (E33-F12). The values are encrypted at field level, which is why they are
 * seeded here and not in SQL. Idempotent (fixed ids); does nothing when the demo employee is absent.
 */
@Component
@Profile({"local", "test"})
public class DeviceCaptureDemoSeeder {

    static final String EMPLOYEE = "E1001";
    private static final Logger log = LoggerFactory.getLogger(DeviceCaptureDemoSeeder.class);

    private record Demo(UUID id, String formCode, String category, String device, String station, String path, int daysAgo,
                        Map<String, Object> values, boolean summary) {
    }

    private static final List<Demo> DEMOS = List.of(
            new Demo(id(1), "E03.DEVICE_RESULT.VITALS", "VITALS", "DEV-000003", "LHR-OH-ROOM-1", "DEVICE", 180,
                    values("systolic", 126, "diastolic", 81, "pulse", 72), false),
            new Demo(id(2), "E03.DEVICE_RESULT.VITALS", "VITALS", "DEV-000003", "LHR-OH-ROOM-1", "DEVICE", 90,
                    values("systolic", 132, "diastolic", 84, "pulse", 70), false),
            new Demo(id(3), "E03.DEVICE_RESULT.VITALS", "VITALS", "DEV-000004", "LHR-OH-ROOM-1", "ALTERNATE_DEVICE", 14,
                    values("systolic", 129, "diastolic", 83, "pulse", 68), true),
            new Demo(id(4), "E03.DEVICE_RESULT.BODY_COMPOSITION", "BODY_COMPOSITION", null, "LHR-OH-ROOM-1", "MANUAL_ATTESTED", 14,
                    values("heightCm", 172, "weightKg", 74.5, "bmi", 25.2), true),
            new Demo(id(5), "E03.DEVICE_RESULT.SPIROMETRY", "SPIROMETRY", "DEV-000001", "LHR-OH-ROOM-2", "DEVICE", 365,
                    values("fev1L", 3.42, "fvcL", 4.31, "fev1FvcRatio", 0.79, "fev1PctPredicted", 96), false),
            new Demo(id(6), "E03.DEVICE_RESULT.SPIROMETRY", "SPIROMETRY", "DEV-000001", "LHR-OH-ROOM-2", "OCR_VERIFIED", 30,
                    values("fev1L", 3.36, "fvcL", 4.28, "fev1FvcRatio", 0.79, "fev1PctPredicted", 94), true));

    private final E33CaptureSessionRepository sessions;
    private final E33CaptureObservationRepository observations;
    private final E33CaptureEventRepository events;
    private final E33EmployeeSummaryRepository summaries;
    private final E33FormRuleRepository rules;
    private final CaptureSupport support;
    private final TransactionTemplate tx;

    public DeviceCaptureDemoSeeder(E33CaptureSessionRepository sessions, E33CaptureObservationRepository observations,
                                   E33CaptureEventRepository events, E33EmployeeSummaryRepository summaries, E33FormRuleRepository rules,
                                   CaptureSupport support, TransactionTemplate tx) {
        this.sessions = sessions;
        this.observations = observations;
        this.events = events;
        this.summaries = summaries;
        this.rules = rules;
        this.support = support;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            Integer n = tx.execute(s -> seedAll());
            if (n != null && n > 0) {
                log.info("E33 demo captures seeded: {}", n);
            }
        } catch (RuntimeException e) {
            log.warn("E33 demo seeding skipped: {}", e.getClass().getSimpleName());
        }
    }

    int seedAll() {
        if (support.employee(EMPLOYEE).isEmpty()) {
            return 0;
        }
        int n = 0;
        String language = support.employee(EMPLOYEE).map(e -> PlainLanguage.language(e.getPreferredLanguage())).orElse("en");
        for (Demo d : DEMOS) {
            if (sessions.existsById(d.id()) || rules.findById(d.formCode()).isEmpty()) {
                continue;
            }
            Instant at = support.now().truncatedTo(ChronoUnit.MINUTES).minus(d.daysAgo(), ChronoUnit.DAYS);
            CaptureSession s = new CaptureSession();
            s.setId(d.id());
            s.setMode("FORM");
            s.setState("ACCEPTED");
            s.setEmployeeId(EMPLOYEE);
            s.setFormCode(d.formCode());
            s.setFormVersion("1");
            s.setCategory(d.category());
            s.setRestricted(false);
            s.setDeviceInstanceId(d.device());
            s.setOriginalDeviceInstanceId("ALTERNATE_DEVICE".equals(d.path()) ? "DEV-000003" : d.device());
            s.setPath(d.path());
            s.setFallbackReason("DEVICE".equals(d.path()) ? null : "MANUAL_ATTESTED".equals(d.path()) ? "DEVICE_UNAVAILABLE"
                    : "OCR_VERIFIED".equals(d.path()) ? "DISPLAY_ONLY" : "DEVICE_LOCKED");
            s.setOperator("nurse1");
            s.setSiteCode("LHR");
            s.setStationCode(d.station());
            s.setContextPush("NONE");
            s.setOpenedAt(at.minusSeconds(240));
            s.setLastActivityAt(at.plusSeconds(60));
            s.setExpiresAt(at.plusSeconds(360));
            s.setClosedAt(at.plusSeconds(60));
            UUID observationId = UUID.nameUUIDFromBytes(("e33-demo-observation-" + d.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            s.setObservationId(observationId);
            s.setObservationStatus("ROUTED");
            s.setVerifiedBy("nurse1");
            s.setVerifiedAt(at.plusSeconds(60));
            sessions.save(s);
            CaptureObservation o = new CaptureObservation();
            o.setId(UUID.nameUUIDFromBytes(("e33-demo-capture-" + d.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            o.setSessionId(s.getId());
            o.setObservationId(observationId);
            o.setEmployeeId(EMPLOYEE);
            o.setCategory(d.category());
            o.setCaptureMethod("OCR_VERIFIED".equals(d.path()) || "MANUAL_ATTESTED".equals(d.path()) ? d.path() : "DEVICE");
            o.setDeviceInstanceId(d.device());
            o.setStatus("ROUTED");
            o.setEffectiveAt(at);
            o.setReceivedAt(at.plusSeconds(3));
            o.setDecision("ACCEPTED");
            o.setDecidedBy("nurse1");
            o.setDecidedAt(at.plusSeconds(60));
            o.setValuesJson(support.write(d.values()));
            observations.save(o);
            events.save(new CaptureEvent(s.getId(), s.getOpenedAt(), "nurse1", null, "LISTENING", "OPEN", "demo"));
            events.save(new CaptureEvent(s.getId(), at.plusSeconds(3), "device-engine", "LISTENING", "PREVIEW", "RESULT", "PENDING_VERIFICATION"));
            events.save(new CaptureEvent(s.getId(), at.plusSeconds(60), "nurse1", "PREVIEW", "ACCEPTED", "ACCEPT", "demo"));
            if (d.summary()) {
                Map<String, BigDecimal> numbers = new LinkedHashMap<>();
                d.values().forEach((k, v) -> numbers.put(k, new BigDecimal(v.toString())));
                PlainLanguage.Summary text = PlainLanguage.summarise(d.category(), numbers, LocalDate.ofInstant(at, ZoneOffset.UTC), language);
                summaries.save(new EmployeeSummary(EMPLOYEE, s.getId(), observationId, d.category(), at, text.language(), text.text(),
                        text.educationLink(), "nurse1", at.plusSeconds(60)));
            }
            n++;
        }
        return n;
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("e33de000-0000-4000-8000-%012d", n));
    }

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
