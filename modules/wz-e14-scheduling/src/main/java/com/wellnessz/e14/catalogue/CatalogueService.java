package com.wellnessz.e14.catalogue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e14.engine.SlotEngine;
import com.wellnessz.e14.model.Appointment;
import com.wellnessz.e14.model.AppointmentRepository;
import com.wellnessz.e14.model.AppointmentType;
import com.wellnessz.e14.model.AppointmentTypeRepository;
import com.wellnessz.e14.model.ClinicResource;
import com.wellnessz.e14.model.ClinicResourceRepository;
import com.wellnessz.e14.model.DiaryTemplate;
import com.wellnessz.e14.model.DiaryTemplateRepository;
import com.wellnessz.e14.model.EquipmentRepository;
import com.wellnessz.e14.support.SchedulingSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.security.identity.CurrentUser;

/** E14-F01 / BL-010: appointment types and service catalogue, site resources, weekly diary templates and internal charging. */
@Service
public class CatalogueService {

    public static final Set<String> ROOM_TYPES = Set.of("CONSULT", "AUDIO_BOOTH", "TREATMENT", "VACCINATION", "TELEHEALTH");
    public static final Set<String> DEVICE_CATEGORIES = Set.of("AUDIOMETER", "SPIROMETER", "ECG", "VISION_SCREENER", "VACCINE_FRIDGE", "OTHER");
    public static final int MAX_TEMPLATES = 200;

    private final AppointmentTypeRepository types;
    private final ClinicResourceRepository resources;
    private final DiaryTemplateRepository templates;
    private final EquipmentRepository equipment;
    private final AppointmentRepository appointments;
    private final SchedulingSupport support;

    public CatalogueService(AppointmentTypeRepository types, ClinicResourceRepository resources, DiaryTemplateRepository templates,
                            EquipmentRepository equipment, AppointmentRepository appointments, SchedulingSupport support) {
        this.types = types;
        this.resources = resources;
        this.templates = templates;
        this.equipment = equipment;
        this.appointments = appointments;
        this.support = support;
    }

    // ------------------------------------------------------------------ appointment types

    public record AppointmentTypeView(String code, String name, int durationMinutes, List<String> clinicianRoles, String roomType,
                                      String deviceCategory, boolean telehealth, boolean selfBookable, String chargeCode,
                                      BigDecimal internalPrice, boolean active) {
        static AppointmentTypeView of(AppointmentType t) {
            return new AppointmentTypeView(t.getCode(), t.getName(), t.getDurationMinutes(), SchedulingSupport.csv(t.getClinicianRoles()),
                    t.getRoomType(), t.getDeviceCategory(), t.isTelehealth(), t.isSelfBookable(), t.getChargeCode(), t.getInternalPrice(), t.isActive());
        }
    }

    public record TypeCommand(String name, int durationMinutes, List<String> clinicianRoles, String roomType, String deviceCategory,
                              boolean telehealth, boolean selfBookable, String chargeCode, BigDecimal internalPrice, boolean active) {
    }

    @Transactional(readOnly = true)
    public List<AppointmentTypeView> types() {
        return types.findAllByOrderByCode().stream().map(AppointmentTypeView::of).toList();
    }

    @Transactional
    public AppointmentTypeView upsertType(CurrentUser user, String code, TypeCommand c) {
        if (c.roomType() != null && !ROOM_TYPES.contains(c.roomType())) {
            throw ApiException.badRequest("invalid_room_type", "Unknown room type");
        }
        if (c.deviceCategory() != null && !DEVICE_CATEGORIES.contains(c.deviceCategory())) {
            throw ApiException.badRequest("invalid_device_category", "Unknown device category");
        }
        AppointmentType t = types.findById(code).orElseGet(() -> {
            AppointmentType n = new AppointmentType();
            n.setCode(code);
            return n;
        });
        t.setName(c.name().strip());
        t.setDurationMinutes(c.durationMinutes());
        t.setClinicianRoles(SchedulingSupport.csv(c.clinicianRoles() == null ? List.of() : c.clinicianRoles()));
        t.setRoomType(c.roomType());
        t.setDeviceCategory(c.deviceCategory());
        t.setTelehealth(c.telehealth());
        t.setSelfBookable(c.selfBookable());
        t.setChargeCode(c.chargeCode());
        t.setInternalPrice(c.internalPrice());
        t.setActive(c.active());
        t.setUpdatedBy(user.username());
        t.setUpdatedAt(support.now());
        types.save(t);
        support.audit("APPOINTMENT_TYPE_UPSERT", "APPOINTMENT_TYPE", code);
        return AppointmentTypeView.of(t);
    }

    public AppointmentType type(String code) {
        return types.findById(code).filter(AppointmentType::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown_appointment_type", "Unknown or inactive appointment type"));
    }

    public Map<String, AppointmentType> typeMap() {
        Map<String, AppointmentType> m = new LinkedHashMap<>();
        types.findAll().forEach(t -> m.put(t.getCode(), t));
        return m;
    }

    // ------------------------------------------------------------------ resources

    public record ResourceView(UUID id, String siteCode, String type, String code, String name, String username, String roomType,
                               UUID equipmentId, boolean active) {
        static ResourceView of(ClinicResource r) {
            return new ResourceView(r.getId(), r.getSiteCode(), r.getType(), r.getCode(), r.getName(), r.getUsername(), r.getRoomType(),
                    r.getEquipmentId(), r.isActive());
        }
    }

    public record ResourceCommand(String type, String code, String name, String username, String roomType, UUID equipmentId, Boolean active) {
    }

    @Transactional(readOnly = true)
    public List<ResourceView> resources(String siteCode) {
        support.site(siteCode);
        return resources.findBySiteCodeOrderByTypeAscCodeAsc(siteCode).stream().map(ResourceView::of).toList();
    }

    @Transactional
    public ResourceView upsertResource(CurrentUser user, String siteCode, ResourceCommand c) {
        support.site(siteCode);
        String code = c.code();
        if ("CLINICIAN".equals(c.type())) {
            String username = c.username() != null ? c.username() : c.code();
            if (support.staff(username).isEmpty()) {
                throw ApiException.badRequest("unknown_clinician", "Clinician is not in the staff directory");
            }
            code = username;
        }
        if ("ROOM".equals(c.type()) && (c.roomType() == null || !ROOM_TYPES.contains(c.roomType()))) {
            throw ApiException.badRequest("invalid_room_type", "A room needs a room type");
        }
        if ("DEVICE".equals(c.type())) {
            if (c.equipmentId() == null || equipment.findById(c.equipmentId()).isEmpty()) {
                throw ApiException.badRequest("unknown_equipment", "A device resource must reference registered equipment");
            }
        }
        String finalCode = code;
        ClinicResource r = resources.findBySiteCodeAndTypeAndCode(siteCode, c.type(), code).orElseGet(() -> {
            ClinicResource n = ClinicResource.create();
            n.setSiteCode(siteCode);
            n.setType(c.type());
            n.setCode(finalCode);
            return n;
        });
        r.setName(c.name().strip());
        r.setUsername("CLINICIAN".equals(c.type()) ? code : null);
        r.setRoomType("ROOM".equals(c.type()) ? c.roomType() : null);
        r.setEquipmentId("DEVICE".equals(c.type()) ? c.equipmentId() : null);
        r.setActive(c.active() == null || c.active());
        r.setUpdatedAt(support.now());
        resources.save(r);
        support.audit("RESOURCE_UPSERT", "CLINIC_RESOURCE", r.getId().toString());
        return ResourceView.of(r);
    }

    /** Active room codes of a room type at a site. */
    public List<String> roomsOfType(String siteCode, String roomType) {
        return resources.findBySiteCodeAndTypeAndActiveTrue(siteCode, "ROOM").stream()
                .filter(r -> roomType.equals(r.getRoomType())).map(ClinicResource::getCode).sorted().toList();
    }

    public boolean roomExists(String siteCode, String roomCode) {
        return resources.findBySiteCodeAndTypeAndCode(siteCode, "ROOM", roomCode).filter(ClinicResource::isActive).isPresent();
    }

    /** In-service devices of a category at a site, with their calibration expiry. */
    public List<SlotEngine.DeviceOption> devices(String siteCode, String category) {
        return equipment.findBySiteCodeAndCategoryAndStatus(siteCode, category, "IN_SERVICE").stream()
                .sorted((a, b) -> a.getAssetTag().compareTo(b.getAssetTag()))
                .map(e -> new SlotEngine.DeviceOption(e.getDeviceId(), e.getCalibrationDueOn())).toList();
    }

    // ------------------------------------------------------------------ templates

    public record TemplateView(UUID id, int weekday, String start, String end, String clinicianUsername, String clinicianName, String roomCode,
                               List<String> appointmentTypes, boolean telehealth) {
        static TemplateView of(DiaryTemplate t, String clinicianName) {
            return new TemplateView(t.getId(), t.getWeekday(), t.getStartTime().toString(), t.getEndTime().toString(), t.getClinicianUsername(),
                    clinicianName, t.getRoomCode(), SchedulingSupport.csv(t.getAppointmentTypes()), t.isTelehealth());
        }
    }

    public record TemplateCommand(int weekday, LocalTime start, LocalTime end, String clinicianUsername, String roomCode,
                                  List<String> appointmentTypes, boolean telehealth) {
    }

    @Transactional(readOnly = true)
    public List<TemplateView> templates(String siteCode) {
        support.site(siteCode);
        return templates.findBySiteCodeOrderByWeekdayAscStartTimeAsc(siteCode).stream()
                .map(t -> TemplateView.of(t, support.staffName(t.getClinicianUsername()))).toList();
    }

    /** Replaces the site's weekly templates. Sessions of one clinician must not overlap on a weekday (across sites). */
    @Transactional
    public List<TemplateView> replaceTemplates(CurrentUser user, String siteCode, List<TemplateCommand> rows) {
        support.site(siteCode);
        if (rows.size() > MAX_TEMPLATES) {
            throw ApiException.badRequest("too_many_templates", "At most " + MAX_TEMPLATES + " template rows per site");
        }
        Map<String, AppointmentType> known = typeMap();
        for (TemplateCommand r : rows) {
            if (!r.start().isBefore(r.end())) {
                throw ApiException.badRequest("invalid_session", "Session start must be before its end");
            }
            StaffMember s = support.staff(r.clinicianUsername())
                    .orElseThrow(() -> ApiException.badRequest("unknown_clinician", "Clinician is not in the staff directory"));
            if (!s.isActive()) {
                throw ApiException.badRequest("unknown_clinician", "Clinician is not active");
            }
            if (r.roomCode() != null && !roomExists(siteCode, r.roomCode())) {
                throw ApiException.badRequest("unknown_room", "Room " + r.roomCode() + " is not a resource of the site");
            }
            if (r.appointmentTypes() != null) {
                for (String t : r.appointmentTypes()) {
                    if (!known.containsKey(t)) {
                        throw ApiException.badRequest("unknown_appointment_type", "Unknown appointment type " + t);
                    }
                }
            }
        }
        // a clinician cannot hold two overlapping sessions on the same weekday (this site's new rows + other sites)
        List<DiaryTemplate> others = templates.findAllByOrderBySiteCodeAscWeekdayAscStartTimeAsc().stream()
                .filter(t -> !t.getSiteCode().equals(siteCode)).toList();
        for (int i = 0; i < rows.size(); i++) {
            TemplateCommand a = rows.get(i);
            for (int j = i + 1; j < rows.size(); j++) {
                TemplateCommand b = rows.get(j);
                if (a.weekday() == b.weekday() && a.clinicianUsername().equals(b.clinicianUsername())
                        && a.start().isBefore(b.end()) && b.start().isBefore(a.end())) {
                    throw ApiException.conflict("resource_conflict", "Overlapping sessions for " + a.clinicianUsername());
                }
            }
            for (DiaryTemplate o : others) {
                if (o.getWeekday() == a.weekday() && o.getClinicianUsername().equals(a.clinicianUsername())
                        && a.start().isBefore(o.getEndTime()) && o.getStartTime().isBefore(a.end())) {
                    throw ApiException.conflict("resource_conflict", "The clinician already has a session at " + o.getSiteCode());
                }
            }
        }
        templates.deleteAll(templates.findBySiteCodeOrderByWeekdayAscStartTimeAsc(siteCode));
        templates.flush();
        Instant now = support.now();
        List<DiaryTemplate> saved = new ArrayList<>();
        for (TemplateCommand r : rows) {
            DiaryTemplate t = DiaryTemplate.create();
            t.setSiteCode(siteCode);
            t.setWeekday(r.weekday());
            t.setStartTime(r.start());
            t.setEndTime(r.end());
            t.setClinicianUsername(r.clinicianUsername());
            t.setRoomCode(r.roomCode());
            t.setAppointmentTypes(SchedulingSupport.csv(r.appointmentTypes() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(r.appointmentTypes()))));
            t.setTelehealth(r.telehealth());
            t.setUpdatedBy(user.username());
            t.setUpdatedAt(now);
            saved.add(templates.save(t));
        }
        support.audit("DIARY_TEMPLATES_REPLACE", "CLINIC_SITE", siteCode);
        return saved.stream().map(t -> TemplateView.of(t, support.staffName(t.getClinicianUsername()))).toList();
    }

    public List<SlotEngine.TemplateRow> templateRows(String siteCode) {
        return templates.findBySiteCodeOrderByWeekdayAscStartTimeAsc(siteCode).stream()
                .map(t -> new SlotEngine.TemplateRow(t.getWeekday(), t.getStartTime(), t.getEndTime(), t.getClinicianUsername(), t.getRoomCode(),
                        new HashSet<>(SchedulingSupport.csv(t.getAppointmentTypes())), t.isTelehealth()))
                .toList();
    }

    // ------------------------------------------------------------------ internal charging

    public record UsageRow(String subsidiaryCode, String chargeCode, String service, long appointments, BigDecimal amount) {
    }

    /** Attended appointments of the month by employee subsidiary and charge code (cross-charging, BL-010). */
    @Transactional(readOnly = true)
    public List<UsageRow> usage(YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Appointment> attended = appointments.findAttendedBetween(from, to);
        Map<String, AppointmentType> typeMap = typeMap();
        Map<String, Employee> people = support.employees(attended.stream().map(Appointment::getEmployeeId).toList());
        Map<List<String>, long[]> counts = new java.util.TreeMap<>((a, b) -> (a.get(0) + "|" + a.get(1)).compareTo(b.get(0) + "|" + b.get(1)));
        for (Appointment a : attended) {
            AppointmentType t = typeMap.get(a.getAppointmentType());
            String subsidiary = people.containsKey(a.getEmployeeId()) ? people.get(a.getEmployeeId()).getSubsidiaryCode() : "UNASSIGNED";
            String charge = t == null || t.getChargeCode() == null ? "UNCHARGED" : t.getChargeCode();
            counts.computeIfAbsent(List.of(subsidiary, charge, a.getAppointmentType()), k -> new long[1])[0]++;
        }
        List<UsageRow> out = new ArrayList<>();
        counts.forEach((k, n) -> {
            AppointmentType t = typeMap.get(k.get(2));
            BigDecimal price = t == null || t.getInternalPrice() == null ? BigDecimal.ZERO : t.getInternalPrice();
            out.add(new UsageRow(k.get(0), k.get(1), t == null ? k.get(2) : t.getName(), n[0],
                    price.multiply(BigDecimal.valueOf(n[0])).setScale(2, RoundingMode.HALF_UP)));
        });
        return out;
    }

    public static String usageCsv(YearMonth month, List<UsageRow> rows) {
        StringBuilder sb = new StringBuilder("month,subsidiaryCode,chargeCode,service,appointments,amount\n");
        for (UsageRow r : rows) {
            sb.append(month).append(',').append(cell(r.subsidiaryCode())).append(',').append(cell(r.chargeCode())).append(',')
                    .append(cell(r.service())).append(',').append(r.appointments()).append(',').append(r.amount().toPlainString()).append('\n');
        }
        return sb.toString();
    }

    /** CSV cell with quoting and spreadsheet formula neutralisation. */
    static String cell(String v) {
        if (v == null) {
            return "";
        }
        String s = v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
