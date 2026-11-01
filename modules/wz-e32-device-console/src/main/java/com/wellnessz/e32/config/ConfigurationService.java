package com.wellnessz.e32.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceDiagnostics;
import com.wellnessz.casekernel.device.DeviceEndpointProvisioning;
import com.wellnessz.casekernel.device.DeviceEquipmentRegister;
import com.wellnessz.casekernel.support.DeviceCalibrationCheck;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e32.calibration.CalibrationService;
import com.wellnessz.e32.catalogue.CatalogueRoutes;
import com.wellnessz.e32.catalogue.ModelStore;
import com.wellnessz.e32.catalogue.PosturePolicy;
import com.wellnessz.e32.driver.DriverStore;
import com.wellnessz.e32.option.OptionPacks;
import com.wellnessz.e32.option.OptionRules;
import com.wellnessz.e32.option.ParameterValidator;
import com.wellnessz.e32.support.ApprovalStore;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;
import com.wellnessz.e32.support.FieldIssue;
import com.wellnessz.e32.support.JsonDiff;
import com.wellnessz.security.identity.CurrentUser;

/**
 * The eight-step provisioning wizard state machine (E32-HLR-02/-03/-05/-08/-10, E32-F02/F06/F10/F11, contract §13.2):
 * select device → choose option (unsupported options hidden) → prerequisites with auto-checks → configure with driver
 * defaults (secrets via the vault) → associate and bind (with golden-dataset preview) → test through the diagnostics SPI with
 * evidence → four-eyes certification with PDF → publish (endpoint provisioning, token shown once); plus pause / resume,
 * clone, revise (atomic switch), suspend / resume and decommissioning with four-eyes approval and key revocation.
 */
@Service("e32ConfigurationService")
public class ConfigurationService {

    private static final Set<String> FINAL = Set.of(Configuration.DECOMMISSIONED, Configuration.SUPERSEDED);

    private final E32Support support;
    private final ConfigurationStore store;
    private final ModelStore models;
    private final DriverStore drivers;
    private final StationStore stations;
    private final OptionPacks packs;
    private final ApprovalStore approvals;
    private final CertificationService certifications;
    private final CalibrationService calibration;
    private final List<DeviceCalibrationCheck> calibrationChecks;

    public ConfigurationService(E32Support support, ConfigurationStore store, ModelStore models, DriverStore drivers, StationStore stations,
                                OptionPacks packs, ApprovalStore approvals, CertificationService certifications, CalibrationService calibration,
                                List<DeviceCalibrationCheck> calibrationChecks) {
        this.support = support;
        this.store = store;
        this.models = models;
        this.drivers = drivers;
        this.stations = stations;
        this.packs = packs;
        this.approvals = approvals;
        this.certifications = certifications;
        this.calibration = calibration;
        this.calibrationChecks = List.copyOf(calibrationChecks);
    }

    // ------------------------------------------------------------------ commands records

    /** @param tenantCode tenant of the instance (E31-HLR-12; {@code GROUP} when omitted) – the model must be in its catalogue */
    public record CreateCommand(String modelCode, String siteCode, UUID templateId, String tenantCode) {

        public CreateCommand(String modelCode, String siteCode, UUID templateId) {
            this(modelCode, siteCode, templateId, null);
        }
    }

    public record CloneCommand(String siteCode, String stationCode) {
    }

    public record TestCommand(String mode, String datasetId) {
    }

    public record ReviewCommand(String outcome, String note) {
    }

    public record DecommissionCommand(String reason, String disposal, String transferTo) {
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String siteCode, String modelCode) {
        return list(status, siteCode, modelCode, null);
    }

    /** Configurations, optionally of one tenant only (tenant-scoped configurations, E31-HLR-12). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String siteCode, String modelCode, String tenantCode) {
        support.requireEnabled();
        return store.latestAll().stream()
                .filter(c -> tenantCode == null || tenantCode.equals(c.tenantCode()))
                .filter(c -> status == null || status.equals(c.status()))
                .filter(c -> siteCode == null || siteCode.equals(c.siteCode()))
                .filter(c -> modelCode == null || modelCode.equals(c.modelCode()))
                .map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        support.requireEnabled();
        return view(store.requireLatest(id));
    }

    public Map<String, Object> summary(Configuration c) {
        return Docs.of("configurationId", c.configurationId(), "version", c.version(), "deviceInstanceId", c.deviceInstanceId(),
                "status", c.status(), "paused", c.paused(), "currentStep", c.currentStep(), "modelCode", c.modelCode(), "option", c.option(),
                "siteCode", c.siteCode(), "stationCode", c.stationCode(), "endpointId", c.endpointId(), "driverPackageId", c.driverPackageId(),
                "driverVersion", c.driverVersion(), "updatedAt", c.updatedAt(), "submittedBy", c.submittedBy(), "clonedFrom", c.clonedFrom());
    }

    /** Full view with secrets shown as {@code {set:true}} only. */
    public Map<String, Object> view(Configuration c) {
        Map<String, Object> v = summary(c);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int n = 1; n <= 8; n++) {
            Map<String, Object> s = Docs.copyMap(c.step(n));
            Map<String, Object> data = Docs.map(s.get("data"));
            if (n == 4) {
                data.put("parameters", maskSecrets(c));
                data.put("secrets", maskedSecretNames(c));
            }
            s.put("data", data);
            s.put("step", n);
            s.put("name", WizardRules.stepName(n));
            steps.add(s);
        }
        v.put("steps", steps);
        v.put("tests", Docs.copy(c.tests()));
        v.put("nurseReview", c.data().get("nurseReview"));
        v.put("simulatorOnlyJustification", c.data().get("simulatorOnlyJustification"));
        v.put("approvals", c.data().get("approvals"));
        v.put("decommissionRequest", c.data().get("decommissionRequest"));
        v.put("disposal", c.data().get("disposal"));
        v.put("secretsToRebind", c.data().get("secretsToRebind"));
        // who changed steps 4–6 of this version (with the submitter excluded from approving – four-eyes, enforced on the server)
        v.put("stepEditors", new ArrayList<>(c.stepEditors()));
        v.put("stepEditorsByStep", stepEditorsByStep(c));
        v.put("approvedBy", c.approvedBy());
        v.put("coApprovedBy", c.coApprovedBy());
        v.put("approvedAt", c.approvedAt());
        v.put("publishedAt", c.publishedAt());
        v.put("statusReason", c.statusReason());
        v.put("equipmentDeviceId", c.equipmentDeviceId());
        v.put("serial", c.serial());
        v.put("firmware", c.firmware());
        v.put("softwareVersion", c.softwareVersion());
        v.values().removeIf(java.util.Objects::isNull);
        return v;
    }

    /** Last editor of each of steps 4–6 ({@code "4" → username}); steps never edited are omitted. */
    static Map<String, Object> stepEditorsByStep(Configuration c) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int n = 4; n <= 6; n++) {
            Object by = c.step(n).get("updatedBy");
            if (by != null) {
                out.put(String.valueOf(n), by);
            }
        }
        return out;
    }

    /**
     * Read-only view for the OH clinician / nursing head doing the step-6 test-form review (contract §18): the device, station,
     * forms and bound fields, the test runs and the review – no connectivity parameters, secrets (not even references), endpoint
     * identity or checklist evidence.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> review(String id) {
        support.requireEnabled();
        Configuration c = store.requireLatest(id);
        Map<String, Object> step5 = Docs.copyMap(Docs.map(c.step(5).get("data")));
        Map<String, Object> v = Docs.of("configurationId", c.configurationId(), "version", c.version(), "deviceInstanceId", c.deviceInstanceId(),
                "status", c.status(), "currentStep", c.currentStep(), "modelCode", c.modelCode(), "option", c.option(), "siteCode", c.siteCode(),
                "stationCode", c.stationCode(), "driverPackageId", c.driverPackageId(), "driverVersion", c.driverVersion(),
                "formCodes", new ArrayList<>(c.formCodes()), "association", step5, "testStep", Docs.of("status", c.stepStatus(6),
                        "errors", c.step(6).get("errors")), "tests", Docs.copy(c.tests()), "nurseReview", c.data().get("nurseReview"),
                "reviewOpen", Configuration.DRAFT.equals(c.status()) && !c.paused() && !c.tests().isEmpty());
        models.find(c.modelCode()).ifPresent(m -> {
            v.put("manufacturer", m.manufacturer());
            v.put("model", m.model());
        });
        v.values().removeIf(java.util.Objects::isNull);
        return v;
    }

    private Map<String, Object> maskSecrets(Configuration c) {
        Map<String, Object> p = Docs.copyMap(c.parameters());
        if (c.option() != null) {
            for (String path : ParameterValidator.secretPaths(packs.parameterSchema(c.option()))) {
                if (Docs.at(p, path) != null) {
                    Docs.put(p, path, Docs.of("set", true));
                }
            }
        }
        return p;
    }

    private Map<String, Object> maskedSecretNames(Configuration c) {
        Map<String, Object> out = new LinkedHashMap<>();
        c.secrets().keySet().forEach(k -> out.put(k, Docs.of("set", true)));
        return out;
    }

    /** Step 2 comparison: every option the model supports (unsupported ones hidden) with driver availability. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> options(String id) {
        Configuration c = store.requireLatest(id);
        return comparison(models.require(c.modelCode()), stations.testSite(c.siteCode()));
    }

    private List<Map<String, Object>> comparison(ModelStore.ModelRow m, boolean testSite) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String o : CatalogueRoutes.offered(m.modelCode(), m.options())) {
            Map<String, Object> row = packs.comparison(o);
            if (!m.supports(o)) {
                row.put("catalogueRoute", true); // integration route of the catalogue (vendor system), not an inventory option
            }
            Optional<DriverStore.DriverRow> d = drivers.best(m.modelCode(), o, true);
            row.put("driverAvailable", d.isPresent());
            d.ifPresent(x -> row.put("driver", Docs.of("packageId", x.packageId(), "version", x.version(), "status", x.status())));
            row.put("certifiedDriver", drivers.best(m.modelCode(), o, false).isPresent());
            out.add(row);
        }
        return out;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public Map<String, Object> create(CurrentUser user, CreateCommand cmd) {
        support.requireEnabled();
        if (cmd.modelCode() == null) {
            throw ApiException.badRequest("validation_failed", "modelCode is required");
        }
        requireWriter(user, cmd.siteCode());
        String tenant = support.tenant(cmd.tenantCode()).code(); // 400 unknown_tenant
        Configuration c = ConfigurationStore.draft(support.nextConfigurationId(), 1, support.nextInstanceId(), cmd.modelCode(), user.username());
        c.setTenantCode(tenant);
        c.setSiteCode(cmd.siteCode());
        c.setTemplateId(cmd.templateId());
        List<String> warnings = applyStep1(c, Docs.of("modelCode", cmd.modelCode()), user.username());
        store.insert(c);
        support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "CREATE", 1,
                Docs.of("modelCode", c.modelCode(), "siteCode", c.siteCode()), null);
        support.audit("DEVICE_CONFIG_CREATE", "DEVICE_CONFIGURATION", c.configurationId());
        Map<String, Object> v = view(c);
        if (!warnings.isEmpty()) {
            v.put("warnings", warnings);
        }
        return v;
    }

    private void requireWriter(CurrentUser user, String siteCode) {
        if (user.hasRole("DEVICE_ADMIN")) {
            return;
        }
        if (user.hasRole("INTEGRATION_TESTER") && stations.testSite(siteCode)) {
            return;
        }
        throw ApiException.forbidden("Integration testers may create configurations on test sites only");
    }

    // ------------------------------------------------------------------ steps

    @Transactional
    public Map<String, Object> putStep(CurrentUser user, String id, int n, Map<String, Object> body) {
        support.requireEnabled();
        Configuration c = editable(store.requireLatest(id));
        requireWriter(user, n == 5 ? Docs.str(body.get("siteCode")) : c.siteCode());
        if (n < 1 || n > 6) {
            throw ApiException.badRequest("validation_failed", "steps 7 and 8 are commands (submit / approve / publish)");
        }
        for (int k = 1; k < n; k++) {
            if (!"VALID".equals(c.stepStatus(k))) {
                throw new FieldIssue.Invalid("step_invalid", "Complete the earlier steps first",
                        List.of(new FieldIssue("step" + k, "step_incomplete", WizardRules.stepName(k) + " is not complete")));
            }
        }
        Map<String, Object> before = Docs.copyMap(c.stepData(n));
        List<String> warnings = switch (n) {
            case 1 -> applyStep1(c, body, user.username());
            case 2 -> applyStep2(c, body, user.username());
            case 3 -> applyStep3(c, body, user.username());
            case 4 -> applyStep4(c, body, user.username());
            case 5 -> applyStep5(c, body, user.username());
            default -> applyStep6(c, body, user.username());
        };
        if (n >= 4) {
            c.addStepEditor(user.username());
        }
        store.update(c, user.username());
        support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "STEP", n,
                JsonDiff.diff(redact(before), redact(c.stepData(n))), null);
        support.audit("DEVICE_CONFIG_STEP", "DEVICE_CONFIGURATION", c.configurationId(), "step " + n);
        Map<String, Object> v = view(c);
        if (!warnings.isEmpty()) {
            v.put("warnings", warnings);
        }
        return v;
    }

    private static Object redact(Map<String, Object> data) {
        Map<String, Object> d = Docs.copyMap(data);
        d.remove("defaults");
        return d;
    }

    private Configuration editable(Configuration c) {
        if (c.paused()) {
            throw ApiException.conflict("configuration_paused", "The configuration is paused – resume it first");
        }
        if (!Configuration.DRAFT.equals(c.status())) {
            throw ApiException.conflict("invalid_status", "Only a DRAFT configuration can be changed (revise a published one)");
        }
        return c;
    }

    /** Step 1 – select device. */
    List<String> applyStep1(Configuration c, Map<String, Object> body, String actor) {
        String modelCode = Docs.str(body.get("modelCode"));
        List<String> warnings = new ArrayList<>();
        List<FieldIssue> issues = new ArrayList<>();
        ModelStore.ModelRow m = modelCode == null ? null : models.find(modelCode).orElse(null);
        if (m == null) {
            issues.add(new FieldIssue("modelCode", "unknown_model", "The model is not in the catalogue – use 'request a new model'"));
        } else {
            if ("RETIRED".equals(m.lifecycle())) {
                issues.add(new FieldIssue("modelCode", "model_retired", "Retired models cannot be provisioned"));
            }
            if ("DEPRECATED".equals(m.lifecycle())) {
                warnings.add("model_deprecated");
            }
            if ("BLOCKED".equals(m.posture())) {
                issues.add(new FieldIssue("modelCode", "posture_blocked", "The model's security posture is blocked and no exception is approved"));
            }
            if (!support.visible(c.tenantCode(), models.tenantOf(m.modelCode()))) {
                issues.add(new FieldIssue("modelCode", "model_not_in_tenant_catalogue", "The model is not in the catalogue of tenant " + c.tenantCode()));
            }
        }
        if (!issues.isEmpty()) {
            throw new FieldIssue.Invalid("step_invalid", "Step 1 is not valid", issues);
        }
        boolean changed = !modelCode.equals(c.modelCode()) || !"VALID".equals(c.stepStatus(1));
        Map<String, Object> payload = Docs.of("modelCode", modelCode, "serial", body.get("serial"), "udi", body.get("udi"),
                "equipmentDeviceId", body.get("equipmentDeviceId"), "firmware", body.get("firmware"), "softwareVersion", body.get("softwareVersion"),
                "lifecycle", m.lifecycle(), "posture", m.posture());
        c.setModelCode(modelCode);
        c.setSerial(Docs.str(body.get("serial")));
        c.setUdi(Docs.str(body.get("udi")));
        c.setEquipmentDeviceId(Docs.str(body.get("equipmentDeviceId")));
        c.setFirmware(Docs.str(body.get("firmware")));
        c.setSoftwareVersion(Docs.str(body.get("softwareVersion")));
        Instant now = support.now();
        if (changed && !modelCode.equals(Docs.str(c.stepData(1).get("modelCode")))) {
            WizardRules.invalidateFrom(c, 2, true, actor, now);
            c.setOption(null);
            c.setDriverPackageId(null);
            c.setDriverVersion(null);
        }
        c.setStep(1, "VALID", payload, null, actor, now);
        c.setCurrentStep(Math.max(c.currentStep(), 2));
        return warnings;
    }

    /** Step 2 – choose connectivity option (only options the model supports; a driver package must exist). */
    List<String> applyStep2(Configuration c, Map<String, Object> body, String actor) {
        String option = Docs.str(body.get("option"));
        ModelStore.ModelRow m = models.require(c.modelCode());
        List<FieldIssue> issues = new ArrayList<>();
        boolean testSite = stations.testSite(c.siteCode());
        Optional<DriverStore.DriverRow> driver = Optional.empty();
        if (option == null || !CatalogueRoutes.offers(m, option)) {
            issues.add(new FieldIssue("option", "option_not_supported", "The model does not support this connectivity option"));
        } else {
            driver = drivers.best(m.modelCode(), option, true, owner -> support.visible(c.tenantCode(), owner));
            if (driver.isEmpty()) {
                issues.add(new FieldIssue("option", "no_driver_for_option", "No registered or certified driver package exists for this model and option"));
            }
        }
        if (!issues.isEmpty()) {
            throw new FieldIssue.Invalid("step_invalid", "Step 2 is not valid", issues);
        }
        Instant now = support.now();
        if (!option.equals(c.option())) {
            WizardRules.invalidateFrom(c, 3, true, actor, now);
            c.secrets().clear();
        }
        c.setOption(option);
        DriverStore.DriverRow d = driver.orElseThrow();
        c.setDriverPackageId(d.packageId());
        c.setDriverVersion(d.version());
        c.setStep(2, "VALID", Docs.of("option", option, "comparison", comparison(m, testSite),
                "driver", Docs.of("packageId", d.packageId(), "version", d.version(), "status", d.status())), null, actor, now);
        c.setCurrentStep(Math.max(c.currentStep(), 3));
        List<String> warnings = new ArrayList<>();
        if (!"CERTIFIED".equals(d.status())) {
            warnings.add("driver_not_certified");
        }
        return warnings;
    }

    /** Step 3 – prerequisites checklist (manual items from the request, AUTO items re-checked). */
    List<String> applyStep3(Configuration c, Map<String, Object> body, String actor) {
        ModelStore.ModelRow m = models.require(c.modelCode());
        List<Map<String, Object>> items = currentItems(c, m);
        Map<String, Map<String, Object>> given = new LinkedHashMap<>();
        for (Object o : Docs.list(body.get("items"))) {
            Map<String, Object> i = Docs.map(o);
            if (Docs.str(i.get("code")) != null) {
                given.put(Docs.str(i.get("code")), i);
            }
        }
        List<FieldIssue> issues = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> g = given.get(Docs.str(item.get("code")));
            if (g == null) {
                continue;
            }
            String status = Docs.str(g.get("status"));
            if (status != null && !List.of("DONE", "NOT_APPLICABLE", "OPEN").contains(status)) {
                issues.add(new FieldIssue("items." + item.get("code"), "enum", "status must be DONE, NOT_APPLICABLE or OPEN"));
                continue;
            }
            Map<String, Object> evidence = Docs.map(g.get("evidence"));
            if (!evidence.isEmpty()) {
                item.put("evidence", Docs.of("text", trim(Docs.str(evidence.get("text")), 500), "documentId", trim(Docs.str(evidence.get("documentId")), 100),
                        "reference", trim(Docs.str(evidence.get("reference")), 100)));
            }
            if (WizardRules.isAuto(Docs.str(item.get("code")))) {
                continue; // status of AUTO items comes from the auto-check
            }
            if ("DONE".equals(status) && Docs.map(item.get("evidence")).isEmpty()) {
                issues.add(new FieldIssue("items." + item.get("code"), "evidence_required", "record the evidence (reference, document or note)"));
                continue;
            }
            if (status != null) {
                item.put("status", status);
            }
        }
        if (!issues.isEmpty()) {
            throw new FieldIssue.Invalid("step_invalid", "Step 3 is not valid", issues);
        }
        autoCheck(c, m, items);
        Map<String, Object> zone = items.stream().filter(i -> "NETWORK_ZONE".equals(i.get("code"))).findFirst().orElse(Map.of());
        String zoneRef = Docs.str(Docs.map(zone.get("evidence")).get("reference"));
        c.setZone(zoneRef != null && zoneRef.matches("[A-Z_]{2,40}") ? zoneRef : c.zone());
        List<FieldIssue> open = WizardRules.prerequisiteIssues(items);
        Instant now = support.now();
        c.setStep(3, open.isEmpty() ? "VALID" : "IN_PROGRESS", Docs.of("items", items), FieldIssue.toMaps(open), actor, now);
        if (open.isEmpty()) {
            c.setCurrentStep(Math.max(c.currentStep(), 4));
        }
        return List.of();
    }

    /** {@code POST …/steps/3/auto-check}. */
    @Transactional
    public Map<String, Object> autoCheck(CurrentUser user, String id) {
        support.requireEnabled();
        Configuration c = editable(store.requireLatest(id));
        if (!"VALID".equals(c.stepStatus(2))) {
            throw new FieldIssue.Invalid("step_invalid", "Choose the connectivity option first",
                    List.of(new FieldIssue("step2", "step_incomplete", "Choose connectivity option is not complete")));
        }
        applyStep3(c, Map.of(), user.username());
        store.update(c, user.username());
        support.audit("DEVICE_CONFIG_STEP", "DEVICE_CONFIGURATION", c.configurationId(), "step 3 auto-check");
        return view(c);
    }

    private List<Map<String, Object>> currentItems(Configuration c, ModelStore.ModelRow m) {
        List<Map<String, Object>> generated = WizardRules.prerequisites(packs.prerequisites(c.option()), m.family(), m.highConfidence(), c.option());
        Map<String, Map<String, Object>> existing = new LinkedHashMap<>();
        for (Object o : Docs.list(c.stepData(3).get("items"))) {
            Map<String, Object> i = Docs.map(o);
            existing.put(Docs.str(i.get("code")), i);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : generated) {
            Map<String, Object> e = existing.get(Docs.str(g.get("code")));
            if (e != null && !"NOT_APPLICABLE".equals(Docs.str(g.get("status")))) {
                g.put("status", e.get("status"));
                if (e.get("evidence") != null) {
                    g.put("evidence", e.get("evidence"));
                }
            }
            out.add(g);
        }
        return out;
    }

    private void autoCheck(Configuration c, ModelStore.ModelRow m, List<Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            String code = Docs.str(item.get("code"));
            Map<String, Object> ev = Docs.map(item.get("evidence"));
            switch (code) {
                case "VENDOR_POSTURE" -> {
                    boolean ok = PosturePolicy.usable(m.posture());
                    item.put("status", ok ? "DONE" : "OPEN");
                    item.put("autoResult", ok ? "Posture " + m.posture() : "Posture " + m.posture() + " – record the vendor security intake or an approved exception");
                }
                case "ENDPOINT_IDENTITY" -> {
                    String ref = Docs.str(ev.get("reference")) != null ? Docs.str(ev.get("reference")) : c.endpointId();
                    if (ref == null) {
                        item.put("status", "DONE");
                        item.put("autoResult", "A new endpoint identity will be provisioned at publication (step 8)");
                    } else {
                        try {
                            DeviceEndpointProvisioning.EndpointState s = support.spis().provisioning().state(ref);
                            boolean ok = s != null && s.status() == DeviceCodes.EndpointStatus.ACTIVE;
                            item.put("status", ok ? "DONE" : "OPEN");
                            item.put("autoResult", s == null ? "Endpoint unknown" : "Endpoint " + ref + " is " + s.status());
                        } catch (ApiException unavailable) {
                            item.put("status", "OPEN");
                            item.put("autoResult", "Endpoint security module unavailable – cannot verify the enrolment");
                        }
                    }
                }
                case "CALIBRATION_EVIDENCE" -> {
                    if ("NOT_APPLICABLE".equals(Docs.str(item.get("status"))) && !WizardRules.CALIBRATED.contains(m.family())) {
                        item.put("autoResult", "Not a calibrated measurement device");
                        continue;
                    }
                    String equipment = Docs.str(ev.get("reference")) != null ? Docs.str(ev.get("reference")) : c.equipmentDeviceId();
                    Optional<DeviceEquipmentRegister.EquipmentItem> reg = equipment == null ? Optional.empty() : support.spis().equipment().find(equipment);
                    if (reg.isPresent()) {
                        String verdict = calibrationVerdict(equipment);
                        boolean ok = "VALID".equals(verdict);
                        item.put("status", ok ? "DONE" : "OPEN");
                        item.put("autoResult", "Equipment " + equipment + " calibration " + verdict
                                + (reg.get().calibrationDueOn() == null ? "" : ", due " + reg.get().calibrationDueOn()));
                        if (ok) {
                            c.setEquipmentDeviceId(equipment);
                        }
                    } else if (Docs.str(ev.get("documentId")) != null || Docs.str(ev.get("text")) != null && equipment == null) {
                        item.put("status", "DONE");
                        item.put("autoResult", "Calibration certificate recorded manually (device not in the equipment register)");
                    } else {
                        item.put("status", "OPEN");
                        item.put("autoResult", equipment == null ? "Link the equipment-register record or upload the calibration certificate"
                                : "Equipment " + equipment + " is not in the equipment register");
                    }
                }
                default -> {
                    // manual item
                }
            }
        }
    }

    private String calibrationVerdict(String equipmentDeviceId) {
        DeviceCalibrationCheck.Verdict worst = null;
        for (DeviceCalibrationCheck check : calibrationChecks) {
            DeviceCalibrationCheck.Status s = check.check(equipmentDeviceId, support.today());
            if (s != null && s.verdict() != null && (worst == null || s.verdict().ordinal() > worst.ordinal())) {
                worst = s.verdict();
            }
        }
        return worst == null ? "UNKNOWN" : worst.name();
    }

    /** Defaults for step 4: schema defaults overlaid with the driver package's protocol defaults. */
    @Transactional(readOnly = true)
    public Map<String, Object> defaults(String id) {
        Configuration c = store.requireLatest(id);
        if (c.option() == null) {
            throw new FieldIssue.Invalid("step_invalid", "Choose the connectivity option first",
                    List.of(new FieldIssue("step2", "step_incomplete", "Choose connectivity option is not complete")));
        }
        return Docs.of("option", c.option(), "parameters", parameterDefaults(c), "common", commonDefaults(c),
                "parameterSchema", packs.parameterSchema(c.option()), "commonSchema", packs.commonSchema(),
                "secretFields", ParameterValidator.secretPaths(packs.parameterSchema(c.option())));
    }

    private Map<String, Object> parameterDefaults(Configuration c) {
        Map<String, Object> schema = packs.parameterSchema(c.option());
        Map<String, Object> d = ParameterValidator.defaults(schema);
        Map<String, Object> manifest = manifestOf(c);
        Map<String, Object> merged = Docs.merge(d, OptionRules.fromPackage(c.option(), Docs.map(manifest.get("protocol")), schema));
        return Docs.merge(merged, Docs.map(Docs.at(c.data(), "templateDefaults.parameters")));
    }

    private Map<String, Object> commonDefaults(Configuration c) {
        Map<String, Object> d = ParameterValidator.defaults(packs.commonSchema());
        d.put("driverPackageId", c.driverPackageId());
        d.put("driverVersion", c.driverVersion());
        if (c.siteCode() != null && c.siteCode().startsWith("DXB")) {
            d.put("timeZone", "Asia/Dubai");
        }
        return d;
    }

    private Map<String, Object> manifestOf(Configuration c) {
        if (c.driverPackageId() == null) {
            return Map.of();
        }
        return drivers.find(c.driverPackageId(), c.driverVersion()).map(d -> support.readMap(d.manifestJson())).orElse(Map.of());
    }

    /** Step 4 – configure parameters (schema + practice rules), common items (tighten only) and secrets (vault references). */
    List<String> applyStep4(Configuration c, Map<String, Object> body, String actor) {
        String option = c.option();
        Map<String, Object> schema = packs.parameterSchema(option);
        Map<String, Object> commonIn = Docs.map(body.get("common"));
        Map<String, Object> common = Docs.merge(commonDefaults(c), commonIn);
        List<FieldIssue> issues = new ArrayList<>();
        // driver choice
        String pkgId = Docs.str(common.get("driverPackageId"));
        String version = Docs.str(common.get("driverVersion"));
        DriverStore.DriverRow driver = pkgId == null || version == null ? null : drivers.find(pkgId, version).orElse(null);
        boolean testSite = stations.testSite(c.siteCode());
        if (driver == null) {
            issues.add(new FieldIssue("common.driverVersion", "driver_unknown", "The driver package version is not in the registry"));
        } else {
            if (!driver.models().contains(c.modelCode()) || !driver.options().contains(option)) {
                issues.add(new FieldIssue("common.driverPackageId", "driver_mismatch", "The package does not serve this model and option"));
            }
            if (!driver.usable(testSite)) {
                issues.add(new FieldIssue("common.driverVersion", "driver_not_certified",
                        "Only a CERTIFIED driver version may be used (REGISTERED versions on test sites only)"));
            }
        }
        Map<String, Object> manifest = driver == null ? Map.of() : support.readMap(driver.manifestJson());
        Map<String, Object> defaults = Docs.merge(Docs.merge(ParameterValidator.defaults(schema),
                OptionRules.fromPackage(option, Docs.map(manifest.get("protocol")), schema)), Docs.map(Docs.at(c.data(), "templateDefaults.parameters")));
        Map<String, Object> params = Docs.merge(defaults, Docs.map(body.get("parameters")));
        // secrets: write-only, stored through DeviceSecretStore, parameters keep the reference
        List<String> secretPaths = ParameterValidator.secretPaths(schema);
        Map<String, Object> secretsIn = Docs.map(body.get("secrets"));
        for (String name : secretsIn.keySet()) {
            if (!secretPaths.contains(name)) {
                issues.add(new FieldIssue("secrets." + name, "unknown_secret", "not a secret parameter of this option"));
            }
        }
        for (String path : secretPaths) {
            Object existingRef = c.secrets().get(path);
            if (!secretsIn.containsKey(path) && existingRef != null && Docs.at(params, path) instanceof Map<?, ?>) {
                Docs.put(params, path, existingRef); // {set:true} echoed back by a client keeps the stored reference
            } else if (!secretsIn.containsKey(path) && existingRef != null && Docs.at(params, path) == null) {
                Docs.put(params, path, existingRef);
            }
        }
        // the schema sees a secret supplied in this request as its (future) reference: a secret the option schema requires (P6
        // oauth2.clientSecretRef) was otherwise always "required" – the value never travels in parameters, only in secrets
        Map<String, Object> validated = Docs.copyMap(params);
        secretsIn.forEach((k, v) -> {
            if (secretPaths.contains(k) && Docs.str(v) != null && !Docs.str(v).isEmpty()) {
                Docs.put(validated, k, "devsec:pending");
            }
        });
        List<FieldIssue> schemaIssues = ParameterValidator.validate(schema, validated);
        schemaIssues.forEach(i -> issues.add(new FieldIssue(i.field().startsWith("parameters") ? i.field() : "parameters." + i.field(), i.code(), i.message())));
        if (issues.isEmpty()) {
            // semantic rules see the secrets as (future) references
            Map<String, Object> probe = Docs.copyMap(params);
            secretsIn.keySet().forEach(k -> Docs.put(probe, k, "devsec:pending"));
            OptionRules.semantic(option, probe, new OptionRules.Context(support.cloudAllowedHosts(), support.tenantResidency(c.tenantCode()), c.zone()))
                    .forEach(i -> issues.add(new FieldIssue("parameters." + i.field(), i.code(), i.message())));
        }
        List<FieldIssue> commonSchemaIssues = ParameterValidator.validate(packs.commonSchema(), common);
        commonSchemaIssues.forEach(i -> issues.add(new FieldIssue(i.field().startsWith("parameters") ? "common" + i.field().substring(10)
                : "common." + i.field(), i.code(), i.message())));
        String family = models.require(c.modelCode()).family().name();
        issues.addAll(WizardRules.commonIssues(common, manifest, option, calibration.familyIntervals(family)));
        String host = OptionRules.hostEndpoint(option, params);
        if (host != null) {
            try {
                DeviceEndpointProvisioning.EndpointState s = support.spis().provisioning().state(host);
                if (s == null || s.status() == DeviceCodes.EndpointStatus.REVOKED || s.status() == DeviceCodes.EndpointStatus.ISOLATED) {
                    issues.add(new FieldIssue("parameters", "endpoint_not_usable", "The gateway / agent endpoint " + host + " is not enrolled or is isolated"));
                }
            } catch (ApiException unavailable) {
                // E34 not deployed: enrolment is verified again at publication
            }
        }
        if (!issues.isEmpty()) {
            throw new FieldIssue.Invalid("step_invalid", "Step 4 is not valid", issues);
        }
        for (Map.Entry<String, Object> e : secretsIn.entrySet()) {
            String value = Docs.str(e.getValue());
            if (value == null || value.isEmpty()) {
                continue;
            }
            Object existing = c.secrets().get(e.getKey());
            String ref;
            if (existing != null) {
                support.spis().secrets().rotate(actor, Docs.str(existing), value.toCharArray());
                ref = Docs.str(existing);
            } else {
                ref = support.spis().secrets().store(actor, "device-configuration:" + c.configurationId(), e.getKey(), value.toCharArray());
            }
            c.secrets().put(e.getKey(), ref);
            Docs.put(params, e.getKey(), ref);
        }
        c.data().remove("secretsToRebind");
        Instant now = support.now();
        boolean wasValid = "VALID".equals(c.stepStatus(4));
        c.setDriverPackageId(pkgId);
        c.setDriverVersion(version);
        c.setStep(4, "VALID", Docs.of("parameters", params, "common", common), null, actor, now);
        if (wasValid) {
            WizardRules.invalidateFrom(c, 5, false, actor, now);
        }
        c.setCurrentStep(Math.max(c.currentStep(), 5));
        return List.of();
    }

    /** Step 5 – associate and bind. */
    List<String> applyStep5(Configuration c, Map<String, Object> body, String actor) {
        List<FieldIssue> issues = new ArrayList<>();
        String siteCode = Docs.str(body.get("siteCode"));
        String stationCode = Docs.str(body.get("stationCode"));
        StationStore.Station st = stations.find(stationCode).orElse(null);
        if (st == null || !st.active()) {
            issues.add(new FieldIssue("stationCode", "unknown_station", "The station does not exist"));
        } else if (siteCode != null && !siteCode.equals(st.siteCode())) {
            issues.add(new FieldIssue("stationCode", "station_site_mismatch", "The station belongs to another site"));
        }
        Map<String, Object> manifest = manifestOf(c);
        List<String> formCodes = Docs.strings(body.get("formCodes"));
        List<Object> bindings = Docs.list(body.get("bindings"));
        if (bindings.isEmpty()) {
            bindings = new ArrayList<>(Docs.list(Docs.at(c.data(), "templateDefaults.bindings")));
        }
        if (bindings.isEmpty()) {
            for (Object o : Docs.list(manifest.get("bindings"))) {
                Map<String, Object> b = Docs.map(o);
                if (formCodes.isEmpty() || formCodes.contains(Docs.str(b.get("formCode")))) {
                    bindings.add(Docs.copyMap(b));
                }
            }
        }
        bindings = WizardRules.completeBindings(bindings, manifest);
        if (formCodes.isEmpty()) {
            for (Object o : bindings) {
                formCodes.add(Docs.str(Docs.map(o).get("formCode")));
            }
        }
        Set<String> bound = new LinkedHashSet<>();
        Set<DeviceCodes.Category> categories = new LinkedHashSet<>();
        for (Object o : bindings) {
            Map<String, Object> b = Docs.map(o);
            bound.add(Docs.str(b.get("formCode")));
            DeviceCodes.Category cat = com.wellnessz.e32.spi.CatalogueLookupAdapter.category(Docs.str(b.get("category")));
            if (cat == null) {
                issues.add(new FieldIssue("bindings.category", "enum", "unknown measurement category"));
            } else {
                categories.add(cat);
            }
        }
        for (String f : formCodes) {
            if (!bound.contains(f)) {
                issues.add(new FieldIssue("formCodes", "binding_missing", "form " + f + " has no binding"));
            }
        }
        List<String> methods = Docs.strings(body.get("associationMethods"));
        issues.addAll(WizardRules.associationIssues(methods, manifest, categories));
        issues.addAll(WizardRules.bindingIssues(bindings, manifest, c.option()));
        if (!issues.isEmpty()) {
            throw new FieldIssue.Invalid("step_invalid", "Step 5 is not valid", issues);
        }
        boolean wasValid = "VALID".equals(c.stepStatus(5));
        c.setSiteCode(st.siteCode());
        c.setStationCode(st.stationCode());
        c.setRoomCode(Docs.str(body.get("roomCode")) == null ? st.roomCode() : Docs.str(body.get("roomCode")));
        Instant now = support.now();
        c.setStep(5, "VALID", Docs.of("associationMethods", methods, "siteCode", st.siteCode(), "roomCode", c.roomCode(), "stationCode", st.stationCode(),
                "services", Docs.strings(body.get("services")), "formCodes", formCodes, "bindings", bindings), null, actor, now);
        if (wasValid) {
            WizardRules.invalidateFrom(c, 6, false, actor, now);
        }
        c.setCurrentStep(Math.max(c.currentStep(), 6));
        return List.of();
    }

    /** {@code POST …/steps/5/preview {datasetId}}: the bound fields as they would be filled from a golden dataset. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String id, String datasetId) {
        Configuration c = store.requireLatest(id);
        Map<String, Object> manifest = manifestOf(c);
        String wanted = datasetId == null ? "normal" : datasetId;
        Map<String, Object> vector = null;
        for (Object o : Docs.list(manifest.get("testVectors"))) {
            Map<String, Object> v = Docs.map(o);
            if (wanted.equals(Docs.str(v.get("name"))) || wanted.equals(Docs.str(v.get("input")))) {
                vector = v;
            }
        }
        if (vector == null) {
            throw ApiException.notFound("Golden dataset");
        }
        Map<String, Map<String, Object>> mappings = new LinkedHashMap<>();
        for (Object o : Docs.list(manifest.get("mappings"))) {
            mappings.put(Docs.str(Docs.map(o).get("key")), Docs.map(o));
        }
        Map<String, Object> expect = Docs.map(vector.get("expect"));
        List<Object> bindings = c.bindings().isEmpty() ? Docs.list(manifest.get("bindings")) : c.bindings();
        List<Map<String, Object>> forms = new ArrayList<>();
        for (Object o : bindings) {
            Map<String, Object> b = Docs.map(o);
            List<Map<String, Object>> fields = new ArrayList<>();
            Docs.map(b.get("fields")).forEach((key, formField) -> {
                Map<String, Object> m = mappings.getOrDefault(key, Map.of());
                fields.add(Docs.of("key", key, "formField", formField, "value", expect.get(key), "unit", m.get("ucum"), "loinc", m.get("loinc"),
                        "display", m.get("display"), "filled", expect.containsKey(key)));
            });
            forms.add(Docs.of("formCode", b.get("formCode"), "target", b.get("target"), "verifyBeforePost", b.get("verifyBeforePost"), "fields", fields));
        }
        return Docs.of("datasetId", vector.get("name"), "input", vector.get("input"), "expectedError", expect.get("error"),
                "expectedGates", expect.get("gates"), "forms", forms);
    }

    /** Step 6 PUT – records the simulator-only justification (then SECURITY_OPS co-approves at step 7). */
    List<String> applyStep6(Configuration c, Map<String, Object> body, String actor) {
        String justification = trim(Docs.str(body.get("simulatorOnlyJustification")), 1000);
        if (justification != null && !justification.isBlank()) {
            c.data().put("simulatorOnlyJustification", justification);
        } else {
            c.data().remove("simulatorOnlyJustification");
        }
        recomputeStep6(c, actor);
        return List.of();
    }

    /** {@code POST …/steps/6/tests}: connection + end-to-end test through {@link DeviceDiagnostics#runTest}. */
    @Transactional
    public Map<String, Object> runTest(CurrentUser user, String id, TestCommand cmd) {
        support.requireEnabled();
        Configuration c = editable(store.requireLatest(id));
        requireWriter(user, c.siteCode());
        for (int k = 1; k <= 5; k++) {
            if (!"VALID".equals(c.stepStatus(k))) {
                throw new FieldIssue.Invalid("step_invalid", "Complete steps 1–5 before testing",
                        List.of(new FieldIssue("step" + k, "step_incomplete", WizardRules.stepName(k) + " is not complete")));
            }
        }
        String mode = cmd.mode() == null ? "SIMULATOR" : cmd.mode();
        if (!"SIMULATOR".equals(mode) && !"LIVE".equals(mode)) {
            throw ApiException.badRequest("validation_failed", "mode must be SIMULATOR or LIVE");
        }
        String formCode = c.formCodes().isEmpty() ? null : c.formCodes().get(0);
        // the engine's LIVE test needs the instance and the endpoint fronting it (contract §13.4: connector or gateway seen in 5 min)
        Map<String, Object> testParameters = new LinkedHashMap<>(Docs.copyMap(c.parameters()));
        testParameters.put("deviceInstanceId", c.deviceInstanceId());
        String host = c.option() == null ? null : OptionRules.hostEndpoint(c.option(), c.parameters());
        if (host != null || c.endpointId() != null) {
            testParameters.put("endpointId", host != null ? host : c.endpointId());
        }
        DeviceDiagnostics.TestRun run = support.spis().diagnostics().runTest(user.username(), new DeviceDiagnostics.TestRequest(c.configurationId(),
                c.modelCode(), DeviceCodes.Option.ofCode(c.option()), mode, c.driverPackageId(), c.driverVersion(), cmd.datasetId(),
                testParameters, formCode));
        if (run == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "test_unavailable", "The test runner returned no result");
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (DeviceDiagnostics.TestStep s : run.steps()) {
            steps.add(Docs.of("name", s.name(), "outcome", s.outcome(), "detail", s.detail()));
        }
        Map<String, Object> entry = Docs.of("testRunId", run.testRunId() == null ? UUID.randomUUID().toString() : run.testRunId().toString(),
                "mode", mode, "status", run.status(), "datasetId", cmd.datasetId(), "steps", steps,
                "finishedAt", (run.finishedAt() == null ? support.now() : run.finishedAt()).toString(), "by", user.username());
        entry.put("evidenceSha256", run.evidenceSha256() != null ? run.evidenceSha256()
                : Docs.sha256(com.wellnessz.e32.support.CanonicalJson.write(entry)));
        c.tests().add(entry);
        c.addStepEditor(user.username());
        recomputeStep6(c, user.username());
        store.update(c, user.username());
        support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "TEST", 6,
                Docs.of("testRunId", entry.get("testRunId"), "mode", mode, "status", run.status(), "evidenceSha256", entry.get("evidenceSha256")), null);
        support.audit("DEVICE_CONFIG_TEST", "DEVICE_CONFIGURATION", c.configurationId(), mode + " " + run.status());
        return entry;
    }

    /** {@code POST …/steps/6/nurse-review}: test-form preview reviewed by an OH nurse / nursing head. */
    @Transactional
    public Map<String, Object> nurseReview(CurrentUser user, String id, ReviewCommand cmd) {
        support.requireEnabled();
        Configuration c = editable(store.requireLatest(id));
        if (!"ACCEPTED".equals(cmd.outcome()) && !"REJECTED".equals(cmd.outcome())) {
            throw ApiException.badRequest("validation_failed", "outcome must be ACCEPTED or REJECTED");
        }
        if (c.tests().isEmpty()) {
            throw E32Support.unprocessable("test_required", "Run a test into the test form before the nurse review");
        }
        c.data().put("nurseReview", Docs.of("outcome", cmd.outcome(), "note", trim(cmd.note(), 1000), "by", user.username(),
                "at", support.now().toString()));
        recomputeStep6(c, user.username());
        store.update(c, user.username());
        support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "NURSE_REVIEW", 6, Docs.of("outcome", cmd.outcome()), null);
        support.audit("DEVICE_CONFIG_TEST", "DEVICE_CONFIGURATION", c.configurationId(), "nurse review " + cmd.outcome());
        return view(c);
    }

    private void recomputeStep6(Configuration c, String actor) {
        List<FieldIssue> missing = step6Issues(c);
        Map<String, Object> data = Docs.of("tests", c.tests().size(), "simulatorOnlyJustification", c.data().get("simulatorOnlyJustification") != null);
        c.setStep(6, missing.isEmpty() ? "VALID" : "IN_PROGRESS", data, FieldIssue.toMaps(missing), actor, support.now());
        if (missing.isEmpty()) {
            c.setCurrentStep(Math.max(c.currentStep(), 7));
        }
    }

    /** Publish is blocked unless the latest simulator run (and, for production stations, the latest live run) passed. */
    List<FieldIssue> step6Issues(Configuration c) {
        List<FieldIssue> out = new ArrayList<>();
        String lastSim = null;
        String lastLive = null;
        for (Object o : c.tests()) {
            Map<String, Object> t = Docs.map(o);
            if ("SIMULATOR".equals(t.get("mode"))) {
                lastSim = Docs.str(t.get("status"));
            } else if ("LIVE".equals(t.get("mode"))) {
                lastLive = Docs.str(t.get("status"));
            }
        }
        if (!"PASSED".equals(lastSim)) {
            out.add(new FieldIssue("tests", "simulator_test_required", "a passing simulator run (including the negative vectors) is required"));
        }
        boolean testStation = stations.find(c.stationCode()).map(StationStore.Station::testSite).orElse(false);
        if (!testStation && !"PASSED".equals(lastLive) && c.data().get("simulatorOnlyJustification") == null) {
            out.add(new FieldIssue("tests", "live_test_required", "production stations need a passing LIVE run (or a simulator-only justification)"));
        }
        Map<String, Object> review = Docs.map(c.data().get("nurseReview"));
        if (!"ACCEPTED".equals(review.get("outcome"))) {
            out.add(new FieldIssue("nurseReview", "nurse_review_required", "the test-form result must be accepted by an OH nurse"));
        }
        return out;
    }

    // ------------------------------------------------------------------ step 7: submit, approve, reject

    @Transactional
    public Map<String, Object> submit(CurrentUser user, String id) {
        support.requireEnabled();
        Configuration c = editable(store.requireLatest(id));
        requireWriter(user, c.siteCode());
        List<FieldIssue> issues = new ArrayList<>();
        for (int k = 1; k <= 6; k++) {
            if (!"VALID".equals(c.stepStatus(k))) {
                issues.add(new FieldIssue("step" + k, "step_incomplete", WizardRules.stepName(k) + " is not complete"));
            }
        }
        if (!issues.isEmpty()) {
            throw new FieldIssue.Invalid("step_invalid", "Complete steps 1–6 before submitting", issues);
        }
        ModelStore.ModelRow m = models.require(c.modelCode());
        if ("BLOCKED".equals(m.posture())) {
            throw E32Support.unprocessable("posture_blocked", "The model's security posture is blocked");
        }
        DriverStore.DriverRow d = drivers.require(c.driverPackageId(), c.driverVersion());
        boolean testStation = stations.find(c.stationCode()).map(StationStore.Station::testSite).orElse(false);
        if (!d.usable(testStation)) {
            throw E32Support.unprocessable("driver_not_certified", "The pinned driver version is not certified");
        }
        Instant now = support.now();
        c.setStatus(Configuration.AWAITING_APPROVAL);
        c.setSubmitted(user.username(), now);
        c.data().put("approvals", new ArrayList<>());
        if ("EXCEPTION_APPROVED".equals(m.posture())) {
            c.data().put("postureException", currentException(m.modelCode()));
        }
        c.setStep(7, "IN_PROGRESS", Docs.of("submittedBy", user.username(), "submittedAt", now.toString(),
                "requiresSecurityCoApproval", c.data().get("simulatorOnlyJustification") != null), null, user.username(), now);
        c.setCurrentStep(7);
        store.update(c, user.username());
        UUID approvalId = approvals.open("CONFIGURATION", c.configurationId(), c.version(), user.username(),
                Docs.of("deviceInstanceId", c.deviceInstanceId(), "modelCode", c.modelCode(), "siteCode", c.siteCode()));
        support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "SUBMIT", 7, null, approvalId.toString());
        support.audit("DEVICE_CONFIG_SUBMIT", "DEVICE_CONFIGURATION", c.configurationId());
        support.notifyRole("DEVICE_APPROVER", "device.approval-required", support.params(c.configurationId(), c.siteCode()), user.username());
        if (c.data().get("simulatorOnlyJustification") != null) {
            support.notifyRole("SECURITY_OPS", "device.approval-required", support.params(c.configurationId(), c.siteCode()), user.username());
        }
        support.securityEvent("CONFIG_CHANGE_SUBMITTED", DeviceCodes.Severity.INFO, c.endpointId(), c.deviceInstanceId(), c.siteCode(),
                user.username(), "submitted", Map.of());
        return view(c);
    }

    private Map<String, Object> currentException(String modelCode) {
        List<Map<String, Object>> rows = support.jdbc().queryForList("""
                select id, compensating_controls, expires_on, risk_owner, decided_by from devicecat.posture_exception
                where model_code = ? and status = 'APPROVED' order by decided_at desc limit 1""", modelCode);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        return Docs.of("exceptionId", String.valueOf(r.get("id")), "compensatingControls", support.readList(Docs.str(r.get("compensating_controls"))),
                "expiresOn", String.valueOf(r.get("expires_on")), "riskOwner", r.get("risk_owner"), "approvedBy", r.get("decided_by"));
    }

    /**
     * Four-eyes approval: the approver is neither the submitter nor anyone who changed steps 4–6 of this version (409
     * {@code four_eyes_violation}); only DEVICE_APPROVER approves – plus SECURITY_OPS when the live test was waived.
     */
    @Transactional
    public Map<String, Object> approve(CurrentUser user, String id, String note) {
        support.requireEnabled();
        Configuration c = store.requireLatest(id);
        if (!Configuration.AWAITING_APPROVAL.equals(c.status())) {
            throw ApiException.conflict("invalid_status", "The configuration is not awaiting approval");
        }
        if (c.paused()) {
            throw ApiException.conflict("configuration_paused", "The configuration is paused – resume it first");
        }
        if (WizardRules.fourEyesViolation(c, user.username())) {
            support.auditDenied("DEVICE_CONFIG_APPROVE", "DEVICE_CONFIGURATION", c.configurationId(), "four_eyes_violation");
            support.securityEvent("CONFIG_CHANGE_FOUR_EYES_VIOLATION", DeviceCodes.Severity.MEDIUM, c.endpointId(), c.deviceInstanceId(), c.siteCode(),
                    user.username(), "four_eyes_violation", Map.of());
            throw ApiException.conflict("four_eyes_violation", "You submitted or changed this configuration – another approver must approve it");
        }
        boolean needsSecurity = c.data().get("simulatorOnlyJustification") != null;
        List<Object> given = Docs.list(c.data().get("approvals"));
        String role;
        if (user.hasRole("DEVICE_APPROVER") && given.stream().noneMatch(a -> "DEVICE_APPROVER".equals(Docs.map(a).get("role")))) {
            role = "DEVICE_APPROVER";
        } else if (needsSecurity && user.hasRole("SECURITY_OPS") && given.stream().noneMatch(a -> "SECURITY_OPS".equals(Docs.map(a).get("role")))) {
            role = "SECURITY_OPS";
        } else {
            throw ApiException.forbidden("A device approver (and security operations for a waived live test) must approve");
        }
        if (given.stream().anyMatch(a -> user.username().equals(Docs.map(a).get("by")))) {
            throw ApiException.conflict("four_eyes_violation", "The two approvals must come from different people");
        }
        Instant now = support.now();
        given.add(Docs.of("role", role, "by", user.username(), "at", now.toString(), "note", trim(note, 1000)));
        c.data().put("approvals", given);
        boolean complete = given.stream().anyMatch(a -> "DEVICE_APPROVER".equals(Docs.map(a).get("role")))
                && (!needsSecurity || given.stream().anyMatch(a -> "SECURITY_OPS".equals(Docs.map(a).get("role"))));
        if (!complete) {
            store.update(c, user.username());
            support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "APPROVE_PARTIAL", 7, Docs.of("role", role), null);
            support.audit("DEVICE_CONFIG_APPROVE", "DEVICE_CONFIGURATION", c.configurationId(), "partial " + role);
            return view(c);
        }
        String approver = given.stream().map(Docs::map).filter(a -> "DEVICE_APPROVER".equals(a.get("role"))).map(a -> Docs.str(a.get("by")))
                .findFirst().orElse(user.username());
        c.setApproved(approver, now);
        if (needsSecurity) {
            c.setCoApprovedBy(given.stream().map(Docs::map).filter(a -> "SECURITY_OPS".equals(a.get("role"))).map(a -> Docs.str(a.get("by")))
                    .findFirst().orElse(null));
        }
        c.setStatus(Configuration.CERTIFIED);
        String approvalRef = approvals.pending("CONFIGURATION", c.configurationId()).map(a -> a.id().toString()).orElse(null);
        Map<String, Object> record = certifications.certify(c, gates(c), approvalRef);
        c.setStep(7, "VALID", Docs.of("certificationId", record.get("certificationId"), "approvedBy", approver, "coApprovedBy", c.coApprovedBy(),
                "approvedAt", now.toString()), null, user.username(), now);
        c.setCurrentStep(8);
        store.update(c, user.username());
        approvals.pending("CONFIGURATION", c.configurationId()).ifPresent(a -> approvals.decide(a.id(), "APPROVED", user.username(), note));
        support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "APPROVE", 7,
                Docs.of("certificationId", record.get("certificationId")), approvalRef);
        support.audit("DEVICE_CONFIG_APPROVE", "DEVICE_CONFIGURATION", c.configurationId(), Docs.str(record.get("certificationId")));
        support.notifyUser(c.submittedBy(), "device.certified", support.params(c.configurationId(), c.siteCode()));
        support.securityEvent("CONFIG_CHANGE_APPROVED", DeviceCodes.Severity.INFO, c.endpointId(), c.deviceInstanceId(), c.siteCode(),
                user.username(), "approved", Map.of());
        return view(c);
    }

    @Transactional
    public Map<String, Object> reject(CurrentUser user, String id, String reason) {
        support.requireEnabled();
        Configuration c = store.requireLatest(id);
        if (!Configuration.AWAITING_APPROVAL.equals(c.status())) {
            throw ApiException.conflict("invalid_status", "The configuration is not awaiting approval");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("validation_failed", "a reason is required");
        }
        if (!user.hasAnyRole("DEVICE_APPROVER", "SECURITY_OPS")) {
            throw ApiException.forbidden("Only an approver can reject");
        }
        Instant now = support.now();
        c.setStatus(Configuration.DRAFT);
        c.setStep(7, "INVALID", Docs.of("rejectedBy", user.username(), "rejectedAt", now.toString(), "reason", trim(reason, 1000)),
                List.of(Docs.of("field", "approval", "code", "rejected", "message", trim(reason, 300))), user.username(), now);
        c.data().remove("approvals");
        c.setCurrentStep(6);
        store.update(c, user.username());
        approvals.pending("CONFIGURATION", c.configurationId()).ifPresent(a -> approvals.decide(a.id(), "REJECTED", user.username(), trim(reason, 1000)));
        support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "REJECT", 7, Docs.of("reason", trim(reason, 300)), null);
        support.audit("DEVICE_CONFIG_REJECT", "DEVICE_CONFIGURATION", c.configurationId());
        return view(c);
    }

    public List<String> gates(Configuration c) {
        return WizardRules.gates(manifestOf(c), Docs.strings(c.common().get("qualityGateAdditions")));
    }

    // ------------------------------------------------------------------ step 8: publish, clone, revise

    @Transactional
    public Map<String, Object> publish(CurrentUser user, String id) {
        support.requireEnabled();
        Configuration c = store.requireLatest(id);
        requireWriter(user, c.siteCode());
        if (c.paused()) {
            throw ApiException.conflict("configuration_paused", "The configuration is paused – resume it first");
        }
        if (!Configuration.CERTIFIED.equals(c.status())) {
            throw ApiException.conflict("invalid_status", "Only a CERTIFIED configuration can be published");
        }
        if (!certifications.exists(c.configurationId(), c.version())) {
            throw E32Support.unprocessable("certification_missing", "The certification record is missing");
        }
        if (!step6Issues(c).isEmpty()) {
            throw E32Support.unprocessable("test_evidence_missing", "Publication is blocked until the test evidence is complete");
        }
        Optional<Configuration> previous = store.live(c.deviceInstanceId());
        String kind = OptionRules.endpointKind(c.option(), packs.endpointKind(c.option()), c.parameters());
        String existing = OptionRules.hostEndpoint(c.option(), c.parameters());
        if (existing == null && previous.isPresent()) {
            existing = previous.get().endpointId();
        }
        DeviceEndpointProvisioning.EndpointIssue issue = null;
        if (kind != null) {
            String zone = c.zone() == null ? "DEVICE_VLAN" : c.zone();
            // the step-3 zone is the device's network segment; the endpoint's zone follows the E34 policy for its kind (contract
            // §15.4) – passing the device VLAN made E34 refuse DEVICE endpoints (P8) with zone_violation and mis-zoned gateways
            issue = support.spis().provisioning().provision(user.username(), new DeviceEndpointProvisioning.ProvisionRequest(c.deviceInstanceId(),
                    DeviceCodes.EndpointKind.valueOf(kind), c.siteCode(), null, c.tenantCode(), existing));
            c.setEndpointId(issue.endpointId());
            c.setEndpointKind(kind);
            c.setZone(zone);
        }
        Instant now = support.now();
        if (previous.isPresent()) {
            Configuration p = previous.get();
            p.setStatus(Configuration.SUPERSEDED);
            p.setStatusReason("SUPERSEDED_BY_V" + c.version());
            store.update(p, user.username());
        }
        c.setStatus(Configuration.PUBLISHED);
        c.setStatusReason(null);
        c.setPublished(user.username(), now);
        c.setStep(8, "VALID", Docs.of("publishedBy", user.username(), "publishedAt", now.toString(), "endpointId", c.endpointId()), null,
                user.username(), now);
        store.update(c, user.username());
        support.jdbc().update("update devicecat.fleet_alert set status = 'CLOSED', closed_at = ? where device_instance_id = ? and alert_type in ('QUARANTINED','DRIVER_REVOKED') and status <> 'CLOSED'",
                java.sql.Timestamp.from(now), c.deviceInstanceId());
        support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "PUBLISH", 8,
                Docs.of("endpointId", c.endpointId(), "supersedes", previous.map(Configuration::version).orElse(null)), null);
        support.audit("DEVICE_CONFIG_PUBLISH", "DEVICE_CONFIGURATION", c.configurationId(), c.deviceInstanceId());
        Map<String, Object> out = new LinkedHashMap<>(Docs.of("configurationId", c.configurationId(), "deviceInstanceId", c.deviceInstanceId(),
                "configVersion", c.version(), "status", c.status(), "endpointId", c.endpointId()));
        if (issue != null && issue.enrolmentToken() != null) {
            out.put("enrolmentToken", issue.enrolmentToken()); // shown once, never stored by the console
            out.put("enrolmentExpiresAt", issue.enrolmentExpiresAt());
        }
        return out;
    }

    @Transactional
    public Map<String, Object> cloneTo(CurrentUser user, String id, CloneCommand cmd) {
        support.requireEnabled();
        Configuration src = store.requireLatest(id);
        requireWriter(user, cmd.siteCode());
        if (Configuration.DECOMMISSIONED.equals(src.status())) {
            throw ApiException.conflict("invalid_status", "A decommissioned configuration cannot be cloned");
        }
        for (int k = 1; k <= 4; k++) {
            if (!"VALID".equals(src.stepStatus(k))) {
                throw new FieldIssue.Invalid("step_invalid", "Only configurations with steps 1–4 complete can be cloned",
                        List.of(new FieldIssue("step" + k, "step_incomplete", WizardRules.stepName(k) + " is not complete")));
            }
        }
        StationStore.Station st = stations.find(cmd.stationCode()).orElseThrow(() -> new FieldIssue.Invalid("step_invalid", "Unknown station",
                List.of(new FieldIssue("stationCode", "unknown_station", "The station does not exist"))));
        if (cmd.siteCode() != null && !cmd.siteCode().equals(st.siteCode())) {
            throw new FieldIssue.Invalid("step_invalid", "Station / site mismatch",
                    List.of(new FieldIssue("stationCode", "station_site_mismatch", "The station belongs to another site")));
        }
        Configuration c = ConfigurationStore.copyOf(src, support.nextConfigurationId(), 1, support.nextInstanceId(), user.username());
        ConfigurationStore.markCloned(c, src.configurationId());
        c.setSiteCode(st.siteCode());
        c.setStationCode(st.stationCode());
        c.setRoomCode(st.roomCode());
        c.setEndpointId(null);
        c.setEndpointKind(null);
        c.setEquipmentDeviceId(null);
        c.setSerial(null);
        c.setUdi(null);
        c.data().remove("approvals");
        c.data().remove("decommissionRequest");
        c.data().remove("disposal");
        c.data().remove("nurseReview");
        c.data().remove("simulatorOnlyJustification");
        c.data().put("tests", new ArrayList<>());
        c.data().put("stepEditors", new ArrayList<>(List.of(user.username())));
        Instant now = support.now();
        Map<String, Object> s5 = Docs.copyMap(src.stepData(5));
        s5.put("siteCode", st.siteCode());
        s5.put("stationCode", st.stationCode());
        s5.put("roomCode", st.roomCode());
        c.setStep(5, "IN_PROGRESS", s5, List.of(Docs.of("field", "stationCode", "code", "revalidate", "message", "Confirm the bindings for the new station")),
                user.username(), now);
        for (int k = 6; k <= 8; k++) {
            c.setStep(k, "NOT_STARTED", new LinkedHashMap<>(), null, user.username(), now);
        }
        c.setCurrentStep(5);
        store.insert(c);
        support.history("CONFIGURATION", c.configurationId(), 1, user.username(), "CLONE", 5,
                Docs.of("clonedFrom", src.configurationId(), "stationCode", st.stationCode()), null);
        support.audit("DEVICE_CONFIG_CREATE", "DEVICE_CONFIGURATION", c.configurationId(), "clone of " + src.configurationId());
        return view(c);
    }

    @Transactional
    public Map<String, Object> revise(CurrentUser user, String id) {
        support.requireEnabled();
        Configuration latest = store.requireLatest(id);
        requireWriter(user, latest.siteCode());
        if (!latest.live()) {
            throw ApiException.conflict(Configuration.DRAFT.equals(latest.status()) || Configuration.AWAITING_APPROVAL.equals(latest.status())
                    || Configuration.CERTIFIED.equals(latest.status()) ? "revision_open" : "invalid_status",
                    "Only a published, suspended or quarantined configuration can be revised (one open revision at a time)");
        }
        Configuration c = ConfigurationStore.copyOf(latest, latest.configurationId(), latest.version() + 1, latest.deviceInstanceId(), user.username());
        ConfigurationStore.markCloned(c, latest.clonedFrom());
        c.data().remove("approvals");
        c.data().remove("nurseReview");
        c.data().remove("simulatorOnlyJustification");
        c.data().remove("postureException");
        c.data().put("tests", new ArrayList<>());
        c.data().put("stepEditors", new ArrayList<>());
        Instant now = support.now();
        for (int k = 6; k <= 8; k++) {
            c.setStep(k, "NOT_STARTED", new LinkedHashMap<>(), null, user.username(), now);
        }
        c.setCurrentStep(4);
        store.insert(c);
        support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "REVISE", null,
                Docs.of("fromVersion", latest.version(), "fromStatus", latest.status()), null);
        support.audit("DEVICE_CONFIG_REVISE", "DEVICE_CONFIGURATION", c.configurationId(), "v" + c.version());
        return view(c);
    }

    // ------------------------------------------------------------------ pause / resume, suspend / resume service

    @Transactional
    public Map<String, Object> pause(CurrentUser user, String id, boolean paused) {
        support.requireEnabled();
        Configuration c = store.requireLatest(id);
        if (FINAL.contains(c.status())) {
            throw ApiException.conflict("invalid_status", "A final configuration cannot be paused");
        }
        c.setPaused(paused);
        store.update(c, user.username());
        support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), paused ? "PAUSE" : "RESUME", null, null, null);
        support.audit("DEVICE_CONFIG_STEP", "DEVICE_CONFIGURATION", c.configurationId(), paused ? "paused" : "resumed");
        return view(c);
    }

    @Transactional
    public Map<String, Object> suspend(CurrentUser user, String id, String reason, boolean suspend) {
        support.requireEnabled();
        Configuration c = store.live(store.requireLatest(id).deviceInstanceId()).orElseThrow(() -> ApiException.notFound("Published configuration"));
        if (suspend) {
            if (!Configuration.PUBLISHED.equals(c.status())) {
                throw ApiException.conflict("invalid_status", "Only a published configuration can be suspended");
            }
            c.setStatus(Configuration.SUSPENDED);
            c.setStatusReason(reason == null ? "MANUAL" : trim(reason, 200));
        } else {
            if (!Configuration.SUSPENDED.equals(c.status())) {
                throw ApiException.conflict("invalid_status", "Only a suspended configuration can resume service");
            }
            DriverStore.DriverRow d = drivers.require(c.driverPackageId(), c.driverVersion());
            if ("REVOKED".equals(d.status())) {
                throw ApiException.conflict("recertification_required", "The pinned driver was revoked – revise the configuration to a valid driver");
            }
            c.setStatus(Configuration.PUBLISHED);
            c.setStatusReason(null);
        }
        store.update(c, user.username());
        support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), suspend ? "SUSPEND" : "RESUME_SERVICE", null,
                Docs.of("reason", c.statusReason()), null);
        support.audit("DEVICE_CONFIG_STEP", "DEVICE_CONFIGURATION", c.configurationId(), suspend ? "suspended" : "resumed service");
        return view(c);
    }

    // ------------------------------------------------------------------ decommissioning (four-eyes, revocation)

    @Transactional
    public Map<String, Object> requestDecommission(CurrentUser user, String id, DecommissionCommand cmd) {
        support.requireEnabled();
        Configuration c = store.live(store.requireLatest(id).deviceInstanceId()).orElseThrow(() -> ApiException.notFound("Published configuration"));
        if (cmd.reason() == null || cmd.reason().isBlank()) {
            throw ApiException.badRequest("validation_failed", "a reason is required");
        }
        if (!List.of("DISPOSED", "TRANSFERRED", "RETURNED_TO_VENDOR", "STORED").contains(cmd.disposal())) {
            throw ApiException.badRequest("validation_failed", "disposal must be DISPOSED, TRANSFERRED, RETURNED_TO_VENDOR or STORED");
        }
        if ("TRANSFERRED".equals(cmd.disposal()) && (cmd.transferTo() == null || cmd.transferTo().isBlank())) {
            throw ApiException.badRequest("validation_failed", "transferTo is required for a transfer");
        }
        if (approvals.pending("DECOMMISSION", c.configurationId()).isPresent()) {
            throw ApiException.conflict("decommission_pending", "A decommissioning request is already awaiting approval");
        }
        Map<String, Object> req = Docs.of("reason", trim(cmd.reason(), 500), "disposal", cmd.disposal(), "transferTo", trim(cmd.transferTo(), 200),
                "requestedBy", user.username(), "requestedAt", support.now().toString(), "version", c.version());
        UUID approvalId = approvals.open("DECOMMISSION", c.configurationId(), c.version(), user.username(), req);
        req.put("approvalId", approvalId.toString());
        c.data().put("decommissionRequest", req);
        store.update(c, user.username());
        support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "DECOMMISSION_REQUESTED", null, req, approvalId.toString());
        support.audit("DEVICE_DECOMMISSION", "DEVICE_CONFIGURATION", c.configurationId(), "requested");
        support.notifyRole("DEVICE_APPROVER", "device.approval-required", support.params(c.configurationId(), c.siteCode()), user.username());
        return view(c);
    }

    @Transactional
    public Map<String, Object> decideDecommission(CurrentUser user, String id, boolean approve, String note) {
        support.requireEnabled();
        Configuration c = store.live(store.requireLatest(id).deviceInstanceId()).orElseThrow(() -> ApiException.notFound("Published configuration"));
        ApprovalStore.Approval a = approvals.pending("DECOMMISSION", c.configurationId())
                .orElseThrow(() -> ApiException.conflict("invalid_status", "No decommissioning request is awaiting approval"));
        if (user.username().equals(a.requestedBy())) {
            support.auditDenied("DEVICE_DECOMMISSION", "DEVICE_CONFIGURATION", c.configurationId(), "four_eyes_violation");
            throw ApiException.conflict("four_eyes_violation", "The requester cannot approve the decommissioning");
        }
        if (!user.hasRole("DEVICE_APPROVER")) {
            throw ApiException.forbidden("Only a device approver can decide on decommissioning");
        }
        Instant now = support.now();
        if (!approve) {
            approvals.decide(a.id(), "REJECTED", user.username(), trim(note, 1000));
            c.data().remove("decommissionRequest");
            store.update(c, user.username());
            support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "DECOMMISSION_REJECTED", null, null, a.id().toString());
            return view(c);
        }
        if (c.endpointId() != null) {
            support.spis().provisioning().revoke(user.username(), c.endpointId(), c.deviceInstanceId(), "DECOMMISSIONED");
        }
        List<String> destroyed = new ArrayList<>();
        for (Object ref : c.secrets().values()) {
            String r = Docs.str(ref);
            if (r != null && !sharedSecret(r, c.configurationId())) {
                support.spis().secrets().destroy(user.username(), r);
                destroyed.add(r);
            }
        }
        Map<String, Object> req = Docs.map(c.data().get("decommissionRequest"));
        Map<String, Object> disposal = Docs.of("reason", req.get("reason"), "disposal", req.get("disposal"), "transferTo", req.get("transferTo"),
                "requestedBy", a.requestedBy(), "approvedBy", user.username(), "decommissionedAt", now.toString(),
                "endpointRevoked", c.endpointId(), "secretsDestroyed", destroyed.size(), "bindingsRemoved", c.bindings().size(),
                "retentionClass", "DEVICE_CONFIGURATION", "deviceDataRetention", "per data class (DEVICE_RAW_PAYLOAD / OH record)");
        for (Configuration v : store.versions(c.configurationId())) {
            if (!FINAL.contains(v.status())) {
                v.data().put("disposal", disposal);
                v.stepData(5).put("bindings", new ArrayList<>());
                v.stepData(5).put("formCodes", new ArrayList<>());
                v.secrets().clear();
                v.setStatus(Configuration.DECOMMISSIONED);
                v.setStatusReason("DECOMMISSIONED");
                v.setPaused(false);
                store.update(v, user.username());
            }
        }
        approvals.decide(a.id(), "APPROVED", user.username(), trim(note, 1000));
        support.jdbc().update("update devicecat.fleet_alert set status = 'CLOSED', closed_at = ? where device_instance_id = ? and status <> 'CLOSED'",
                java.sql.Timestamp.from(now), c.deviceInstanceId());
        support.history("CONFIGURATION", c.configurationId(), c.version(), user.username(), "DECOMMISSION", null, disposal, a.id().toString());
        support.audit("DEVICE_DECOMMISSION", "DEVICE_CONFIGURATION", c.configurationId(), Docs.str(req.get("disposal")));
        support.securityEvent("DEVICE_DECOMMISSIONED", DeviceCodes.Severity.INFO, c.endpointId(), c.deviceInstanceId(), c.siteCode(), user.username(),
                "decommissioned", Map.of());
        return view(store.requireLatest(c.configurationId()));
    }

    private boolean sharedSecret(String ref, String exceptConfiguration) {
        for (Configuration other : store.latestAll()) {
            if (!other.configurationId().equals(exceptConfiguration) && !FINAL.contains(other.status()) && other.secrets().containsValue(ref)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ history, data flow, certification

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String id) {
        support.requireEnabled();
        store.requireLatest(id);
        return support.jdbc().query("""
                select actor, action, step, version, diff, approval_ref, at from devicecat.change_history
                where entity_type = 'CONFIGURATION' and entity_id = ? order by at, id""",
                (rs, i) -> Docs.of("actor", rs.getString("actor"), "action", rs.getString("action"), "step", rs.getObject("step"),
                        "version", rs.getObject("version"), "diff", support.readList(rs.getString("diff") != null && rs.getString("diff").startsWith("[")
                                ? rs.getString("diff") : null), "detail", rs.getString("diff") != null && rs.getString("diff").startsWith("{")
                                ? support.readMap(rs.getString("diff")) : null,
                        "approvalRef", rs.getString("approval_ref"), "at", rs.getTimestamp("at").toInstant()), id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> dataFlow(String id) {
        support.requireEnabled();
        Configuration c = store.requireLatest(id);
        return certifications.dataFlow(c, gates(c));
    }

    // ------------------------------------------------------------------ templates, batches and environment imports

    /**
     * Batch provisioning from a site / family template (E32-BL-010): creates a DRAFT with steps 1–2 done and the template's
     * parameters and bindings stored as defaults for steps 4 and 5.
     */
    public Configuration createFromTemplate(CurrentUser user, UUID templateId, String modelCode, String option, String stationCode,
                                            Map<String, Object> parameters, List<Object> bindings) {
        StationStore.Station st = stations.find(stationCode).orElseThrow(() -> E32Support.unprocessable("unknown_station", "Unknown station " + stationCode));
        Configuration c = ConfigurationStore.draft(support.nextConfigurationId(), 1, support.nextInstanceId(), modelCode, user.username());
        c.setSiteCode(st.siteCode());
        c.setTemplateId(templateId);
        applyStep1(c, Docs.of("modelCode", modelCode), user.username());
        applyStep2(c, Docs.of("option", option), user.username());
        c.data().put("templateDefaults", Docs.of("parameters", parameters == null ? Map.of() : Docs.copy(parameters),
                "bindings", bindings == null ? List.of() : Docs.copy(bindings), "stationCode", st.stationCode()));
        store.insert(c);
        support.history("CONFIGURATION", c.configurationId(), 1, user.username(), "CREATE_FROM_TEMPLATE", 2,
                Docs.of("templateId", templateId == null ? null : templateId.toString(), "stationCode", stationCode), null);
        support.audit("DEVICE_CONFIG_CREATE", "DEVICE_CONFIGURATION", c.configurationId(), "template");
        return c;
    }

    /**
     * Environment promotion (contract §13.4): an approved import becomes a DRAFT version at step 6 (test in the target
     * environment required) – or at step 4 when secrets must be re-bound. Secret values never travel; references are dropped.
     */
    public Configuration importDraft(String actor, String targetConfigurationId, Map<String, Object> snapshot, String importRef) {
        Optional<Configuration> existing = targetConfigurationId == null ? Optional.empty() : store.latest(targetConfigurationId);
        Configuration c;
        if (existing.isPresent()) {
            Configuration latest = existing.get();
            if (!latest.live()) {
                throw ApiException.conflict("revision_open", "Configuration " + latest.configurationId() + " already has an open version");
            }
            c = ConfigurationStore.copyOf(latest, latest.configurationId(), latest.version() + 1, latest.deviceInstanceId(), actor);
        } else {
            c = ConfigurationStore.draft(support.nextConfigurationId(), 1, support.nextInstanceId(), Docs.str(snapshot.get("modelCode")), actor);
            c.data().clear();
        }
        ModelStore.ModelRow m = models.require(Docs.str(snapshot.get("modelCode")));
        c.setModelCode(m.modelCode());
        c.setOption(Docs.str(snapshot.get("option")));
        c.setSiteCode(Docs.str(snapshot.get("siteCode")));
        c.setStationCode(Docs.str(snapshot.get("stationCode")));
        c.setRoomCode(Docs.str(snapshot.get("roomCode")));
        c.setFirmware(Docs.str(snapshot.get("firmware")));
        c.setSoftwareVersion(Docs.str(snapshot.get("softwareVersion")));
        Map<String, Object> driver = Docs.map(snapshot.get("driver"));
        c.setDriverPackageId(Docs.str(driver.get("packageId")));
        c.setDriverVersion(Docs.str(driver.get("version")));
        Instant now = support.now();
        c.data().remove("approvals");
        c.data().remove("nurseReview");
        c.data().remove("decommissionRequest");
        c.data().put("tests", new ArrayList<>());
        c.data().put("stepEditors", new ArrayList<>());
        c.data().put("importedFrom", importRef);
        c.setStep(1, "VALID", Docs.of("modelCode", m.modelCode(), "lifecycle", m.lifecycle(), "posture", m.posture()), null, actor, now);
        c.setStep(2, "VALID", Docs.of("option", c.option(), "driver", driver), null, actor, now);
        List<Map<String, Object>> items = WizardRules.prerequisites(packs.prerequisites(c.option()), m.family(), m.highConfidence(), c.option());
        items.forEach(i -> {
            if (!"NOT_APPLICABLE".equals(i.get("status"))) {
                i.put("status", "DONE");
                i.put("evidence", Docs.of("reference", importRef, "text", "Carried over by environment promotion"));
            }
        });
        c.setStep(3, "VALID", Docs.of("items", items), null, actor, now);
        Map<String, Object> params = Docs.copyMap(Docs.map(snapshot.get("parameters")));
        List<String> rebind = new ArrayList<>();
        for (String path : ParameterValidator.secretPaths(packs.parameterSchema(c.option()))) {
            if (Docs.at(params, path) != null) {
                Docs.put(params, path, null);
                rebind.add(path);
            }
        }
        c.secrets().clear();
        c.setStep(4, rebind.isEmpty() ? "VALID" : "INVALID", Docs.of("parameters", params, "common", Docs.copy(snapshot.get("common"))),
                rebind.isEmpty() ? null : List.of(Docs.of("field", "secrets", "code", "secret_rebind_required",
                        "message", "Secrets do not travel between environments – enter them again")), actor, now);
        c.setStep(5, "VALID", Docs.of("associationMethods", Docs.copy(snapshot.get("associationMethods")), "siteCode", c.siteCode(),
                "roomCode", c.roomCode(), "stationCode", c.stationCode(), "services", Docs.copy(snapshot.get("services")),
                "formCodes", Docs.copy(snapshot.get("formCodes")), "bindings", Docs.copy(snapshot.get("bindings"))), null, actor, now);
        for (int k = 6; k <= 8; k++) {
            c.setStep(k, "NOT_STARTED", new LinkedHashMap<>(), null, actor, now);
        }
        if (rebind.isEmpty()) {
            c.data().remove("secretsToRebind");
            c.setCurrentStep(6);
        } else {
            c.data().put("secretsToRebind", rebind);
            c.setCurrentStep(4);
        }
        c.setStatus(Configuration.DRAFT);
        store.insert(c);
        support.history("CONFIGURATION", c.configurationId(), c.version(), actor, "IMPORT", null, Docs.of("importRef", importRef), importRef);
        return c;
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
