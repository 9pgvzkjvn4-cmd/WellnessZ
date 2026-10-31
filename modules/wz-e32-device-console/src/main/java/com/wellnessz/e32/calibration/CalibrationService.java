package com.wellnessz.e32.calibration;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceEquipmentRegister;
import com.wellnessz.casekernel.support.DeviceCalibrationCheck;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.documents.PdfRenderer;
import com.wellnessz.e32.catalogue.ModelStore;
import com.wellnessz.e32.config.Configuration;
import com.wellnessz.e32.config.ConfigurationStore;
import com.wellnessz.e32.support.ApprovalStore;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Calibration, QC and lockout (E32-F04, contract §13.6): schedules per family (relaxing changes need four-eyes approval),
 * quick check entry at the station, QC received from devices, derived lockout and release (only by a newer passing check),
 * status per site / station with the E14 calibration verdict ({@link DeviceCalibrationCheck}), compliance report
 * (JSON / CSV / PDF), reminders, and the E14 equipment-register migration report (E32-BL-017).
 */
@Service("e32CalibrationService")
public class CalibrationService {

    private final E32Support support;
    private final ConfigurationStore configurations;
    private final ModelStore models;
    private final List<DeviceCalibrationCheck> calibrationChecks;
    private final ApprovalStore approvals;
    private final PdfRenderer pdf;

    public CalibrationService(E32Support support, ConfigurationStore configurations, ModelStore models, List<DeviceCalibrationCheck> calibrationChecks,
                              ApprovalStore approvals, PdfRenderer pdf) {
        this.support = support;
        this.configurations = configurations;
        this.models = models;
        this.calibrationChecks = List.copyOf(calibrationChecks);
        this.approvals = approvals;
        this.pdf = pdf;
    }

    // ------------------------------------------------------------------ schedules

    public List<QcRules.Schedule> schedules() {
        return support.jdbc().query("""
                select family, check_type, interval_hours, grace_minutes, lockout, reminder_before_hours, per_lot, description
                from devicecat.calibration_schedule order by family, check_type""",
                (rs, i) -> new QcRules.Schedule(rs.getString("family"), rs.getString("check_type"), rs.getInt("interval_hours"),
                        rs.getInt("grace_minutes"), rs.getBoolean("lockout"), rs.getInt("reminder_before_hours"), rs.getBoolean("per_lot"),
                        rs.getString("description")));
    }

    public List<QcRules.Schedule> familySchedules(String family) {
        return schedules().stream().filter(s -> s.family().equals(family)).toList();
    }

    public Map<String, Integer> familyIntervals(String family) {
        Map<String, Integer> m = new LinkedHashMap<>();
        familySchedules(family).forEach(s -> m.put(s.checkType(), s.intervalHours()));
        return m;
    }

    public record ScheduleCommand(Integer intervalHours, Integer graceMinutes, Boolean lockout, Integer reminderBeforeHours) {
    }

    /** Tightening applies at once; relaxing (longer interval / grace, lockout off) opens a four-eyes approval. */
    @Transactional
    public Map<String, Object> putSchedule(CurrentUser user, String family, String checkType, ScheduleCommand cmd) {
        support.requireEnabled();
        QcRules.Schedule cur = schedules().stream().filter(s -> s.family().equals(family) && s.checkType().equals(checkType)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Calibration schedule"));
        int interval = cmd.intervalHours() == null ? cur.intervalHours() : cmd.intervalHours();
        int grace = cmd.graceMinutes() == null ? cur.graceMinutes() : cmd.graceMinutes();
        boolean lockout = cmd.lockout() == null ? cur.lockout() : cmd.lockout();
        int reminder = cmd.reminderBeforeHours() == null ? cur.reminderBeforeHours() : cmd.reminderBeforeHours();
        if (interval < 1 || interval > 87600 || grace < 0 || grace > 1440 || reminder < 0 || reminder > 2160) {
            throw ApiException.badRequest("validation_failed", "interval 1–87600 h, grace 0–1440 min, reminder 0–2160 h");
        }
        Map<String, Object> change = Docs.of("family", family, "checkType", checkType, "intervalHours", interval, "graceMinutes", grace,
                "lockout", lockout, "reminderBeforeHours", reminder);
        if (QcRules.relaxes(cur, interval, grace, lockout)) {
            UUID id = approvals.open("SCHEDULE_CHANGE", family + "/" + checkType, null, user.username(), change);
            support.notifyRole("DEVICE_APPROVER", "device.approval-required", support.params(family + "/" + checkType, null), user.username());
            support.history("SCHEDULE", family + "/" + checkType, null, user.username(), "CHANGE_REQUESTED", null, change, id.toString());
            Map<String, Object> out = scheduleView(cur);
            out.put("pendingApprovalId", id);
            out.put("status", "PENDING_APPROVAL");
            return out;
        }
        applySchedule(user.username(), change, null);
        return scheduleView(schedules().stream().filter(s -> s.family().equals(family) && s.checkType().equals(checkType)).findFirst().orElseThrow());
    }

    /** Applies a schedule change (directly when tightening, or from an approved request). */
    public void applySchedule(String actor, Map<String, Object> change, String approvalRef) {
        support.jdbc().update("""
                update devicecat.calibration_schedule set interval_hours = ?, grace_minutes = ?, lockout = ?, reminder_before_hours = ?,
                    updated_by = ?, updated_at = ? where family = ? and check_type = ?""",
                Docs.integer(change.get("intervalHours")), Docs.integer(change.get("graceMinutes")), Docs.bool(change.get("lockout"), true),
                Docs.integer(change.get("reminderBeforeHours")), actor, Timestamp.from(support.now()), Docs.str(change.get("family")),
                Docs.str(change.get("checkType")));
        support.history("SCHEDULE", change.get("family") + "/" + change.get("checkType"), null, actor, "CHANGE", null, change, approvalRef);
    }

    public static Map<String, Object> scheduleView(QcRules.Schedule s) {
        return new LinkedHashMap<>(Docs.of("family", s.family(), "checkType", s.checkType(), "intervalHours", s.intervalHours(),
                "graceMinutes", s.graceMinutes(), "lockout", s.lockout(), "reminderBeforeHours", s.reminderBeforeHours(), "perLot", s.perLot(),
                "description", s.description()));
    }

    // ------------------------------------------------------------------ evaluation

    public List<QcRules.Schedule> instanceSchedules(Configuration c) {
        String family = models.find(c.modelCode()).map(m -> m.family().name()).orElse("");
        return QcRules.effective(familySchedules(family), Docs.list(c.common().get("calibrationSchedule")));
    }

    public List<QcRules.Check> checks(String instanceId) {
        return support.jdbc().query("""
                select check_type, performed_at, outcome, lot, lot_expiry from devicecat.qc_check where device_instance_id = ?
                order by performed_at desc limit 500""",
                (rs, i) -> new QcRules.Check(rs.getString("check_type"), rs.getTimestamp("performed_at").toInstant(), rs.getString("outcome"),
                        rs.getString("lot"), rs.getDate("lot_expiry") == null ? null : rs.getDate("lot_expiry").toLocalDate()), instanceId);
    }

    public List<QcRules.Verdict> verdicts(Configuration c, Instant at, String lot, LocalDate day) {
        List<QcRules.Check> checks = checks(c.deviceInstanceId());
        List<QcRules.Verdict> out = new ArrayList<>();
        for (QcRules.Schedule s : instanceSchedules(c)) {
            out.add(QcRules.evaluate(s, checks, at, s.perLot() ? lot : null, day));
        }
        return out;
    }

    /** {@code DeviceCatalogueLookup.qcStatus}: the verdict as of {@code on} (now for today, end of day for a past day). */
    @Transactional(readOnly = true)
    public DeviceCatalogueLookup.QcStatus qcStatus(String instanceId, LocalDate on, String lot) {
        Optional<Configuration> c = configurations.live(instanceId);
        if (c.isEmpty()) {
            return DeviceCatalogueLookup.QcStatus.unknown();
        }
        LocalDate day = on == null ? support.today() : on;
        Instant at = day.isBefore(support.today()) ? day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(1) : support.now();
        List<QcRules.Verdict> verdicts = verdicts(c.get(), at, lot, day);
        if (lot != null) {
            verdicts = verdicts.stream().filter(v -> instanceSchedules(c.get()).stream()
                    .anyMatch(s -> s.checkType().equals(v.checkType()) && s.perLot())).toList();
            if (verdicts.isEmpty()) {
                return new DeviceCatalogueLookup.QcStatus("NOT_REQUIRED", null, null, null, lot, null);
            }
        }
        QcRules.Verdict v = QcRules.aggregate(verdicts);
        return new DeviceCatalogueLookup.QcStatus(v.verdict(), v.checkType(), v.lastCheckAt(), v.dueAt(), v.lot(), v.lotExpiry());
    }

    public Map<String, Object> e14Calibration(Configuration c) {
        if (c.equipmentDeviceId() == null) {
            return Docs.of("verdict", "UNKNOWN", "reason", "not linked to the equipment register");
        }
        DeviceCalibrationCheck.Verdict worst = DeviceCalibrationCheck.Verdict.UNKNOWN;
        LocalDate due = null;
        String certificate = null;
        for (DeviceCalibrationCheck check : calibrationChecks) {
            DeviceCalibrationCheck.Status s = check.check(c.equipmentDeviceId(), support.today());
            if (s != null && s.verdict() != null && s.verdict().ordinal() > worst.ordinal()) {
                worst = s.verdict();
                due = s.calibrationDueOn();
                certificate = s.calibrationId();
            } else if (s != null && s.verdict() == DeviceCalibrationCheck.Verdict.VALID && worst == DeviceCalibrationCheck.Verdict.VALID && due == null) {
                due = s.calibrationDueOn();
            }
        }
        return Docs.of("verdict", worst.name(), "dueOn", due, "certificate", certificate, "equipmentDeviceId", c.equipmentDeviceId());
    }

    public Map<String, Object> instanceStatus(Configuration c) {
        List<QcRules.Verdict> verdicts = verdicts(c, support.now(), null, support.today());
        List<Map<String, Object>> checks = new ArrayList<>();
        boolean locked = false;
        for (QcRules.Verdict v : verdicts) {
            checks.add(Docs.of("checkType", v.checkType(), "verdict", v.verdict(), "lastCheckAt", v.lastCheckAt(), "dueAt", v.dueAt(),
                    "lockout", v.lockout(), "lot", v.lot()));
            locked |= v.blocks();
        }
        Map<String, Object> cal = e14Calibration(c);
        String calVerdict = Docs.str(cal.get("verdict"));
        boolean calBlocks = "OUT_OF_CALIBRATION".equals(calVerdict) || "OUT_OF_SERVICE".equals(calVerdict);
        String model = models.find(c.modelCode()).map(m -> m.manufacturer() + " " + m.model()).orElse(c.modelCode());
        return Docs.of("deviceInstanceId", c.deviceInstanceId(), "modelCode", c.modelCode(), "model", model, "siteCode", c.siteCode(),
                "stationCode", c.stationCode(), "status", c.status(), "checks", checks, "calibration", cal, "lockedOut", locked || calBlocks,
                "lockoutReason", locked ? "QC" : calBlocks ? "CALIBRATION" : null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> status(String siteCode, String stationCode) {
        support.requireEnabled();
        return configurations.liveAll().stream()
                .filter(c -> siteCode == null || siteCode.equals(c.siteCode()))
                .filter(c -> stationCode == null || stationCode.equals(c.stationCode()))
                .map(this::instanceStatus).toList();
    }

    // ------------------------------------------------------------------ checks, lockout and release

    public record CheckCommand(String deviceInstanceId, String checkType, Instant performedAt, String outcome, Map<String, Object> values, String lot,
                               LocalDate lotExpiry, String evidenceDocumentId) {
    }

    @Transactional
    public Map<String, Object> recordCheck(CurrentUser user, CheckCommand cmd) {
        support.requireEnabled();
        return record(user.username(), cmd, "MANUAL");
    }

    /** QC from devices ({@code device.qc.received.v1} published by E31 – PHI-free). */
    @Transactional
    public void recordDeviceQc(Map<String, Object> payload) {
        String instance = Docs.str(payload.get("deviceInstanceId"));
        if (instance == null || configurations.live(instance).isEmpty()) {
            return;
        }
        Instant performed = payload.get("performedAt") == null ? support.now() : Instant.parse(Docs.str(payload.get("performedAt")));
        LocalDate expiry = payload.get("lotExpiry") == null ? null : LocalDate.parse(Docs.str(payload.get("lotExpiry")));
        String outcome = "FAIL".equals(Docs.str(payload.get("outcome"))) || "FAILED".equals(Docs.str(payload.get("outcome"))) ? "FAIL" : "PASS";
        record("device", new CheckCommand(instance, Docs.str(payload.get("checkType")), performed, outcome, null, Docs.str(payload.get("lot")),
                expiry, null), "DEVICE");
    }

    private Map<String, Object> record(String actor, CheckCommand cmd, String source) {
        Configuration c = configurations.live(cmd.deviceInstanceId()).orElseThrow(() -> ApiException.notFound("Device instance"));
        if (!"PASS".equals(cmd.outcome()) && !"FAIL".equals(cmd.outcome())) {
            throw ApiException.badRequest("validation_failed", "outcome must be PASS or FAIL");
        }
        QcRules.Schedule schedule = instanceSchedules(c).stream().filter(s -> s.checkType().equals(cmd.checkType())).findFirst()
                .orElseThrow(() -> E32Support.unprocessable("check_type_not_applicable", "This check type does not apply to the device"));
        Instant performed = cmd.performedAt() == null ? support.now() : cmd.performedAt();
        if (performed.isAfter(support.now().plus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("validation_failed", "performedAt cannot be in the future");
        }
        if (schedule.perLot() && (cmd.lot() == null || cmd.lot().isBlank())) {
            throw ApiException.badRequest("validation_failed", "the QC lot is required for this check");
        }
        boolean wasLocked = verdicts(c, support.now(), null, support.today()).stream().anyMatch(QcRules.Verdict::blocks);
        support.jdbc().update("""
                insert into devicecat.qc_check (id, device_instance_id, check_type, performed_at, outcome, check_values, lot, lot_expiry,
                    evidence_document_id, source, recorded_by, recorded_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), c.deviceInstanceId(), cmd.checkType(), Timestamp.from(performed), cmd.outcome(),
                cmd.values() == null ? null : support.write(cmd.values()), cmd.lot(), cmd.lotExpiry() == null ? null : java.sql.Date.valueOf(cmd.lotExpiry()),
                cmd.evidenceDocumentId(), source, actor, Timestamp.from(support.now()));
        support.audit("DEVICE_QC_CHECK", "DEVICE_INSTANCE", c.deviceInstanceId(), cmd.checkType() + " " + cmd.outcome());
        Map<String, Object> status = instanceStatus(c);
        boolean locked = verdicts(c, support.now(), null, support.today()).stream().anyMatch(QcRules.Verdict::blocks);
        if ("FAIL".equals(cmd.outcome()) && schedule.lockout()) {
            lock(c, cmd.checkType(), "QC_FAILED", actor);
        } else if (wasLocked && !locked) {
            lockoutEvent(c.deviceInstanceId(), cmd.checkType(), "RELEASED", "PASS_RECORDED", actor);
            closeAlerts(c.deviceInstanceId(), "LOCKED_OUT");
        }
        return status;
    }

    /** Records a lockout once (event, fleet alert, content-free notification to the on-duty administrator and nursing head). */
    public void lock(Configuration c, String checkType, String reason, String actor) {
        List<String> last = support.jdbc().queryForList("""
                select event from devicecat.lockout_event where device_instance_id = ? and check_type = ? order by at desc limit 1""",
                String.class, c.deviceInstanceId(), checkType);
        if (!last.isEmpty() && "LOCKED".equals(last.get(0))) {
            return;
        }
        lockoutEvent(c.deviceInstanceId(), checkType, "LOCKED", reason, actor);
        int inserted = support.jdbc().update("""
                insert into devicecat.fleet_alert (id, device_instance_id, site_code, alert_type, level, detail, dedup_key, status, raised_at)
                values (?, ?, ?, 'LOCKED_OUT', 'HIGH', ?, ?, 'OPEN', ?) on conflict do nothing""",
                UUID.randomUUID(), c.deviceInstanceId(), c.siteCode(), checkType + " " + reason, "LOCKED_OUT:" + c.deviceInstanceId(),
                Timestamp.from(support.now()));
        if (inserted > 0) {
            support.notifyOnDuty(c.siteCode(), "device.locked-out", support.params(c.deviceInstanceId(), c.siteCode()), true);
            support.notifyRole("NURSING_HEAD", "device.locked-out", support.params(c.deviceInstanceId(), c.siteCode()), null);
        }
    }

    private void lockoutEvent(String instanceId, String checkType, String event, String reason, String actor) {
        support.jdbc().update("""
                insert into devicecat.lockout_event (id, device_instance_id, check_type, event, reason, actor, at) values (?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), instanceId, checkType, event, reason, actor, Timestamp.from(support.now()));
    }

    private void closeAlerts(String instanceId, String type) {
        support.jdbc().update("update devicecat.fleet_alert set status = 'CLOSED', closed_at = ? where device_instance_id = ? and alert_type = ? and status <> 'CLOSED'",
                Timestamp.from(support.now()), instanceId, type);
    }

    /** Release only with a newer passing check – there is no override (contract §13.6). */
    @Transactional
    public Map<String, Object> release(CurrentUser user, String instanceId) {
        support.requireEnabled();
        Configuration c = configurations.live(instanceId).orElseThrow(() -> ApiException.notFound("Device instance"));
        List<QcRules.Verdict> blocking = verdicts(c, support.now(), null, support.today()).stream().filter(QcRules.Verdict::blocks).toList();
        if (!blocking.isEmpty()) {
            QcRules.Verdict v = blocking.get(0);
            throw new ApiException(HttpStatus.CONFLICT, "FAILED".equals(v.verdict()) ? "qc_failed" : "qc_due",
                    "Record a passing " + v.checkType() + " check before releasing the device");
        }
        for (String type : support.jdbc().queryForList("""
                select distinct check_type from devicecat.lockout_event e where device_instance_id = ? and event = 'LOCKED'
                and not exists (select 1 from devicecat.lockout_event r where r.device_instance_id = e.device_instance_id
                    and r.check_type = e.check_type and r.event = 'RELEASED' and r.at > e.at)""", String.class, instanceId)) {
            lockoutEvent(instanceId, type, "RELEASED", "MANUAL_RELEASE", user.username());
        }
        closeAlerts(instanceId, "LOCKED_OUT");
        support.audit("DEVICE_LOCKOUT_RELEASE", "DEVICE_INSTANCE", instanceId);
        return instanceStatus(c);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> history(String instanceId) {
        support.requireEnabled();
        Configuration c = configurations.live(instanceId).orElseThrow(() -> ApiException.notFound("Device instance"));
        List<Map<String, Object>> checks = support.jdbc().query("""
                select check_type, performed_at, outcome, lot, lot_expiry, source, recorded_by, evidence_document_id from devicecat.qc_check
                where device_instance_id = ? order by performed_at desc limit 500""",
                (rs, i) -> Docs.of("checkType", rs.getString("check_type"), "performedAt", rs.getTimestamp("performed_at").toInstant(),
                        "outcome", rs.getString("outcome"), "lot", rs.getString("lot"),
                        "lotExpiry", rs.getDate("lot_expiry") == null ? null : rs.getDate("lot_expiry").toLocalDate(), "source", rs.getString("source"),
                        "recordedBy", rs.getString("recorded_by"), "evidenceDocumentId", rs.getString("evidence_document_id")), instanceId);
        List<Map<String, Object>> events = support.jdbc().query("""
                select check_type, event, reason, actor, at from devicecat.lockout_event where device_instance_id = ? order by at desc limit 200""",
                (rs, i) -> Docs.of("checkType", rs.getString("check_type"), "event", rs.getString("event"), "reason", rs.getString("reason"),
                        "actor", rs.getString("actor"), "at", rs.getTimestamp("at").toInstant()), instanceId);
        Map<String, Object> out = instanceStatus(c);
        out.put("history", checks);
        out.put("lockoutEvents", events);
        return out;
    }

    // ------------------------------------------------------------------ compliance report

    public record Report(String contentType, byte[] body, Map<String, Object> json) {
    }

    @Transactional(readOnly = true)
    public Report report(String siteCode, LocalDate from, LocalDate to, String format) {
        support.requireEnabled();
        LocalDate end = to == null ? support.today() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        Instant a = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant b = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, List<Configuration>> bySite = new LinkedHashMap<>();
        for (Configuration c : configurations.liveAll()) {
            if (siteCode == null || siteCode.equals(c.siteCode())) {
                bySite.computeIfAbsent(c.siteCode() == null ? "-" : c.siteCode(), k -> new ArrayList<>()).add(c);
            }
        }
        List<Map<String, Object>> sites = new ArrayList<>();
        for (Map.Entry<String, List<Configuration>> e : bySite.entrySet()) {
            int compliant = 0;
            int pass = 0;
            int fail = 0;
            int lockouts = 0;
            List<Map<String, Object>> devices = new ArrayList<>();
            for (Configuration c : e.getValue()) {
                Map<String, Object> st = instanceStatus(c);
                boolean ok = !Docs.bool(st.get("lockedOut"), false);
                compliant += ok ? 1 : 0;
                List<Map<String, Object>> counts = support.jdbc().queryForList("""
                        select outcome, count(*) as n from devicecat.qc_check where device_instance_id = ? and performed_at >= ? and performed_at < ?
                        group by outcome""", c.deviceInstanceId(), Timestamp.from(a), Timestamp.from(b));
                int p = 0;
                int f = 0;
                for (Map<String, Object> r : counts) {
                    int n = ((Number) r.get("n")).intValue();
                    if ("PASS".equals(r.get("outcome"))) {
                        p += n;
                    } else {
                        f += n;
                    }
                }
                Integer l = support.jdbc().queryForObject("""
                        select count(*) from devicecat.lockout_event where device_instance_id = ? and event = 'LOCKED' and at >= ? and at < ?""",
                        Integer.class, c.deviceInstanceId(), Timestamp.from(a), Timestamp.from(b));
                pass += p;
                fail += f;
                lockouts += l == null ? 0 : l;
                devices.add(Docs.of("deviceInstanceId", c.deviceInstanceId(), "modelCode", c.modelCode(), "stationCode", c.stationCode(),
                        "compliant", ok, "checksPassed", p, "checksFailed", f, "lockouts", l, "calibration", st.get("calibration")));
            }
            int total = e.getValue().size();
            sites.add(Docs.of("siteCode", e.getKey(), "devices", total, "compliant", compliant,
                    "compliancePct", total == 0 ? 100.0 : Math.round(1000.0 * compliant / total) / 10.0, "checksPassed", pass, "checksFailed", fail,
                    "lockouts", lockouts, "items", devices));
        }
        Map<String, Object> json = Docs.of("from", start, "to", end, "generatedAt", support.now(), "sites", sites);
        String fmt = format == null ? "json" : format.toLowerCase(Locale.ROOT);
        return switch (fmt) {
            case "csv" -> new Report("text/csv", csv(sites).getBytes(StandardCharsets.UTF_8), json);
            case "pdf" -> new Report("application/pdf", pdfReport(start, end, sites), json);
            case "json" -> new Report("application/json", null, json);
            default -> throw ApiException.badRequest("validation_failed", "format must be json, csv or pdf");
        };
    }

    private static String csv(List<Map<String, Object>> sites) {
        StringBuilder sb = new StringBuilder("siteCode,deviceInstanceId,modelCode,stationCode,compliant,checksPassed,checksFailed,lockouts\n");
        for (Map<String, Object> s : sites) {
            for (Object o : Docs.list(s.get("items"))) {
                Map<String, Object> d = Docs.map(o);
                sb.append(cell(s.get("siteCode"))).append(',').append(cell(d.get("deviceInstanceId"))).append(',').append(cell(d.get("modelCode")))
                        .append(',').append(cell(d.get("stationCode"))).append(',').append(d.get("compliant")).append(',').append(d.get("checksPassed"))
                        .append(',').append(d.get("checksFailed")).append(',').append(d.get("lockouts")).append('\n');
            }
        }
        return sb.toString();
    }

    private static String cell(Object o) {
        String s = o == null ? "" : String.valueOf(o);
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) {
            s = "'" + s;
        }
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private byte[] pdfReport(LocalDate from, LocalDate to, List<Map<String, Object>> sites) {
        List<PdfRenderer.Section> sections = new ArrayList<>();
        for (Map<String, Object> s : sites) {
            StringBuilder t = new StringBuilder();
            t.append("Devices: ").append(s.get("devices")).append(" - compliant: ").append(s.get("compliant")).append(" (")
                    .append(s.get("compliancePct")).append(" %) - checks passed / failed: ").append(s.get("checksPassed")).append(" / ")
                    .append(s.get("checksFailed")).append(" - lockouts: ").append(s.get("lockouts")).append('\n');
            for (Object o : Docs.list(s.get("items"))) {
                Map<String, Object> d = Docs.map(o);
                t.append(d.get("deviceInstanceId")).append(' ').append(d.get("modelCode")).append(" at ").append(d.get("stationCode"))
                        .append(Docs.bool(d.get("compliant"), false) ? " - compliant" : " - LOCKED OUT").append('\n');
            }
            sections.add(new PdfRenderer.Section("Site " + s.get("siteCode"), t.toString()));
        }
        if (sections.isEmpty()) {
            sections.add(new PdfRenderer.Section("Sites", "No published devices in scope."));
        }
        return pdf.render("Device calibration and QC compliance report", "DCQ-" + from + "-" + to, 1, sections,
                "Calibration certificates are held in the equipment register (E14); daily checks and QC lots in the device console.").pdf();
    }

    // ------------------------------------------------------------------ reminders and lockout sweep (job)

    /** Hourly: lockout sweep and calibration-due reminders (once per instance, check type and due time). */
    @Transactional
    public int sweep() {
        int notified = 0;
        Instant now = support.now();
        for (Configuration c : configurations.liveAll()) {
            if (!Configuration.PUBLISHED.equals(c.status())) {
                continue;
            }
            for (QcRules.Verdict v : verdicts(c, now, null, support.today())) {
                if (v.blocks()) {
                    lock(c, v.checkType(), "FAILED".equals(v.verdict()) ? "QC_FAILED" : "QC_OVERDUE", "system");
                    continue;
                }
                QcRules.Schedule s = instanceSchedules(c).stream().filter(x -> x.checkType().equals(v.checkType())).findFirst().orElse(null);
                if (s != null && v.dueAt() != null && now.plus(Duration.ofHours(s.reminderBeforeHours())).isAfter(v.dueAt())) {
                    int inserted = support.jdbc().update("""
                            insert into devicecat.fleet_alert (id, device_instance_id, site_code, alert_type, level, detail, dedup_key, status, raised_at)
                            values (?, ?, ?, 'CALIBRATION_DUE', 'MEDIUM', ?, ?, 'OPEN', ?) on conflict do nothing""",
                            UUID.randomUUID(), c.deviceInstanceId(), c.siteCode(), v.checkType() + " due",
                            "CALIBRATION_DUE:" + c.deviceInstanceId() + ":" + v.checkType() + ":" + v.dueAt(), Timestamp.from(now));
                    if (inserted > 0) {
                        Map<String, String> p = support.params(c.deviceInstanceId(), c.siteCode());
                        p.put("dueDate", v.dueAt().atZone(ZoneOffset.UTC).toLocalDate().toString());
                        support.notifyOnDuty(c.siteCode(), "device.calibration-due", p, false);
                        support.notifyRole("NURSING_HEAD", "device.calibration-due", p, null);
                        notified++;
                    }
                }
            }
        }
        return notified;
    }

    // ------------------------------------------------------------------ E14 migration (E32-BL-017)

    @Transactional(readOnly = true)
    public Map<String, Object> migrationReport() {
        support.requireEnabled();
        DeviceEquipmentRegister register = support.spis().equipment();
        List<DeviceEquipmentRegister.EquipmentItem> items = register.list();
        Map<String, Configuration> linked = new LinkedHashMap<>();
        for (Configuration c : configurations.latestAll()) {
            if (c.equipmentDeviceId() != null && !Configuration.DECOMMISSIONED.equals(c.status())) {
                linked.put(c.equipmentDeviceId(), c);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int linkedCount = 0;
        int issues = 0;
        for (DeviceEquipmentRegister.EquipmentItem e : items) {
            List<String> findings = new ArrayList<>();
            Configuration c = linked.get(e.equipmentDeviceId());
            if (c != null) {
                linkedCount++;
            }
            if (e.serialNumber() == null || e.serialNumber().isBlank()) {
                findings.add("SERIAL_MISSING");
            }
            if (e.model() == null || e.model().isBlank()) {
                findings.add("MODEL_MISSING");
            }
            if (e.calibrationDueOn() == null) {
                findings.add("CALIBRATION_DUE_DATE_MISSING");
            } else if (e.calibrationDueOn().isBefore(support.today())) {
                findings.add("CALIBRATION_OVERDUE");
            }
            if (c != null && c.serial() != null && e.serialNumber() != null && !c.serial().equalsIgnoreCase(e.serialNumber())) {
                findings.add("SERIAL_MISMATCH");
            }
            String family = familyFor(e.category());
            if (family == null) {
                findings.add("NOT_A_CAPTURE_DEVICE");
            }
            issues += findings.isEmpty() ? 0 : 1;
            rows.add(Docs.of("equipmentDeviceId", e.equipmentDeviceId(), "assetTag", e.assetTag(), "category", e.category(), "model", e.model(), "serialNumber", e.serialNumber(),
                    "siteCode", e.siteCode(), "status", e.status(), "calibrationDueOn", e.calibrationDueOn(), "suggestedFamily", family,
                    "suggestedModelCode", family == null ? null : suggestModel(family, e.model()),
                    "linkedDeviceInstanceId", c == null ? null : c.deviceInstanceId(), "findings", findings));
        }
        List<String> orphanInstances = configurations.liveAll().stream()
                .filter(c -> c.equipmentDeviceId() != null && register.find(c.equipmentDeviceId()).isEmpty())
                .map(Configuration::deviceInstanceId).toList();
        return Docs.of("generatedAt", support.now(), "registerItems", items.size(), "linked", linkedCount, "unlinked", items.size() - linkedCount,
                "itemsWithFindings", issues, "instancesWithUnknownEquipment", orphanInstances, "items", rows);
    }

    public static String familyFor(String e14Category) {
        if (e14Category == null) {
            return null;
        }
        return switch (e14Category) {
            case "AUDIOMETER" -> "AUDIOMETRY";
            case "SPIROMETER" -> "SPIROMETRY";
            case "ECG" -> "CARDIOLOGY";
            case "VISION_SCREENER" -> "VISION";
            default -> null;
        };
    }

    String suggestModel(String family, String e14Model) {
        if (e14Model == null) {
            return null;
        }
        String needle = e14Model.toLowerCase(Locale.ROOT);
        return models.all().stream().filter(m -> m.family().name().equals(family))
                .filter(m -> needle.contains(m.model().toLowerCase(Locale.ROOT)) || m.model().toLowerCase(Locale.ROOT).contains(needle)
                        || needle.contains(m.manufacturer().toLowerCase(Locale.ROOT).split(" ")[0]))
                .map(ModelStore.ModelRow::modelCode).findFirst().orElse(null);
    }
}
