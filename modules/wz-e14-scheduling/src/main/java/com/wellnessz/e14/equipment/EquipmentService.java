package com.wellnessz.e14.equipment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.common.text.ClinicalContentException;
import com.wellnessz.e14.E14Events;
import com.wellnessz.e14.engine.EquipmentRules;
import com.wellnessz.e14.model.Calibration;
import com.wellnessz.e14.model.CalibrationRepository;
import com.wellnessz.e14.model.Equipment;
import com.wellnessz.e14.model.EquipmentRepository;
import com.wellnessz.e14.model.Excursion;
import com.wellnessz.e14.model.ExcursionRepository;
import com.wellnessz.e14.model.TemperatureReading;
import com.wellnessz.e14.model.TemperatureReadingRepository;
import com.wellnessz.e14.support.SchedulingSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.ClinicSite;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.identity.CurrentUser;

/**
 * E14-F04 equipment and cold-chain register: devices with calibration history (derived calibration state), service status,
 * vaccine-fridge temperature logs (manual and IoT) with excursion detection, acknowledgement and monthly reports.
 */
@Service
public class EquipmentService {

    public static final int MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
    public static final int MAX_IOT_BATCH = 5000;

    private final EquipmentRepository equipment;
    private final CalibrationRepository calibrations;
    private final TemperatureReadingRepository readings;
    private final ExcursionRepository excursions;
    private final EventBus events;
    private final SchedulingSupport support;

    public EquipmentService(EquipmentRepository equipment, CalibrationRepository calibrations, TemperatureReadingRepository readings,
                            ExcursionRepository excursions, EventBus events, SchedulingSupport support) {
        this.equipment = equipment;
        this.calibrations = calibrations;
        this.readings = readings;
        this.excursions = excursions;
        this.events = events;
        this.support = support;
    }

    // ------------------------------------------------------------------ views

    public record LastReading(BigDecimal temperatureC, Instant recordedAt) {
    }

    public record EquipmentView(UUID id, String assetTag, String deviceId, String category, String model, String serialNumber, String siteCode,
                                String status, String statusReason, Integer calibrationIntervalMonths, LocalDate lastCalibratedOn,
                                LocalDate calibrationDueOn, String calibrationState, String lastCertificateNumber, BigDecimal minTempC,
                                BigDecimal maxTempC, LastReading lastReading, long openExcursions, long version) {
    }

    public record CalibrationView(UUID id, UUID equipmentId, LocalDate performedOn, String vendor, String certificateNumber, String result,
                                  LocalDate nextDueOn, boolean documentAttached, String documentSha256, String recordedBy, Instant recordedAt) {
    }

    public record ReadingView(Instant recordedAt, BigDecimal temperatureC, String source, boolean excursion) {
    }

    public record ExcursionView(UUID id, UUID equipmentId, String assetTag, String siteCode, Instant startedAt, Instant endedAt, BigDecimal minC,
                                BigDecimal maxC, String status, String action, String acknowledgedBy, Instant acknowledgedAt, String closedBy,
                                Instant closedAt) {
    }

    public record DayReport(LocalDate date, BigDecimal minC, BigDecimal maxC, int readings, int excursionReadings, int excursionsStarted) {
    }

    public record ColdChainReport(UUID equipmentId, String assetTag, String siteCode, YearMonth month, BigDecimal minTempC, BigDecimal maxTempC,
                                  List<DayReport> days, int excursions) {
    }

    EquipmentView view(Equipment e) {
        Optional<TemperatureReading> last = isTemperatureMonitored(e) ? readings.findFirstByEquipmentIdOrderByRecordedAtDesc(e.getId()) : Optional.empty();
        return new EquipmentView(e.getId(), e.getAssetTag(), e.getDeviceId(), e.getCategory(), e.getModel(), e.getSerialNumber(), e.getSiteCode(),
                e.getStatus(), e.getStatusReason(), e.getCalibrationIntervalMonths(), e.getLastCalibratedOn(), e.getCalibrationDueOn(),
                state(e).name(), e.getLastCertificateNumber(), e.getMinTempC(), e.getMaxTempC(),
                last.map(r -> new LastReading(r.getTemperatureC(), r.getRecordedAt())).orElse(null),
                excursions.countByEquipmentIdAndStatusNot(e.getId(), "CLOSED"), e.getVersion());
    }

    EquipmentRules.CalibrationState state(Equipment e) {
        return EquipmentRules.state(e.getCalibrationDueOn(), support.today(e.getSiteCode()),
                support.intConfig("scheduling.calibration.due-alert-days", EquipmentRules.DUE_SOON_DAYS));
    }

    private static boolean isTemperatureMonitored(Equipment e) {
        return "VACCINE_FRIDGE".equals(e.getCategory()) || e.getMinTempC() != null || e.getMaxTempC() != null;
    }

    private ExcursionView excursionView(Excursion x, Equipment e) {
        return new ExcursionView(x.getId(), x.getEquipmentId(), e == null ? null : e.getAssetTag(), e == null ? null : e.getSiteCode(),
                x.getStartedAt(), x.getEndedAt(), x.getMinC(), x.getMaxC(), x.getStatus(), x.getAction(), x.getAcknowledgedBy(),
                x.getAcknowledgedAt(), x.getClosedBy(), x.getClosedAt());
    }

    public Equipment get(UUID id) {
        return equipment.findById(id).orElseThrow(() -> ApiException.notFound("Equipment"));
    }

    // ------------------------------------------------------------------ register

    @Transactional(readOnly = true)
    public List<EquipmentView> list(String siteCode, String category) {
        return equipment.search(siteCode, category).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public EquipmentView one(UUID id) {
        return view(get(id));
    }

    public record EquipmentCommand(String assetTag, String deviceId, String category, String model, String serialNumber, String siteCode,
                                   Integer calibrationIntervalMonths, LocalDate lastCalibratedOn, LocalDate calibrationDueOn,
                                   BigDecimal minTempC, BigDecimal maxTempC) {
    }

    @Transactional
    public EquipmentView register(CurrentUser user, EquipmentCommand c) {
        String deviceId = c.deviceId() == null || c.deviceId().isBlank() ? c.assetTag() : c.deviceId();
        if (equipment.findByAssetTag(c.assetTag()).isPresent() || equipment.findByDeviceId(deviceId).isPresent()) {
            throw ApiException.conflict("duplicate", "Asset tag or device id is already registered");
        }
        Equipment e = Equipment.create();
        e.setAssetTag(c.assetTag());
        e.setDeviceId(deviceId);
        e.setStatus("IN_SERVICE");
        e.setCreatedAt(support.now());
        apply(e, c);
        equipment.saveAndFlush(e);
        support.audit("EQUIPMENT_REGISTER", "EQUIPMENT", e.getId().toString());
        return view(e);
    }

    @Transactional
    public EquipmentView update(CurrentUser user, UUID id, EquipmentCommand c) {
        Equipment e = get(id);
        if (c.assetTag() != null && !c.assetTag().equals(e.getAssetTag())) {
            if (equipment.findByAssetTag(c.assetTag()).isPresent()) {
                throw ApiException.conflict("duplicate", "Asset tag is already registered");
            }
            e.setAssetTag(c.assetTag());
        }
        if (c.deviceId() != null && !c.deviceId().isBlank() && !c.deviceId().equals(e.getDeviceId())) {
            if (equipment.findByDeviceId(c.deviceId()).isPresent()) {
                throw ApiException.conflict("duplicate", "Device id is already registered");
            }
            e.setDeviceId(c.deviceId());
        }
        apply(e, c);
        equipment.saveAndFlush(e);
        support.audit("EQUIPMENT_UPDATE", "EQUIPMENT", id.toString());
        return view(e);
    }

    private void apply(Equipment e, EquipmentCommand c) {
        support.site(c.siteCode());
        e.setCategory(c.category());
        e.setModel(c.model());
        e.setSerialNumber(c.serialNumber());
        e.setSiteCode(c.siteCode());
        e.setCalibrationIntervalMonths(c.calibrationIntervalMonths());
        e.setLastCalibratedOn(c.lastCalibratedOn());
        LocalDate due = c.calibrationDueOn();
        if (due == null && c.lastCalibratedOn() != null) {
            due = EquipmentRules.nextDue(c.lastCalibratedOn(), null, c.calibrationIntervalMonths());
        }
        e.setCalibrationDueOn(due);
        BigDecimal min = c.minTempC();
        BigDecimal max = c.maxTempC();
        if ("VACCINE_FRIDGE".equals(c.category())) {
            min = min == null ? EquipmentRules.DEFAULT_FRIDGE_MIN : min;
            max = max == null ? EquipmentRules.DEFAULT_FRIDGE_MAX : max;
        }
        if (min != null && max != null && min.compareTo(max) >= 0) {
            throw ApiException.badRequest("invalid_range", "minTempC must be below maxTempC");
        }
        e.setMinTempC(min);
        e.setMaxTempC(max);
        e.setUpdatedAt(support.now());
    }

    // ------------------------------------------------------------------ calibration & status

    public record CalibrationCommand(LocalDate performedOn, String vendor, String certificateNumber, String result, LocalDate nextDueOn,
                                     String documentBase64) {
    }

    @Transactional
    public CalibrationView recordCalibration(CurrentUser user, UUID id, CalibrationCommand c) {
        Equipment e = get(id);
        if ("RETIRED".equals(e.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "retired", "Retired equipment cannot be calibrated");
        }
        if (c.performedOn().isAfter(support.todayUtc().plusDays(1))) {
            throw ApiException.badRequest("future_date", "performedOn cannot be in the future");
        }
        byte[] doc = null;
        if (c.documentBase64() != null && !c.documentBase64().isBlank()) {
            try {
                doc = Base64.getMimeDecoder().decode(c.documentBase64());
            } catch (IllegalArgumentException bad) {
                throw ApiException.badRequest("invalid_document", "documentBase64 is not valid base64");
            }
            if (doc.length == 0 || doc.length > MAX_DOCUMENT_BYTES) {
                throw ApiException.badRequest("invalid_document", "Certificate document must be 1 byte – 10 MB");
            }
            if (!isPdfOrImage(doc)) {
                throw ApiException.badRequest("invalid_document", "Certificate document must be a PDF, JPEG or PNG");
            }
        }
        Calibration cal = Calibration.create();
        cal.setEquipmentId(id);
        cal.setPerformedOn(c.performedOn());
        cal.setVendor(c.vendor().strip());
        cal.setCertificateNumber(c.certificateNumber().strip());
        cal.setResult(c.result());
        cal.setDocumentContent(doc);
        cal.setDocumentSha256(doc == null ? null : sha256(doc));
        cal.setRecordedBy(user.username());
        cal.setRecordedAt(support.now());
        Instant now = support.now();
        if ("PASS".equals(c.result())) {
            LocalDate next = EquipmentRules.nextDue(c.performedOn(), c.nextDueOn(), e.getCalibrationIntervalMonths());
            cal.setNextDueOn(next);
            e.setLastCalibratedOn(c.performedOn());
            e.setCalibrationDueOn(next);
            e.setLastCertificateNumber(cal.getCertificateNumber());
            if ("OUT_OF_SERVICE".equals(e.getStatus())) {
                e.setStatus("IN_SERVICE");
                e.setStatusReason("Calibration passed " + c.performedOn());
            }
        } else {
            e.setStatus("OUT_OF_SERVICE");
            e.setStatusReason("Calibration failed " + c.performedOn());
            e.setLastCertificateNumber(cal.getCertificateNumber());
        }
        e.setUpdatedAt(now);
        calibrations.save(cal);
        equipment.saveAndFlush(e);
        events.publish(new E14Events.EquipmentCalibration(id.toString(), "FAIL".equals(c.result()) ? "OUT_OF_SERVICE" : state(e).name()));
        support.audit("EQUIPMENT_CALIBRATION", "EQUIPMENT", id.toString());
        return calibrationView(cal);
    }

    static boolean isPdfOrImage(byte[] b) {
        boolean pdf = b.length > 4 && new String(b, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
        boolean png = b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean jpeg = b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
        return pdf || png || jpeg;
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CalibrationView calibrationView(Calibration c) {
        return new CalibrationView(c.getId(), c.getEquipmentId(), c.getPerformedOn(), c.getVendor(), c.getCertificateNumber(), c.getResult(),
                c.getNextDueOn(), c.getDocumentContent() != null, c.getDocumentSha256(), c.getRecordedBy(), c.getRecordedAt());
    }

    @Transactional(readOnly = true)
    public List<CalibrationView> calibrationHistory(UUID id) {
        get(id);
        return calibrations.findByEquipmentIdOrderByPerformedOnDescRecordedAtDesc(id).stream().map(EquipmentService::calibrationView).toList();
    }

    @Transactional
    public EquipmentView setStatus(CurrentUser user, UUID id, String newStatus, String reason) {
        Equipment e = get(id);
        ClinicalContentException.screen("reason", reason);
        if ("RETIRED".equals(e.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "retired", "Retired equipment cannot change status");
        }
        e.setStatus(newStatus);
        e.setStatusReason(reason.strip());
        e.setUpdatedAt(support.now());
        equipment.saveAndFlush(e);
        events.publish(new E14Events.EquipmentCalibration(id.toString(), "IN_SERVICE".equals(newStatus) ? state(e).name() : newStatus));
        support.audit("EQUIPMENT_STATUS", "EQUIPMENT", id.toString());
        return view(e);
    }

    @Transactional(readOnly = true)
    public List<EquipmentView> calibrationDue(int withinDays) {
        LocalDate by = support.todayUtc().plusDays(withinDays);
        return equipment.findCalibrationDueBy(by).stream().map(this::view).toList();
    }

    /** Daily job: {@code equipment.calibration-due} to the biomedical address once per due date, at 30 days. */
    @Transactional
    public int calibrationAlerts(LocalDate today) {
        int days = support.intConfig("scheduling.calibration.due-alert-days", EquipmentRules.DUE_SOON_DAYS);
        String address = support.config("notifications.biomedical.email").orElse(null);
        int sent = 0;
        for (Equipment e : equipment.findCalibrationDueBy(today.plusDays(days))) {
            if (!EquipmentRules.dueAlert(e.getCalibrationDueOn(), today, days, e.getDueAlertedFor())) {
                continue;
            }
            if (address != null) {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("documentNumber", e.getAssetTag());
                p.put("site", support.siteName(e.getSiteCode()));
                p.put("dueDate", e.getCalibrationDueOn().toString());
                support.notifyAddress("biomedical", address, "equipment.calibration-due", p);
            }
            e.setDueAlertedFor(e.getCalibrationDueOn());
            e.setUpdatedAt(support.now());
            events.publish(new E14Events.EquipmentCalibration(e.getId().toString(), state(e).name()));
            sent++;
        }
        return sent;
    }

    // ------------------------------------------------------------------ cold chain

    public record IotReading(String deviceId, BigDecimal temperatureC, Instant recordedAt) {
    }

    public record IotResult(int accepted, int rejected, int excursionsOpened, List<String> unknownDevices) {
    }

    @Transactional
    public ReadingView manualReading(CurrentUser user, UUID id, BigDecimal temperatureC, Instant recordedAt) {
        Equipment e = get(id);
        if (!isTemperatureMonitored(e)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "not_temperature_monitored", "This equipment has no temperature log");
        }
        Instant at = recordedAt == null ? support.now() : recordedAt;
        if (at.isAfter(support.now().plusSeconds(300))) {
            throw ApiException.badRequest("future_reading", "recordedAt cannot be in the future");
        }
        TemperatureReading r = record(e, temperatureC, at, "MANUAL", user.username());
        support.audit("TEMPERATURE_READING", "EQUIPMENT", id.toString());
        return new ReadingView(r.getRecordedAt(), r.getTemperatureC(), r.getSource(), r.isExcursion());
    }

    @Transactional
    public IotResult iotReadings(CurrentUser user, List<IotReading> batch) {
        if (batch.size() > MAX_IOT_BATCH) {
            throw ApiException.badRequest("too_many", "At most " + MAX_IOT_BATCH + " readings per call");
        }
        int accepted = 0;
        int rejected = 0;
        int opened = 0;
        List<String> unknown = new ArrayList<>();
        Map<String, Optional<Equipment>> cache = new LinkedHashMap<>();
        Instant latest = support.now().plusSeconds(300);
        List<IotReading> sorted = batch.stream().sorted(Comparator.comparing(IotReading::recordedAt)).toList();
        for (IotReading r : sorted) {
            Optional<Equipment> e = cache.computeIfAbsent(r.deviceId(), equipment::findByDeviceId).filter(EquipmentService::isTemperatureMonitored);
            if (e.isEmpty() || r.recordedAt().isAfter(latest)) {
                rejected++;
                if (e.isEmpty() && !unknown.contains(r.deviceId()) && unknown.size() < 100) {
                    unknown.add(r.deviceId());
                }
                continue;
            }
            long before = excursions.countByEquipmentIdAndStatusNot(e.get().getId(), "CLOSED");
            record(e.get(), r.temperatureC(), r.recordedAt(), "IOT", user.username());
            if (excursions.countByEquipmentIdAndStatusNot(e.get().getId(), "CLOSED") > before) {
                opened++;
            }
            accepted++;
        }
        support.audit("COLD_CHAIN_IOT", "EQUIPMENT", null);
        return new IotResult(accepted, rejected, opened, unknown);
    }

    /** Stores a reading and applies the excursion rule (open / extend / end). */
    TemperatureReading record(Equipment e, BigDecimal temperatureC, Instant at, String source, String actor) {
        BigDecimal t = temperatureC.setScale(2, RoundingMode.HALF_UP);
        Optional<Excursion> current = excursions.findFirstByEquipmentIdAndEndedAtIsNullAndStatusNotOrderByStartedAtDesc(e.getId(), "CLOSED");
        EquipmentRules.ExcursionAction action = EquipmentRules.evaluate(current.isPresent(), t, e.getMinTempC(), e.getMaxTempC());
        TemperatureReading r = TemperatureReading.create();
        r.setEquipmentId(e.getId());
        r.setRecordedAt(at);
        r.setTemperatureC(t);
        r.setSource(source);
        r.setExcursion(EquipmentRules.outOfRange(t, e.getMinTempC(), e.getMaxTempC()));
        r.setRecordedBy(actor);
        readings.save(r);
        switch (action) {
            case OPEN -> {
                Excursion x = Excursion.create();
                x.setEquipmentId(e.getId());
                x.setStartedAt(at);
                x.setMinC(t);
                x.setMaxC(t);
                x.setStatus("OPEN");
                excursions.saveAndFlush(x);
                events.publish(new E14Events.ColdChainExcursion(e.getId().toString(), x.getId().toString()));
                notifyExcursion(e, EquipmentRules.level(t, e.getMaxTempC()));
            }
            case EXTEND -> {
                Excursion x = current.orElseThrow();
                x.setMinC(x.getMinC().min(t));
                x.setMaxC(x.getMaxC().max(t));
            }
            case END -> current.orElseThrow().setEndedAt(at);
            default -> {
            }
        }
        return r;
    }

    /** {@code coldchain.excursion} to the site's clinicians (staff site codes) and the nursing head. */
    void notifyExcursion(Equipment e, String level) {
        ClinicSite site = support.site(e.getSiteCode());
        Map<String, String> p = new LinkedHashMap<>();
        p.put("documentNumber", e.getAssetTag());
        p.put("site", site.getName());
        p.put("level", level);
        for (StaffMember s : support.activeStaff()) {
            List<String> sites = StaffMember.list(s.getSiteCodes());
            boolean atSite = sites.contains(site.getCode()) || sites.contains(site.getLocationCode());
            boolean clinician = java.util.Arrays.stream(Roles.CLINICIANS).anyMatch(s::hasRole);
            if ((atSite && clinician) || s.hasRole(Roles.NURSING_HEAD)) {
                support.notifyStaff(s, "coldchain.excursion", p, Priority.URGENT);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ReadingView> temperatures(UUID id, Instant from, Instant to) {
        get(id);
        return readings.findBetween(id, from, to).stream().limit(10000)
                .map(r -> new ReadingView(r.getRecordedAt(), r.getTemperatureC(), r.getSource(), r.isExcursion())).toList();
    }

    @Transactional(readOnly = true)
    public List<ExcursionView> excursions(String statusFilter) {
        Map<UUID, Equipment> cache = new LinkedHashMap<>();
        return excursions.search(statusFilter).stream().limit(2000)
                .map(x -> excursionView(x, cache.computeIfAbsent(x.getEquipmentId(), k -> equipment.findById(k).orElse(null)))).toList();
    }

    @Transactional
    public ExcursionView acknowledge(CurrentUser user, UUID id, String action) {
        Excursion x = excursions.findById(id).orElseThrow(() -> ApiException.notFound("Excursion"));
        if ("CLOSED".equals(x.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_status", "The excursion is closed");
        }
        x.setStatus("ACKNOWLEDGED");
        x.setAction(action.strip());
        x.setAcknowledgedBy(user.username());
        x.setAcknowledgedAt(support.now());
        support.audit("EXCURSION_ACK", "EXCURSION", id.toString());
        return excursionView(x, equipment.findById(x.getEquipmentId()).orElse(null));
    }

    @Transactional
    public ExcursionView close(CurrentUser user, UUID id) {
        Excursion x = excursions.findById(id).orElseThrow(() -> ApiException.notFound("Excursion"));
        if (!"ACKNOWLEDGED".equals(x.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_status", "Acknowledge the excursion (with the action taken) first");
        }
        Instant now = support.now();
        if (x.getEndedAt() == null) {
            x.setEndedAt(now);
        }
        x.setStatus("CLOSED");
        x.setClosedBy(user.username());
        x.setClosedAt(now);
        support.audit("EXCURSION_CLOSE", "EXCURSION", id.toString());
        return excursionView(x, equipment.findById(x.getEquipmentId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public ColdChainReport report(UUID id, YearMonth month) {
        Equipment e = get(id);
        ZoneId zone = support.zone(e.getSiteCode());
        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        List<TemperatureReading> list = readings.findBetween(id, from, to);
        List<Excursion> xs = excursions.findByEquipmentIdOrderByStartedAtDesc(id).stream()
                .filter(x -> !x.getStartedAt().isBefore(from) && x.getStartedAt().isBefore(to)).toList();
        List<DayReport> days = new ArrayList<>();
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            LocalDate day = d;
            List<TemperatureReading> dayReadings = list.stream().filter(r -> r.getRecordedAt().atZone(zone).toLocalDate().equals(day)).toList();
            if (dayReadings.isEmpty()) {
                continue;
            }
            BigDecimal min = dayReadings.stream().map(TemperatureReading::getTemperatureC).min(BigDecimal::compareTo).orElse(null);
            BigDecimal max = dayReadings.stream().map(TemperatureReading::getTemperatureC).max(BigDecimal::compareTo).orElse(null);
            int started = (int) xs.stream().filter(x -> x.getStartedAt().atZone(zone).toLocalDate().equals(day)).count();
            days.add(new DayReport(day, min, max, dayReadings.size(), (int) dayReadings.stream().filter(TemperatureReading::isExcursion).count(), started));
        }
        return new ColdChainReport(id, e.getAssetTag(), e.getSiteCode(), month, e.getMinTempC(), e.getMaxTempC(), days, xs.size());
    }
}
