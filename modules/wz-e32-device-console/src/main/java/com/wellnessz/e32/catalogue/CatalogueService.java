package com.wellnessz.e32.catalogue;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCodes.Family;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e32.config.Configuration;
import com.wellnessz.e32.config.ConfigurationStore;
import com.wellnessz.e32.driver.DriverStore;
import com.wellnessz.e32.support.ApprovalStore;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;
import com.wellnessz.e32.support.JsonDiff;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Device catalogue and regulatory register (E32-F01, E32-HLR-01, contract §5 / §13.1): families, model search and detail,
 * console-created models, edits (flagged {@code locallyModified}), lifecycle with its gates, UDI / GUDID / EUDAMED import,
 * side-by-side comparison, and vendor security onboarding (E32-F07, §13.8): posture intake with policy evaluation and
 * compensating-control exceptions approved by Security Operations.
 */
@Service("e32CatalogueService")
public class CatalogueService {

    private static final Set<String> EDITABLE = Set.of("manufacturer", "model", "ohUse", "connectivity", "protocol", "fdaCodes", "options",
            "variants", "guidelineDrivers", "units", "formsFed", "vendorSoftware", "regulatory", "validationListed", "optionNotes");
    private static final Set<String> COLUMN_FIELDS = Set.of("manufacturer", "model", "ohUse", "connectivity", "protocol", "fdaCodes", "options");

    private final ModelStore models;
    private final DriverStore drivers;
    private final ConfigurationStore configurations;
    private final ApprovalStore approvals;
    private final E32Support support;

    public CatalogueService(ModelStore models, DriverStore drivers, ConfigurationStore configurations, ApprovalStore approvals, E32Support support) {
        this.models = models;
        this.drivers = drivers;
        this.configurations = configurations;
        this.approvals = approvals;
        this.support = support;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<Map<String, Object>> families() {
        support.requireEnabled();
        Map<Family, int[]> counts = new LinkedHashMap<>();
        for (Family f : Family.values()) {
            counts.put(f, new int[2]);
        }
        for (ModelStore.ModelRow m : models.all()) {
            int[] c = counts.get(m.family());
            c[0]++;
            if ("CERTIFIED".equals(m.lifecycle())) {
                c[1]++;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        counts.forEach((f, c) -> out.add(Docs.of("family", f.name(), "label", InventoryMapping.label(f), "models", c[0], "certifiedModels", c[1])));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(String family, String q, String option, String lifecycle, String posture) {
        return search(family, q, option, lifecycle, posture, null);
    }

    /**
     * Catalogue search; with {@code tenantCode} only the models visible to that tenant (E31-HLR-12: the shared group catalogue unless
     * the tenant's scope is {@code TENANT}, plus the tenant's own models – never another tenant's).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(String family, String q, String option, String lifecycle, String posture, String tenantCode) {
        support.requireEnabled();
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        List<DriverStore.DriverRow> all = drivers.all();
        Map<String, String> owners = models.tenantScoped();
        // without a tenant the group-level view lists every model (device administrators of the group operate all tenants)
        return models.all().stream()
                .filter(m -> tenantCode == null || support.visible(tenantCode, owners.get(m.modelCode())))
                .filter(m -> family == null || m.family().name().equals(family))
                .filter(m -> option == null || m.supports(option))
                .filter(m -> lifecycle == null || lifecycle.equals(m.lifecycle()))
                .filter(m -> posture == null || posture.equals(m.posture()))
                .filter(m -> needle == null || (m.manufacturer() + " " + m.model() + " " + m.modelCode()).toLowerCase(Locale.ROOT).contains(needle))
                .map(m -> Docs.of("modelCode", m.modelCode(), "family", m.family().name(), "manufacturer", m.manufacturer(), "model", m.model(),
                        "options", m.options(), "lifecycle", m.lifecycle(), "posture", m.posture(),
                        "certifiedDrivers", all.stream().filter(d -> "CERTIFIED".equals(d.status()) && d.models().contains(m.modelCode())).count(),
                        "confidence", m.confidence(), "sprint", m.sprint()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String modelCode) {
        support.requireEnabled();
        return record(models.require(modelCode));
    }

    /** Model record of contract §5. */
    public Map<String, Object> record(ModelStore.ModelRow m) {
        Map<String, Object> d = m.detail();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("modelCode", m.modelCode());
        r.put("family", m.family().name());
        r.put("familyLabel", m.familyLabel());
        r.put("manufacturer", m.manufacturer());
        r.put("model", m.model());
        r.put("variants", d.getOrDefault("variants", List.of()));
        r.put("ohUse", m.ohUse());
        r.put("guidelineDrivers", d.get("guidelineDrivers"));
        r.put("units", d.getOrDefault("units", List.of()));
        r.put("formsFed", d.getOrDefault("formsFed", List.of()));
        r.put("options", d.getOrDefault("optionNotes", InventoryMapping.optionNotes(m.connectivity(), m.options())));
        r.put("connectivity", m.connectivity());
        r.put("protocol", m.protocol());
        r.put("fdaCodes", InventoryMapping.fdaCodes(m.fdaCodes()));
        r.put("vendorSoftware", d.getOrDefault("vendorSoftware", List.of()));
        r.put("udi", d.get("udi"));
        r.put("regulatory", d.getOrDefault("regulatory", List.of()));
        Map<String, Object> security = Docs.copyMap(Docs.map(d.get("security")));
        security.put("posture", m.posture());
        r.put("security", security);
        r.put("validationListed", d.get("validationListed"));
        List<Map<String, Object>> drv = new ArrayList<>();
        for (DriverStore.DriverRow x : drivers.forModel(m.modelCode())) {
            drv.add(Docs.of("packageId", x.packageId(), "version", x.version(), "status", x.status(), "options", x.options(), "certifiedAt", x.certifiedAt()));
        }
        r.put("drivers", drv);
        r.put("inventoryOptions", m.options());
        List<Map<String, Object>> routes = CatalogueRoutes.view(m.modelCode());
        if (!routes.isEmpty()) {
            r.put("catalogueRoutes", routes);
        }
        r.put("tenantCode", models.tenantOf(m.modelCode()));
        r.put("lifecycle", m.lifecycle());
        r.put("sprint", m.sprint());
        r.put("confidence", m.confidence());
        r.put("inventoryId", m.inventoryId());
        r.put("locallyModified", m.locallyModified());
        r.put("modifiedFields", m.modifiedFields());
        r.values().removeIf(java.util.Objects::isNull);
        return r;
    }

    /** Scopes a model to one tenant ({@code null} = back to the shared group catalogue). */
    @Transactional
    public Map<String, Object> setTenant(CurrentUser user, String modelCode, String tenantCode) {
        support.requireEnabled();
        models.require(modelCode);
        String tenant = tenantCode == null || tenantCode.isBlank() ? null : support.tenant(tenantCode).code();
        if (com.wellnessz.casekernel.device.DeviceTenantDirectory.GROUP.equals(tenant)) {
            tenant = null;
        }
        models.setTenant(modelCode, tenant, user.username());
        support.audit("DEVICE_MODEL_UPDATE", "DEVICE_MODEL", modelCode);
        return record(models.require(modelCode));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> compare(List<String> codes) {
        support.requireEnabled();
        if (codes.isEmpty() || codes.size() > 6) {
            throw ApiException.badRequest("validation_failed", "compare 1–6 models");
        }
        return codes.stream().map(models::require).map(this::record).toList();
    }

    // ------------------------------------------------------------------ create / edit

    public record ModelCommand(String manufacturer, String model, String family, List<String> options, String ohUse, String connectivity, String protocol,
                               String fdaCodes, List<String> variants, String guidelineDrivers, List<String> units, List<String> formsFed,
                               List<Map<String, Object>> vendorSoftware, List<Map<String, Object>> regulatory, Boolean validationListed,
                               String confidence) {
    }

    @Transactional
    public Map<String, Object> create(CurrentUser user, ModelCommand cmd) {
        support.requireEnabled();
        if (cmd.manufacturer() == null || cmd.model() == null || cmd.family() == null) {
            throw ApiException.badRequest("validation_failed", "manufacturer, model and family are required");
        }
        Family f;
        try {
            f = Family.valueOf(cmd.family());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("validation_failed", "unknown family");
        }
        List<String> options = InventoryMapping.options(cmd.options());
        if (options.isEmpty()) {
            throw ApiException.badRequest("validation_failed", "at least one connectivity option is required");
        }
        String code = support.nextLocalModelCode();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("variants", cmd.variants() == null ? InventoryMapping.variants(cmd.model()) : cmd.variants());
        detail.put("guidelineDrivers", cmd.guidelineDrivers() == null ? InventoryMapping.guidelineDrivers(f) : cmd.guidelineDrivers());
        detail.put("units", cmd.units() == null ? InventoryMapping.units(f) : cmd.units());
        detail.put("formsFed", cmd.formsFed() == null ? InventoryMapping.formsFed(f) : cmd.formsFed());
        detail.put("optionNotes", InventoryMapping.optionNotes(cmd.connectivity(), options));
        detail.put("vendorSoftware", cmd.vendorSoftware() == null ? List.of() : cmd.vendorSoftware());
        detail.put("regulatory", cmd.regulatory() == null ? InventoryMapping.regulatory(cmd.fdaCodes()) : validRegulatory(cmd.regulatory()));
        detail.put("security", InventoryMapping.initialSecurity());
        if (cmd.validationListed() != null) {
            detail.put("validationListed", cmd.validationListed());
        }
        Timestamp now = Timestamp.from(support.now());
        support.jdbc().update("""
                insert into devicecat.model (model_code, inventory_id, family, family_label, manufacturer, model, oh_use, connectivity, protocol, fda_codes,
                    sprint, confidence, options, detail, lifecycle, posture, locally_modified, modified_fields, created_by, created_at, updated_by, updated_at)
                values (?, null, ?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?, ?, 'CANDIDATE', 'NOT_ASSESSED', true, '', ?, ?, ?, ?)""",
                code, f.name(), InventoryMapping.label(f), trim(cmd.manufacturer(), 200), trim(cmd.model(), 300), trim(cmd.ohUse(), 1000),
                trim(cmd.connectivity(), 1000), trim(cmd.protocol(), 1000), trim(cmd.fdaCodes(), 300),
                cmd.confidence() == null ? "Medium – verify" : trim(cmd.confidence(), 60), Docs.joinCsv(options), support.write(detail),
                user.username(), now, user.username(), now);
        support.history("MODEL", code, null, user.username(), "CREATE", null, Docs.of("manufacturer", cmd.manufacturer(), "model", cmd.model()), null);
        support.audit("DEVICE_MODEL_CREATE", "DEVICE_MODEL", code);
        return record(models.require(code));
    }

    /** Edits regulatory, UDI, vendor-software and descriptive fields; edited fields are never overwritten by the seed. */
    @Transactional
    public Map<String, Object> update(CurrentUser user, String modelCode, Map<String, Object> changes) {
        support.requireEnabled();
        ModelStore.ModelRow m = models.require(modelCode);
        for (String k : changes.keySet()) {
            if (!EDITABLE.contains(k)) {
                throw ApiException.badRequest("validation_failed", "field '" + k + "' cannot be edited here");
            }
        }
        Map<String, Object> before = record(m);
        Set<String> touched = new LinkedHashSet<>(changes.keySet());
        Map<String, Object> detail = Docs.copyMap(m.detail());
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            if (!COLUMN_FIELDS.contains(e.getKey())) {
                Object v = "regulatory".equals(e.getKey()) ? validRegulatory(Docs.list(e.getValue()).stream().map(Docs::map).toList()) : e.getValue();
                detail.put(e.getKey(), Docs.copy(v));
            }
        }
        List<String> options = changes.containsKey("options") ? InventoryMapping.options(Docs.strings(changes.get("options"))) : m.options();
        if (options.isEmpty()) {
            throw ApiException.badRequest("validation_failed", "at least one connectivity option is required");
        }
        if (changes.containsKey("options")) {
            detail.put("optionNotes", InventoryMapping.optionNotes(m.connectivity(), options));
        }
        models.updateColumns(m, str(changes, "manufacturer", m.manufacturer(), 200), str(changes, "model", m.model(), 300),
                str(changes, "ohUse", m.ohUse(), 1000), str(changes, "connectivity", m.connectivity(), 1000), str(changes, "protocol", m.protocol(), 1000),
                str(changes, "fdaCodes", m.fdaCodes(), 300), options, touched, user.username());
        ModelStore.ModelRow updated = models.require(modelCode);
        models.updateDetail(updated, detail, touched, user.username());
        Map<String, Object> after = record(models.require(modelCode));
        support.history("MODEL", modelCode, null, user.username(), "UPDATE", null, JsonDiff.diff(before, after), null);
        support.audit("DEVICE_MODEL_UPDATE", "DEVICE_MODEL", modelCode);
        return after;
    }

    private static String str(Map<String, Object> changes, String key, String fallback, int max) {
        if (!changes.containsKey(key)) {
            return fallback;
        }
        return trim(Docs.str(changes.get(key)), max);
    }

    private static List<Map<String, Object>> validRegulatory(List<Map<String, Object>> entries) {
        Set<String> statuses = Set.of("CLEARED", "CE_MARKED", "REGISTERED", "NOT_REQUIRED", "UNKNOWN");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            String market = Docs.str(e.get("market"));
            String status = Docs.str(e.get("status"));
            if (market == null || !InventoryMapping.MARKETS.contains(market) || status == null || !statuses.contains(status)) {
                throw ApiException.badRequest("validation_failed", "regulatory entries need a known market and status");
            }
            out.add(Docs.of("market", market, "regime", trim(Docs.str(e.get("regime")), 200), "status", status,
                    "productCodes", Docs.strings(e.get("productCodes")), "reference", trim(Docs.str(e.get("reference")), 100),
                    "verifiedOn", Docs.str(e.get("verifiedOn")), "source", e.get("source") == null ? "MANUAL" : trim(Docs.str(e.get("source")), 20)));
        }
        return out;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * CANDIDATE → CERTIFIED (DEVICE_APPROVER; needs a certified driver and an acceptable posture or approved exception) →
     * DEPRECATED → RETIRED (DEVICE_ADMIN; RETIRED needs no published instance).
     */
    @Transactional
    public Map<String, Object> lifecycle(CurrentUser user, String modelCode, String to, String reason) {
        support.requireEnabled();
        ModelStore.ModelRow m = models.require(modelCode);
        String from = m.lifecycle();
        boolean allowed = switch (to == null ? "" : to) {
            case "CERTIFIED" -> "CANDIDATE".equals(from) || "DEPRECATED".equals(from);
            case "DEPRECATED" -> "CERTIFIED".equals(from) || "CANDIDATE".equals(from);
            case "RETIRED" -> "DEPRECATED".equals(from) || "CANDIDATE".equals(from);
            case "CANDIDATE" -> false;
            default -> throw ApiException.badRequest("validation_failed", "to must be CERTIFIED, DEPRECATED or RETIRED");
        };
        if (!allowed) {
            throw E32Support.unprocessable("invalid_transition", "Lifecycle cannot move from " + from + " to " + to);
        }
        if ("CERTIFIED".equals(to)) {
            if (!user.hasRole("DEVICE_APPROVER")) {
                throw ApiException.forbidden("Only a device approver certifies models");
            }
            if (drivers.forModel(modelCode).stream().noneMatch(d -> "CERTIFIED".equals(d.status()))) {
                throw E32Support.unprocessable("no_certified_driver", "A certified driver package is required");
            }
            if (!PosturePolicy.usable(m.posture())) {
                throw E32Support.unprocessable("posture_blocked", "The security posture is not acceptable and no exception is approved");
            }
        } else if (!user.hasRole("DEVICE_ADMIN")) {
            throw ApiException.forbidden("Only a device administrator deprecates or retires models");
        }
        if ("RETIRED".equals(to)) {
            boolean published = configurations.liveAll().stream().anyMatch(c -> modelCode.equals(c.modelCode())
                    && Configuration.PUBLISHED.equals(c.status()));
            if (published) {
                throw ApiException.conflict("published_instances", "The model still has published device instances");
            }
        }
        models.setLifecycle(modelCode, to, user.username());
        support.history("MODEL", modelCode, null, user.username(), "LIFECYCLE", null, Docs.of("from", from, "to", to, "reason", trim(reason, 300)), null);
        support.audit("DEVICE_MODEL_LIFECYCLE", "DEVICE_MODEL", modelCode, from + "->" + to);
        return record(models.require(modelCode));
    }

    // ------------------------------------------------------------------ UDI / regulatory import (E32-BL-002)

    public record UdiCommand(String source, String deviceIdentifier, Map<String, Object> record, LocalDate retrievedOn) {
    }

    /** Records a downloaded GUDID / EUDAMED record (no outbound call in this increment) with its source and date. */
    @Transactional
    public Map<String, Object> importUdi(CurrentUser user, String modelCode, UdiCommand cmd) {
        support.requireEnabled();
        ModelStore.ModelRow m = models.require(modelCode);
        if (!Set.of("GUDID", "EUDAMED", "MANUAL").contains(cmd.source())) {
            throw ApiException.badRequest("validation_failed", "source must be GUDID, EUDAMED or MANUAL");
        }
        if (cmd.deviceIdentifier() == null || !cmd.deviceIdentifier().matches("[A-Za-z0-9+./\\-]{4,60}")) {
            throw ApiException.badRequest("validation_failed", "deviceIdentifier is required (4–60 characters)");
        }
        Map<String, Object> rec = cmd.record() == null ? Map.of() : cmd.record();
        LocalDate retrieved = cmd.retrievedOn() == null ? support.today() : cmd.retrievedOn();
        String agency = Docs.str(rec.get("issuingAgency"));
        if (agency == null) {
            agency = cmd.deviceIdentifier().matches("\\d{8,14}") ? "GS1" : cmd.deviceIdentifier().startsWith("+") ? "HIBCC" : "ICCBBA";
        }
        Map<String, Object> detail = Docs.copyMap(m.detail());
        Map<String, Object> udi = Docs.of("deviceIdentifier", cmd.deviceIdentifier(), "issuingAgency", agency,
                "gudidRef", "GUDID".equals(cmd.source()) ? cmd.deviceIdentifier() : Docs.str(Docs.map(detail.get("udi")).get("gudidRef")),
                "eudamedRef", "EUDAMED".equals(cmd.source()) ? Docs.str(rec.getOrDefault("udiDiId", cmd.deviceIdentifier()))
                        : Docs.str(Docs.map(detail.get("udi")).get("eudamedRef")),
                "source", cmd.source(), "retrievedOn", retrieved.toString(), "recordSha256", Docs.sha256(support.write(rec)));
        detail.put("udi", udi);
        List<Map<String, Object>> regulatory = new ArrayList<>(Docs.list(detail.get("regulatory")).stream().map(Docs::map).toList());
        if ("GUDID".equals(cmd.source())) {
            List<String> codes = Docs.strings(rec.get("productCodes"));
            String submission = Docs.str(rec.get("premarketSubmission"));
            upsertMarket(regulatory, Docs.of("market", "US", "regime", "FDA 21 CFR 862–892 (510(k) / De Novo / PMA)",
                    "status", submission != null || !codes.isEmpty() ? "CLEARED" : "UNKNOWN", "productCodes", codes.isEmpty() ? InventoryMapping.fdaCodes(m.fdaCodes()) : codes,
                    "reference", submission, "verifiedOn", retrieved.toString(), "source", "GUDID"));
        } else if ("EUDAMED".equals(cmd.source())) {
            boolean ce = rec.get("certificates") != null || rec.get("riskClass") != null;
            upsertMarket(regulatory, Docs.of("market", "EU", "regime", "EU MDR 2017/745 / IVDR 2017/746 (CE, EUDAMED)", "status", ce ? "CE_MARKED" : "UNKNOWN",
                    "productCodes", Docs.strings(rec.get("emdnCodes")), "reference", Docs.str(rec.get("riskClass")), "verifiedOn", retrieved.toString(),
                    "source", "EUDAMED"));
        }
        detail.put("regulatory", regulatory);
        models.updateDetail(m, detail, Set.of("udi", "regulatory"), user.username());
        support.history("MODEL", modelCode, null, user.username(), "UDI_IMPORT", null, Docs.of("source", cmd.source(), "deviceIdentifier", cmd.deviceIdentifier()), null);
        support.audit("DEVICE_MODEL_UPDATE", "DEVICE_MODEL", modelCode, "udi " + cmd.source());
        return record(models.require(modelCode));
    }

    private static void upsertMarket(List<Map<String, Object>> regulatory, Map<String, Object> entry) {
        regulatory.removeIf(e -> entry.get("market").equals(e.get("market")));
        regulatory.add(0, entry);
    }

    // ------------------------------------------------------------------ vendor security onboarding (E32-F07)

    @Transactional
    public Map<String, Object> putPosture(CurrentUser user, String modelCode, Map<String, Object> intake) {
        support.requireEnabled();
        ModelStore.ModelRow m = models.require(modelCode);
        PosturePolicy.Evaluation ev = PosturePolicy.evaluate(intake);
        Map<String, Object> detail = Docs.copyMap(m.detail());
        Map<String, Object> security = new LinkedHashMap<>();
        security.put("mds2", Docs.copy(intake.getOrDefault("mds2", Docs.of("received", false))));
        security.put("sbom", Docs.copy(intake.getOrDefault("sbom", Docs.of("available", false))));
        security.put("supportStatus", intake.getOrDefault("supportStatus", "UNKNOWN"));
        security.put("osName", intake.get("osName"));
        security.put("osSupported", intake.get("osSupported"));
        security.put("patchPath", intake.get("patchPath"));
        security.put("remoteSupport", intake.get("remoteSupport"));
        security.put("contacts", Docs.copy(intake.get("contacts")));
        security.put("contract", Docs.copy(intake.get("contract")));
        security.put("evaluatedPosture", ev.posture());
        security.put("findings", ev.findings().stream().map(f -> Docs.of("rule", f.rule(), "outcome", f.outcome(), "message", f.message())).toList());
        security.put("assessedBy", user.username());
        security.put("assessedAt", support.now().toString());
        security.values().removeIf(java.util.Objects::isNull);
        detail.put("security", security);
        models.updateDetail(m, detail, Set.of("security"), user.username());
        String posture = ev.blocked() && activeException(modelCode) ? "EXCEPTION_APPROVED" : ev.posture();
        models.setPosture(modelCode, posture, user.username());
        support.history("MODEL", modelCode, null, user.username(), "POSTURE", null, Docs.of("from", m.posture(), "to", posture), null);
        support.audit("DEVICE_POSTURE_UPDATE", "DEVICE_MODEL", modelCode, posture);
        return Docs.of("modelCode", modelCode, "posture", posture, "evaluatedPosture", ev.posture(), "findings", security.get("findings"));
    }

    private boolean activeException(String modelCode) {
        Integer n = support.jdbc().queryForObject("""
                select count(*) from devicecat.posture_exception where model_code = ? and status = 'APPROVED' and expires_on >= ?""",
                Integer.class, modelCode, java.sql.Date.valueOf(support.today()));
        return n != null && n > 0;
    }

    public record ExceptionCommand(List<String> compensatingControls, LocalDate expiresOn, String riskOwner) {
    }

    @Transactional
    public Map<String, Object> requestException(CurrentUser user, String modelCode, ExceptionCommand cmd) {
        support.requireEnabled();
        ModelStore.ModelRow m = models.require(modelCode);
        List<String> controls = cmd.compensatingControls() == null ? List.of() : cmd.compensatingControls().stream().map(s -> trim(s, 300)).toList();
        if (controls.isEmpty() || cmd.expiresOn() == null || cmd.riskOwner() == null || cmd.riskOwner().isBlank()) {
            throw ApiException.badRequest("validation_failed", "compensatingControls, expiresOn and riskOwner are required");
        }
        if (!cmd.expiresOn().isAfter(support.today()) || cmd.expiresOn().isAfter(support.today().plusYears(1))) {
            throw ApiException.badRequest("validation_failed", "expiresOn must be in the future and within one year");
        }
        PosturePolicy.Evaluation ev = PosturePolicy.evaluate(Docs.map(m.detail().get("security")));
        List<String> missing = PosturePolicy.missingControls(ev, controls);
        if (!missing.isEmpty()) {
            throw E32Support.unprocessable("compensating_controls_insufficient", "Required compensating controls missing: " + String.join(", ", missing));
        }
        UUID id = UUID.randomUUID();
        support.jdbc().update("""
                insert into devicecat.posture_exception (id, model_code, compensating_controls, expires_on, risk_owner, status, requested_by, requested_at)
                values (?, ?, ?, ?, ?, 'REQUESTED', ?, ?)""", id, modelCode, support.write(controls), java.sql.Date.valueOf(cmd.expiresOn()),
                trim(cmd.riskOwner(), 200), user.username(), Timestamp.from(support.now()));
        approvals.open("POSTURE_EXCEPTION", modelCode + "/" + id, null, user.username(), Docs.of("exceptionId", id.toString(), "modelCode", modelCode));
        support.notifyRole("SECURITY_OPS", "device.approval-required", support.params(modelCode, null), user.username());
        support.audit("DEVICE_POSTURE_EXCEPTION", "DEVICE_MODEL", modelCode, "requested");
        return exceptionView(id);
    }

    /** SECURITY_OPS approves (never the requester); a blocked model becomes EXCEPTION_APPROVED until expiry. */
    @Transactional
    public Map<String, Object> decideException(CurrentUser user, String modelCode, UUID exceptionId, boolean approve, String note) {
        support.requireEnabled();
        Map<String, Object> ex = exceptionView(exceptionId);
        if (!modelCode.equals(ex.get("modelCode")) || !"REQUESTED".equals(ex.get("status"))) {
            throw ApiException.conflict("invalid_status", "The exception is not awaiting a decision");
        }
        if (user.username().equals(ex.get("requestedBy"))) {
            throw ApiException.conflict("four_eyes_violation", "The requester cannot approve the exception");
        }
        String status = approve ? "APPROVED" : "REJECTED";
        support.jdbc().update("update devicecat.posture_exception set status = ?, decided_by = ?, decided_at = ?, note = ? where id = ?",
                status, user.username(), Timestamp.from(support.now()), trim(note, 1000), exceptionId);
        approvals.pending("POSTURE_EXCEPTION", modelCode + "/" + exceptionId).ifPresent(a -> approvals.decide(a.id(), status, user.username(), note));
        ModelStore.ModelRow m = models.require(modelCode);
        if (approve && !"ACCEPTABLE".equals(m.posture())) {
            models.setPosture(modelCode, "EXCEPTION_APPROVED", user.username());
        }
        support.history("MODEL", modelCode, null, user.username(), "POSTURE_EXCEPTION_" + status, null, Docs.of("exceptionId", exceptionId.toString()), null);
        support.audit("DEVICE_POSTURE_EXCEPTION", "DEVICE_MODEL", modelCode, status.toLowerCase(Locale.ROOT));
        return exceptionView(exceptionId);
    }

    public Map<String, Object> exceptionView(UUID id) {
        List<Map<String, Object>> rows = support.jdbc().queryForList("select * from devicecat.posture_exception where id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Posture exception");
        }
        Map<String, Object> r = rows.get(0);
        return Docs.of("exceptionId", String.valueOf(r.get("id")), "modelCode", r.get("model_code"),
                "compensatingControls", support.readList(Docs.str(r.get("compensating_controls"))), "expiresOn", String.valueOf(r.get("expires_on")),
                "riskOwner", r.get("risk_owner"), "status", r.get("status"), "requestedBy", r.get("requested_by"), "decidedBy", r.get("decided_by"),
                "note", r.get("note"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> exceptions(String modelCode) {
        return support.jdbc().queryForList("select id from devicecat.posture_exception where model_code = ? order by requested_at desc", UUID.class, modelCode)
                .stream().map(this::exceptionView).toList();
    }

    /** Daily: expired exceptions re-block the model (E34-HLR-07). */
    @Transactional
    public int expireExceptions() {
        List<Map<String, Object>> expired = support.jdbc().queryForList(
                "select id, model_code from devicecat.posture_exception where status = 'APPROVED' and expires_on < ?", java.sql.Date.valueOf(support.today()));
        for (Map<String, Object> r : expired) {
            support.jdbc().update("update devicecat.posture_exception set status = 'EXPIRED' where id = ?", r.get("id"));
            String code = Docs.str(r.get("model_code"));
            ModelStore.ModelRow m = models.require(code);
            if ("EXCEPTION_APPROVED".equals(m.posture()) && !activeException(code)) {
                String evaluated = Docs.str(Docs.map(m.detail().get("security")).get("evaluatedPosture"));
                models.setPosture(code, evaluated == null ? "BLOCKED" : evaluated, "system");
                support.history("MODEL", code, null, "system", "POSTURE_EXCEPTION_EXPIRED", null, Docs.of("exceptionId", String.valueOf(r.get("id"))), null);
            }
        }
        return expired.size();
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
