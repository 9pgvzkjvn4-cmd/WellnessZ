package com.wellnessz.e32.governance;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e32.calibration.CalibrationService;
import com.wellnessz.e32.catalogue.CatalogueService;
import com.wellnessz.e32.config.CertificationService;
import com.wellnessz.e32.config.Configuration;
import com.wellnessz.e32.config.ConfigurationService;
import com.wellnessz.e32.config.ConfigurationStore;
import com.wellnessz.e32.config.StationStore;
import com.wellnessz.e32.driver.DriverStore;
import com.wellnessz.e32.support.ApprovalStore;
import com.wellnessz.e32.support.CanonicalJson;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;
import com.wellnessz.e32.support.JsonDiff;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Configuration governance and environment promotion (E32-HLR-05, E32-F06, contract §13.4): the four-eyes approval queue,
 * complete change history, export (snapshots with secret references only, SHA-256 sealed) and import with a diff view,
 * approved by a second person into DRAFT versions that must be tested in the target environment; site / family templates
 * and batch provisioning for a new clinic with a report (E32-BL-010).
 */
@Service("e32GovernanceService")
public class GovernanceService {

    private final E32Support support;
    private final ApprovalStore approvals;
    private final ConfigurationStore configurations;
    private final ConfigurationService configurationService;
    private final CertificationService certifications;
    private final CatalogueService catalogue;
    private final CalibrationService calibration;
    private final DriverStore drivers;
    private final StationStore stations;

    public GovernanceService(E32Support support, ApprovalStore approvals, ConfigurationStore configurations, ConfigurationService configurationService,
                             CertificationService certifications, CatalogueService catalogue, CalibrationService calibration, DriverStore drivers,
                             StationStore stations) {
        this.support = support;
        this.approvals = approvals;
        this.configurations = configurations;
        this.configurationService = configurationService;
        this.certifications = certifications;
        this.catalogue = catalogue;
        this.calibration = calibration;
        this.drivers = drivers;
        this.stations = stations;
    }

    // ------------------------------------------------------------------ approvals queue

    @Transactional(readOnly = true)
    public List<Map<String, Object>> approvals(String status) {
        support.requireEnabled();
        return approvals.byStatus(status == null ? "PENDING" : status).stream().map(GovernanceService::approvalView).toList();
    }

    static Map<String, Object> approvalView(ApprovalStore.Approval a) {
        return Docs.of("approvalId", a.id().toString(), "kind", a.kind(), "reference", a.reference(), "version", a.refVersion(), "status", a.status(),
                "requestedBy", a.requestedBy(), "requestedAt", a.requestedAt(), "decidedBy", a.decidedBy(), "decidedAt", a.decidedAt(),
                "note", a.note(), "detail", a.detail());
    }

    /** Generic decision on a queued approval (dispatches to the owning workflow, which enforces four-eyes). */
    @Transactional
    public Map<String, Object> decide(CurrentUser user, UUID approvalId, boolean approve, String note) {
        support.requireEnabled();
        ApprovalStore.Approval a = approvals.find(approvalId).orElseThrow(() -> ApiException.notFound("Approval"));
        if (!"PENDING".equals(a.status())) {
            throw ApiException.conflict("invalid_status", "The approval is no longer pending");
        }
        return switch (a.kind()) {
            case "CONFIGURATION" -> approve ? configurationService.approve(user, a.reference(), note)
                    : configurationService.reject(user, a.reference(), note == null ? "rejected" : note);
            case "DECOMMISSION" -> configurationService.decideDecommission(user, a.reference(), approve, note);
            case "IMPORT" -> approve ? approveImport(user, UUID.fromString(a.reference())) : rejectImport(user, UUID.fromString(a.reference()));
            case "POSTURE_EXCEPTION" -> {
                if (!user.hasRole("SECURITY_OPS")) {
                    throw ApiException.forbidden("Security operations approve posture exceptions");
                }
                String[] parts = a.reference().split("/", 2);
                yield catalogue.decideException(user, parts[0], UUID.fromString(parts[1]), approve, note);
            }
            case "SCHEDULE_CHANGE" -> decideSchedule(user, a, approve, note);
            default -> throw ApiException.conflict("invalid_status", "This approval is decided in its own workflow");
        };
    }

    private Map<String, Object> decideSchedule(CurrentUser user, ApprovalStore.Approval a, boolean approve, String note) {
        if (user.username().equals(a.requestedBy())) {
            throw ApiException.conflict("four_eyes_violation", "The requester cannot approve the schedule change");
        }
        if (!user.hasRole("DEVICE_APPROVER")) {
            throw ApiException.forbidden("Only a device approver can approve schedule changes");
        }
        approvals.decide(a.id(), approve ? "APPROVED" : "REJECTED", user.username(), note);
        if (approve) {
            calibration.applySchedule(user.username(), a.detail(), a.id().toString());
        }
        return approvalView(approvals.find(a.id()).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> changes(Instant from, Instant to, String configurationId) {
        support.requireEnabled();
        Instant a = from == null ? Instant.EPOCH : from;
        Instant b = to == null ? support.now().plusSeconds(1) : to;
        return support.jdbc().query("""
                select entity_type, entity_id, version, actor, action, step, diff, approval_ref, at from devicecat.change_history
                where at >= ? and at < ? order by at desc limit 2000""",
                (rs, i) -> Docs.of("entityType", rs.getString("entity_type"), "entityId", rs.getString("entity_id"), "version", rs.getObject("version"),
                        "actor", rs.getString("actor"), "action", rs.getString("action"), "step", rs.getObject("step"), "diff", parse(rs.getString("diff")),
                        "approvalRef", rs.getString("approval_ref"), "at", rs.getTimestamp("at").toInstant()),
                Timestamp.from(a), Timestamp.from(b))
                .stream().filter(r -> configurationId == null || "CONFIGURATION".equals(r.get("entityType")) && configurationId.equals(r.get("entityId")))
                .toList();
    }

    private Object parse(String json) {
        if (json == null) {
            return null;
        }
        return json.startsWith("[") ? support.readList(json) : support.readMap(json);
    }

    // ------------------------------------------------------------------ export / import (environment promotion)

    @Transactional
    public Map<String, Object> export(CurrentUser user, List<String> configurationIds) {
        support.requireEnabled();
        if (configurationIds.isEmpty() || configurationIds.size() > 200) {
            throw ApiException.badRequest("validation_failed", "export 1–200 configurations");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (String id : configurationIds) {
            Configuration c = configurations.requireLatest(id);
            Map<String, Object> snapshot = certifications.snapshot(c);
            DriverStore.DriverRow d = c.driverPackageId() == null ? null : drivers.find(c.driverPackageId(), c.driverVersion()).orElse(null);
            items.add(Docs.of("configurationId", c.configurationId(), "version", c.version(), "status", c.status(), "snapshot", snapshot,
                    "driver", d == null ? null : Docs.of("packageId", d.packageId(), "version", d.version(), "sha256", d.sha256())));
        }
        UUID exportId = UUID.randomUUID();
        Instant now = support.now();
        String sha = Docs.sha256(CanonicalJson.write(items));
        Map<String, Object> payload = Docs.of("exportId", exportId.toString(), "sourceEnvironment", support.environment(), "createdAt", now.toString(),
                "items", items, "sha256", sha);
        support.jdbc().update("""
                insert into devicecat.governance_export (export_id, source_environment, payload, sha256, created_by, created_at) values (?, ?, ?, ?, ?, ?)""",
                exportId, support.environment(), support.write(payload), sha, user.username(), Timestamp.from(now));
        support.audit("DEVICE_CONFIG_EXPORT", "DEVICE_CONFIGURATION", exportId.toString(), items.size() + " configurations");
        return payload;
    }

    @Transactional
    public Map<String, Object> importExport(CurrentUser user, Map<String, Object> export) {
        support.requireEnabled();
        List<Object> items = Docs.list(export.get("items"));
        if (items.isEmpty()) {
            throw ApiException.badRequest("validation_failed", "the export contains no items");
        }
        if (!Docs.sha256(CanonicalJson.write(items)).equals(Docs.str(export.get("sha256")))) {
            throw E32Support.unprocessable("export_tampered", "The export's SHA-256 does not match its content");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : items) {
            Map<String, Object> item = Docs.map(o);
            Map<String, Object> snapshot = Docs.map(item.get("snapshot"));
            String id = Docs.str(item.get("configurationId"));
            Map<String, Object> drv = Docs.map(item.get("driver"));
            List<String> problems = new ArrayList<>();
            if (drv.isEmpty() || drivers.find(Docs.str(drv.get("packageId")), Docs.str(drv.get("version"))).isEmpty()) {
                problems.add("driver_missing");
            } else if (!Docs.str(drv.get("sha256")).equals(drivers.find(Docs.str(drv.get("packageId")), Docs.str(drv.get("version"))).orElseThrow().sha256())) {
                problems.add("driver_differs");
            }
            if (stations.find(Docs.str(snapshot.get("stationCode"))).isEmpty()) {
                problems.add("station_missing");
            }
            Optional<Configuration> target = id == null ? Optional.empty() : configurations.latest(id)
                    .filter(c -> c.modelCode().equals(Docs.str(snapshot.get("modelCode"))));
            Map<String, Object> current = target.map(certifications::snapshot).orElse(Map.of());
            List<Map<String, Object>> diff = JsonDiff.diff(comparable(current), comparable(snapshot));
            out.add(Docs.of("configurationId", id, "targetConfigurationId", target.map(Configuration::configurationId).orElse(null),
                    "action", target.isPresent() ? "UPDATE" : "CREATE", "diff", diff,
                    "secretsToRebind", Docs.map(snapshot.get("secretReferences")).keySet().stream().toList(), "problems", problems));
        }
        UUID importId = UUID.randomUUID();
        support.jdbc().update("""
                insert into devicecat.governance_import (import_id, source_environment, export_id, payload, items, status, imported_by, imported_at)
                values (?, ?, ?, ?, ?, 'PENDING', ?, ?)""", importId, trim(Docs.str(export.get("sourceEnvironment")), 20),
                trim(Docs.str(export.get("exportId")), 60), support.write(export), support.write(out), user.username(), Timestamp.from(support.now()));
        approvals.open("IMPORT", importId.toString(), null, user.username(), Docs.of("items", out.size(), "sourceEnvironment", export.get("sourceEnvironment")));
        support.notifyRole("DEVICE_APPROVER", "device.approval-required", support.params("IMPORT-" + importId.toString().substring(0, 8), null),
                user.username());
        support.audit("DEVICE_CONFIG_IMPORT", "DEVICE_CONFIGURATION", importId.toString(), "staged " + out.size());
        return Docs.of("importId", importId.toString(), "status", "PENDING", "targetEnvironment", support.environment(), "items", out);
    }

    private static Object comparable(Map<String, Object> snapshot) {
        Map<String, Object> m = Docs.copyMap(snapshot);
        m.remove("configurationId");
        m.remove("version");
        m.remove("deviceInstanceId");
        m.remove("secretReferences");
        return m;
    }

    /** DEVICE_APPROVER (≠ importer): creates DRAFT versions at step 6 – no production change without an approval record. */
    @Transactional
    public Map<String, Object> approveImport(CurrentUser user, UUID importId) {
        support.requireEnabled();
        Map<String, Object> imp = importRow(importId);
        if (!"PENDING".equals(imp.get("status"))) {
            throw ApiException.conflict("invalid_status", "The import is not pending");
        }
        if (user.username().equals(imp.get("importedBy"))) {
            support.auditDenied("DEVICE_CONFIG_IMPORT", "DEVICE_CONFIGURATION", importId.toString(), "four_eyes_violation");
            throw ApiException.conflict("four_eyes_violation", "The importer cannot approve the import");
        }
        if (!user.hasRole("DEVICE_APPROVER")) {
            throw ApiException.forbidden("Only a device approver approves imports");
        }
        List<Object> items = Docs.list(imp.get("items"));
        for (Object o : items) {
            List<String> problems = Docs.strings(Docs.map(o).get("problems"));
            if (!problems.isEmpty()) {
                throw E32Support.unprocessable("import_not_applicable", "Resolve the item problems first: " + problems);
            }
        }
        Map<String, Object> payload = support.readMap(Docs.str(imp.get("payload")));
        List<Map<String, Object>> created = new ArrayList<>();
        List<Object> exportItems = Docs.list(payload.get("items"));
        for (int i = 0; i < exportItems.size(); i++) {
            Map<String, Object> item = Docs.map(exportItems.get(i));
            Map<String, Object> plan = Docs.map(items.get(i));
            Configuration c = configurationService.importDraft(user.username(), Docs.str(plan.get("targetConfigurationId")), Docs.map(item.get("snapshot")),
                    "IMPORT:" + importId);
            created.add(Docs.of("configurationId", c.configurationId(), "version", c.version(), "deviceInstanceId", c.deviceInstanceId(),
                    "currentStep", c.currentStep(), "action", plan.get("action")));
        }
        support.jdbc().update("""
                update devicecat.governance_import set status = 'APPROVED', decided_by = ?, decided_at = ?, created_configurations = ? where import_id = ?""",
                user.username(), Timestamp.from(support.now()), support.write(created), importId);
        approvals.pending("IMPORT", importId.toString()).ifPresent(a -> approvals.decide(a.id(), "APPROVED", user.username(), null));
        support.history("IMPORT", importId.toString(), null, user.username(), "APPROVE", null, created, importId.toString());
        support.audit("DEVICE_CONFIG_IMPORT", "DEVICE_CONFIGURATION", importId.toString(), "approved " + created.size());
        Map<String, Object> out = importView(importId);
        out.put("createdConfigurations", created);
        return out;
    }

    @Transactional
    public Map<String, Object> rejectImport(CurrentUser user, UUID importId) {
        Map<String, Object> imp = importRow(importId);
        if (!"PENDING".equals(imp.get("status"))) {
            throw ApiException.conflict("invalid_status", "The import is not pending");
        }
        if (!user.hasRole("DEVICE_APPROVER")) {
            throw ApiException.forbidden("Only a device approver decides on imports");
        }
        support.jdbc().update("update devicecat.governance_import set status = 'REJECTED', decided_by = ?, decided_at = ? where import_id = ?",
                user.username(), Timestamp.from(support.now()), importId);
        approvals.pending("IMPORT", importId.toString()).ifPresent(a -> approvals.decide(a.id(), "REJECTED", user.username(), null));
        return importView(importId);
    }

    private Map<String, Object> importRow(UUID importId) {
        List<Map<String, Object>> rows = support.jdbc().queryForList("select * from devicecat.governance_import where import_id = ?", importId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Import");
        }
        Map<String, Object> r = rows.get(0);
        return Docs.of("importId", importId.toString(), "status", r.get("status"), "importedBy", r.get("imported_by"), "payload", r.get("payload"),
                "items", support.readList(Docs.str(r.get("items"))), "sourceEnvironment", r.get("source_environment"),
                "createdConfigurations", r.get("created_configurations") == null ? null : support.readList(Docs.str(r.get("created_configurations"))),
                "decidedBy", r.get("decided_by"));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> importView(UUID importId) {
        Map<String, Object> r = importRow(importId);
        r.remove("payload");
        return r;
    }

    // ------------------------------------------------------------------ templates and batch provisioning (E32-BL-010)

    public record TemplateCommand(String name, String scope, List<Map<String, Object>> items) {
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> templates() {
        support.requireEnabled();
        return support.jdbc().queryForList("select id from devicecat.template order by name", UUID.class).stream().map(this::template).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> template(UUID id) {
        List<Map<String, Object>> rows = support.jdbc().queryForList("select * from devicecat.template where id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Template");
        }
        Map<String, Object> r = rows.get(0);
        return Docs.of("templateId", id.toString(), "name", r.get("name"), "scope", r.get("scope"), "items", support.readList(Docs.str(r.get("items"))),
                "updatedBy", r.get("updated_by"), "updatedAt", String.valueOf(r.get("updated_at")));
    }

    @Transactional
    public Map<String, Object> saveTemplate(CurrentUser user, UUID id, TemplateCommand cmd) {
        support.requireEnabled();
        if (cmd.name() == null || cmd.name().isBlank() || !List.of("SITE", "FAMILY").contains(cmd.scope()) || cmd.items() == null || cmd.items().isEmpty()) {
            throw ApiException.badRequest("validation_failed", "name, scope SITE|FAMILY and at least one item are required");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> i : cmd.items()) {
            String model = Docs.str(i.get("modelCode"));
            String option = Docs.str(i.get("option"));
            if (model == null || option == null) {
                throw ApiException.badRequest("validation_failed", "each item needs modelCode and option");
            }
            catalogue.get(model);
            items.add(Docs.of("modelCode", model, "option", option, "parameters", Docs.copy(i.getOrDefault("parameters", Map.of())),
                    "bindings", Docs.copy(i.getOrDefault("bindings", List.of())), "stationPattern", Docs.str(i.getOrDefault("stationPattern", "*"))));
        }
        Timestamp now = Timestamp.from(support.now());
        UUID tid = id == null ? UUID.randomUUID() : id;
        if (id == null) {
            support.jdbc().update("""
                    insert into devicecat.template (id, name, scope, items, created_by, created_at, updated_by, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)""",
                    tid, trim(cmd.name(), 200), cmd.scope(), support.write(items), user.username(), now, user.username(), now);
        } else if (support.jdbc().update("update devicecat.template set name = ?, scope = ?, items = ?, updated_by = ?, updated_at = ? where id = ?",
                trim(cmd.name(), 200), cmd.scope(), support.write(items), user.username(), now, id) == 0) {
            throw ApiException.notFound("Template");
        }
        support.history("TEMPLATE", tid.toString(), null, user.username(), id == null ? "CREATE" : "UPDATE", null, Docs.of("items", items.size()), null);
        return template(tid);
    }

    public record BatchCommand(String siteCode, List<String> stations) {
    }

    @Transactional
    public Map<String, Object> batch(CurrentUser user, UUID templateId, BatchCommand cmd) {
        support.requireEnabled();
        Map<String, Object> t = template(templateId);
        if (cmd.siteCode() == null || cmd.stations() == null || cmd.stations().isEmpty()) {
            throw ApiException.badRequest("validation_failed", "siteCode and stations are required");
        }
        List<Map<String, Object>> report = new ArrayList<>();
        for (Object o : Docs.list(t.get("items"))) {
            Map<String, Object> item = Docs.map(o);
            Pattern p = glob(Docs.str(item.get("stationPattern")));
            for (String station : cmd.stations()) {
                if (!p.matcher(station).matches()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>(Docs.of("modelCode", item.get("modelCode"), "option", item.get("option"), "stationCode", station));
                Optional<StationStore.Station> st = stations.find(station);
                if (st.isEmpty() || !cmd.siteCode().equals(st.get().siteCode())) {
                    row.put("status", "FAILED");
                    row.put("error", "unknown_station");
                } else {
                    try {
                        Configuration c = configurationService.createFromTemplate(user, templateId, Docs.str(item.get("modelCode")), Docs.str(item.get("option")),
                                station, Docs.map(item.get("parameters")), Docs.list(item.get("bindings")));
                        row.put("status", "CREATED");
                        row.put("configurationId", c.configurationId());
                        row.put("deviceInstanceId", c.deviceInstanceId());
                    } catch (ApiException e) {
                        row.put("status", "FAILED");
                        row.put("error", e.code());
                    }
                }
                report.add(row);
            }
        }
        UUID batchId = UUID.randomUUID();
        Map<String, Object> out = Docs.of("batchId", batchId.toString(), "templateId", templateId.toString(), "siteCode", cmd.siteCode(),
                "created", report.stream().filter(r -> "CREATED".equals(r.get("status"))).count(),
                "failed", report.stream().filter(r -> "FAILED".equals(r.get("status"))).count(), "items", report);
        support.jdbc().update("insert into devicecat.batch (id, template_id, site_code, report, created_by, created_at) values (?, ?, ?, ?, ?, ?)",
                batchId, templateId, cmd.siteCode(), support.write(out), user.username(), Timestamp.from(support.now()));
        support.audit("DEVICE_CONFIG_CREATE", "DEVICE_TEMPLATE", templateId.toString(), "batch " + report.size());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> batchReport(UUID batchId) {
        List<String> rows = support.jdbc().queryForList("select report from devicecat.batch where id = ?", String.class, batchId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Batch");
        }
        return support.readMap(rows.get(0));
    }

    /**
     * E14 equipment-register migration run (E32-BL-017): every capture-relevant, unlinked register item with a confidently
     * suggested catalogue model becomes a DRAFT configuration linked to its equipment record (serial and equipment id in step 1).
     */
    @Transactional
    public Map<String, Object> migrateRegister(CurrentUser user) {
        support.requireEnabled();
        Map<String, Object> report = calibration.migrationReport();
        List<Map<String, Object>> created = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Object o : Docs.list(report.get("items"))) {
            Map<String, Object> item = Docs.map(o);
            String model = Docs.str(item.get("suggestedModelCode"));
            if (item.get("linkedDeviceInstanceId") != null || model == null) {
                skipped.add(Docs.of("equipmentDeviceId", item.get("equipmentDeviceId"),
                        "reason", item.get("linkedDeviceInstanceId") != null ? "already_linked" : "no_model_match"));
                continue;
            }
            Map<String, Object> view = configurationService.create(user, new ConfigurationService.CreateCommand(model, null, null));
            String id = Docs.str(view.get("configurationId"));
            configurationService.putStep(user, id, 1, Docs.of("modelCode", model, "equipmentDeviceId", item.get("equipmentDeviceId"),
                    "serial", item.get("serialNumber")));
            created.add(Docs.of("equipmentDeviceId", item.get("equipmentDeviceId"), "configurationId", id, "deviceInstanceId", view.get("deviceInstanceId"),
                    "modelCode", model));
        }
        support.audit("DEVICE_CONFIG_CREATE", "DEVICE_EQUIPMENT_MIGRATION", "E14", created.size() + " drafts");
        return Docs.of("created", created, "skipped", skipped, "report", report);
    }

    static Pattern glob(String pattern) {
        String p = pattern == null || pattern.isBlank() ? "*" : pattern;
        StringBuilder re = new StringBuilder();
        for (char ch : p.toCharArray()) {
            switch (ch) {
                case '*' -> re.append(".*");
                case '?' -> re.append('.');
                default -> re.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(re.toString());
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
