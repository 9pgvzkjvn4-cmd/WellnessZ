package com.wellnessz.e32.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wellnessz.casekernel.device.DeviceCodes.Category;
import com.wellnessz.casekernel.device.DeviceCodes.Family;
import com.wellnessz.e32.driver.DriverPackageRules;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.FieldIssue;

/**
 * The rules of the eight-step provisioning wizard (E32-HLR-02, contract §13.2) that do not need the database: step names,
 * invalidation of later steps, the four-eyes rule, the prerequisites checklist per option and model, association-method
 * and binding validation, and the "add or tighten, never relax" rule for quality gates, verification and QC schedules.
 */
public final class WizardRules {

    public static final List<String> STEP_NAMES = List.of("Select device", "Choose connectivity option", "Prerequisites checklist",
            "Configure parameters", "Associate & bind", "Test", "Certify & approve", "Publish & monitor");
    public static final Set<String> ASSOCIATION_METHODS = Set.of("WORKLIST", "SESSION", "BADGE", "VENDOR_ID_MATCH");
    public static final Set<Category> RESTRICTED = Set.of(Category.BREATH_ALCOHOL, Category.DRUG_SCREEN);
    /** Families whose results depend on calibration evidence (E14 register or attested calibration). */
    public static final Set<Family> CALIBRATED = Set.of(Family.SPIROMETRY, Family.AUDIOMETRY, Family.VITALS, Family.CARDIOLOGY, Family.VISION,
            Family.POC, Family.DRUG_ALCOHOL, Family.RESP_ERGONOMICS, Family.EXPOSURE);
    public static final Set<Family> CONSUMABLE_FAMILIES = Set.of(Family.POC, Family.DRUG_ALCOHOL, Family.SPIROMETRY, Family.RESP_ERGONOMICS);
    /** Options whose results are always verified by a clinician before posting. */
    public static final Set<String> ALWAYS_VERIFY = Set.of("P9", "P10");

    private static final Map<String, String[]> PREREQ_TEXT = Map.ofEntries(
            Map.entry("NETWORK_ZONE", new String[] {"Network zone and firewall change",
                "Record the network zone (normally DEVICE_VLAN) and the firewall change reference for the device-to-gateway conduit."}),
            Map.entry("ENDPOINT_IDENTITY", new String[] {"Endpoint identity enrolled",
                "The gateway, Device Host agent, tablet or data manager must be enrolled in the security console (checked automatically)."}),
            Map.entry("VENDOR_SOFTWARE_VERSION", new String[] {"Vendor software version",
                "Confirm the installed vendor software version is one the driver package supports."}),
            Map.entry("DRIVERS_INSTALLED", new String[] {"Drivers / agent installed",
                "USB or serial drivers and the Device Host agent are installed on the station PC."}),
            Map.entry("PAIRING_CREDENTIALS", new String[] {"Pairing credentials",
                "Pairing passkey or numeric comparison prepared; the bond owner is ready."}),
            Map.entry("CERTIFICATES", new String[] {"TLS certificates",
                "Device or vendor-gateway certificate issued by the group PKI and installed (mutual TLS)."}),
            Map.entry("CONSUMABLES", new String[] {"Consumables and lots",
                "Mouthpieces, filters, cartridges, reagent and QC lots in stock and in date."}),
            Map.entry("CALIBRATION_EVIDENCE", new String[] {"Calibration evidence",
                "The device is linked to its equipment-register record with a valid calibration (checked automatically)."}),
            Map.entry("VENDOR_POSTURE", new String[] {"Vendor security posture",
                "The model's security posture is acceptable or covered by an approved exception (checked automatically)."}),
            Map.entry("INTERFACE_SPEC_CONFIRMED", new String[] {"Interface specification confirmed",
                "The catalogue entry is not 'High' confidence: confirm the interface specification with the vendor."}),
            Map.entry("VENDOR_CLOUD_AGREEMENT", new String[] {"Vendor cloud agreement",
                "Data-processing agreement in place and the vendor's hosting region matches the tenant's residency."}));
    private static final Set<String> AUTO = Set.of("ENDPOINT_IDENTITY", "CALIBRATION_EVIDENCE", "VENDOR_POSTURE");

    private WizardRules() {
    }

    public static String stepName(int n) {
        return STEP_NAMES.get(n - 1);
    }

    /**
     * Invalidates steps {@code from..8} after an earlier step changed. With {@code reset} the step payloads are cleared
     * (e.g. a new option resets 3–8); otherwise valid steps become {@code INVALID} and keep their data for re-confirmation.
     */
    public static void invalidateFrom(Configuration c, int from, boolean reset, String actor, Instant at) {
        for (int n = from; n <= 8; n++) {
            String st = c.stepStatus(n);
            if (reset) {
                c.setStep(n, "NOT_STARTED", new LinkedHashMap<>(), null, actor, at);
            } else if ("VALID".equals(st) || "IN_PROGRESS".equals(st)) {
                c.setStep(n, "INVALID", null, List.of(Docs.of("field", "step", "code", "revalidate",
                        "message", "An earlier step changed – review and save this step again")), actor, at);
            }
        }
        if (from <= 6) {
            c.tests().clear();
            c.data().remove("nurseReview");
        }
        if (c.currentStep() > from) {
            c.setCurrentStep(from);
        }
    }

    /** Four-eyes (contract §13.2 step 7): the approver is neither the submitter nor anyone who changed steps 4–6. */
    public static boolean fourEyesViolation(Configuration c, String approver) {
        return approver.equals(c.submittedBy()) || c.stepEditors().contains(approver);
    }

    /** Generated prerequisites checklist (step 3). */
    public static List<Map<String, Object>> prerequisites(List<String> packItems, Family family, boolean highConfidence, String option) {
        Set<String> codes = new LinkedHashSet<>(packItems);
        if (CONSUMABLE_FAMILIES.contains(family)) {
            codes.add("CONSUMABLES");
        }
        if (!highConfidence) {
            codes.add("INTERFACE_SPEC_CONFIRMED");
        }
        codes.add("VENDOR_POSTURE");
        codes.add("CALIBRATION_EVIDENCE");
        List<Map<String, Object>> out = new ArrayList<>();
        for (String code : codes) {
            String[] text = PREREQ_TEXT.getOrDefault(code, new String[] {code, code});
            boolean notApplicable = "CALIBRATION_EVIDENCE".equals(code) && !CALIBRATED.contains(family);
            out.add(new LinkedHashMap<>(Docs.of("code", code, "label", text[0], "guidance", text[1], "kind", AUTO.contains(code) ? "AUTO" : "MANUAL",
                    "required", true, "status", notApplicable ? "NOT_APPLICABLE" : "OPEN", "option", option)));
        }
        return out;
    }

    public static boolean isAuto(String code) {
        return AUTO.contains(code);
    }

    /** Step 3 can be left only when every required item is DONE or NOT_APPLICABLE. */
    public static List<FieldIssue> prerequisiteIssues(List<Map<String, Object>> items) {
        List<FieldIssue> out = new ArrayList<>();
        for (Map<String, Object> i : items) {
            String st = Docs.str(i.get("status"));
            if (Docs.bool(i.get("required"), true) && !"DONE".equals(st) && !"NOT_APPLICABLE".equals(st)) {
                out.add(new FieldIssue("items." + i.get("code"), "prerequisite_open", i.get("label") + " is not complete"));
            }
        }
        return out;
    }

    /**
     * Association methods ⊆ driver capabilities: WORKLIST needs {@code worklistPush ≠ NONE}, VENDOR_ID_MATCH needs an employee
     * identifier in the message; restricted categories accept only WORKLIST or SESSION (contract §9).
     */
    public static List<FieldIssue> associationIssues(List<String> methods, Map<String, Object> manifest, Set<Category> categories) {
        List<FieldIssue> out = new ArrayList<>();
        if (methods.isEmpty()) {
            out.add(new FieldIssue("associationMethods", "required", "choose at least one association method"));
        }
        String push = Docs.str(Docs.at(manifest, "capabilities.worklistPush"));
        boolean hasEmployeeId = Docs.str(Docs.at(manifest, "parse.identity.employee")) != null;
        boolean restricted = categories.stream().anyMatch(RESTRICTED::contains);
        for (String m : methods) {
            if (!ASSOCIATION_METHODS.contains(m)) {
                out.add(new FieldIssue("associationMethods", "enum", "unknown association method " + m));
            } else if ("WORKLIST".equals(m) && (push == null || "NONE".equals(push)) && !restricted) {
                out.add(new FieldIssue("associationMethods", "worklist_not_supported", "the driver cannot receive a worklist (worklistPush NONE)"));
            } else if ("VENDOR_ID_MATCH".equals(m) && !hasEmployeeId) {
                out.add(new FieldIssue("associationMethods", "vendor_id_not_supported", "the device messages carry no employee identifier"));
            }
            if (restricted && !"WORKLIST".equals(m) && !"SESSION".equals(m)) {
                out.add(new FieldIssue("associationMethods", "restricted_association",
                        "breath-alcohol and drug-screen results accept only worklist (test id) or capture-session association"));
            }
        }
        return out;
    }

    /** Instance bindings: keys are package mapping keys, targets known, verification only tightened. */
    public static List<FieldIssue> bindingIssues(List<Object> bindings, Map<String, Object> manifest, String option) {
        List<FieldIssue> out = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(DriverPackageRules.DERIVED_KEYS);
        Map<String, Map<String, Object>> defaults = new LinkedHashMap<>();
        for (Object o : Docs.list(manifest.get("mappings"))) {
            keys.add(Docs.str(Docs.map(o).get("key")));
        }
        for (Object o : Docs.list(manifest.get("bindings"))) {
            Map<String, Object> b = Docs.map(o);
            defaults.put(Docs.str(b.get("formCode")), b);
            keys.addAll(Docs.map(b.get("fields")).keySet());
        }
        if (bindings.isEmpty()) {
            out.add(new FieldIssue("bindings", "required", "at least one result-to-form binding is required"));
        }
        for (int i = 0; i < bindings.size(); i++) {
            Map<String, Object> b = Docs.map(bindings.get(i));
            String p = "bindings[" + i + "]";
            String formCode = Docs.str(b.get("formCode"));
            if (formCode == null || !DriverPackageRules.FORM_CODE.matcher(formCode).matches()) {
                out.add(new FieldIssue(p + ".formCode", "pattern", "form code must look like E03.DEVICE_RESULT.VITALS"));
            }
            String target = Docs.str(b.get("target"));
            if (!DriverPackageRules.TARGETS.contains(target)) {
                out.add(new FieldIssue(p + ".target", "unknown_target", "unknown routing target"));
            } else if (formCode != null && !formCode.startsWith(target.substring(0, 3))) {
                out.add(new FieldIssue(p + ".target", "target_mismatch", "the target module does not own this form"));
            }
            Map<String, Object> fields = Docs.map(b.get("fields"));
            if (fields.isEmpty()) {
                out.add(new FieldIssue(p + ".fields", "required", "map at least one result to a form field"));
            }
            for (String key : fields.keySet()) {
                if (!keys.contains(key)) {
                    out.add(new FieldIssue(p + ".fields." + key, "binding_field_unknown", "'" + key + "' is not a result key of the driver package"));
                }
            }
            Map<String, Object> def = defaults.get(formCode);
            boolean verify = Docs.bool(b.get("verifyBeforePost"), true);
            boolean mustVerify = ALWAYS_VERIFY.contains(option) || def != null && Docs.bool(def.get("verifyBeforePost"), true)
                    || RESTRICTED.stream().anyMatch(r -> r.name().equals(Docs.str(b.get("category"))));
            if (!verify && mustVerify) {
                out.add(new FieldIssue(p + ".verifyBeforePost", "cannot_relax", "verification before posting cannot be switched off for this form"));
            }
        }
        return out;
    }

    /** Completes instance bindings from the package defaults (category, version, gates). */
    public static List<Object> completeBindings(List<Object> bindings, Map<String, Object> manifest) {
        Map<String, Map<String, Object>> defaults = new LinkedHashMap<>();
        for (Object o : Docs.list(manifest.get("bindings"))) {
            Map<String, Object> b = Docs.map(o);
            defaults.put(Docs.str(b.get("formCode")), b);
        }
        List<Object> out = new ArrayList<>();
        for (Object o : bindings) {
            Map<String, Object> b = Docs.copyMap(Docs.map(o));
            Map<String, Object> def = defaults.getOrDefault(Docs.str(b.get("formCode")), Map.of());
            b.putIfAbsent("formVersion", def.getOrDefault("formVersion", "1"));
            b.putIfAbsent("category", def.get("category"));
            b.putIfAbsent("target", def.get("target"));
            b.putIfAbsent("verifyBeforePost", def.getOrDefault("verifyBeforePost", true));
            if (Docs.map(b.get("fields")).isEmpty() && def.get("fields") != null) {
                b.put("fields", Docs.copy(def.get("fields")));
            }
            out.add(b);
        }
        return out;
    }

    /** Common block rules: gates only added, verification only tightened, QC schedules only tightened. */
    public static List<FieldIssue> commonIssues(Map<String, Object> common, Map<String, Object> manifest, String option,
                                                Map<String, Integer> familyIntervals) {
        List<FieldIssue> out = new ArrayList<>();
        for (String g : Docs.strings(common.get("qualityGateAdditions"))) {
            if (!DriverPackageRules.GATES.contains(g.split(":")[0])) {
                out.add(new FieldIssue("common.qualityGateAdditions", "unknown_gate", "unknown quality gate " + g));
            }
        }
        boolean packageVerifies = Docs.list(manifest.get("bindings")).stream().anyMatch(b -> Docs.bool(Docs.map(b).get("verifyBeforePost"), true));
        if (!Docs.bool(common.get("verifyBeforePost"), true) && (packageVerifies || ALWAYS_VERIFY.contains(option))) {
            out.add(new FieldIssue("common.verifyBeforePost", "cannot_relax", "the driver package requires verification before posting"));
        }
        List<Object> schedule = Docs.list(common.get("calibrationSchedule"));
        for (int i = 0; i < schedule.size(); i++) {
            Map<String, Object> s = Docs.map(schedule.get(i));
            String type = Docs.str(s.get("checkType"));
            Integer base = familyIntervals.get(type);
            Integer interval = Docs.integer(s.get("intervalHours"));
            if (base != null && interval != null && interval > base) {
                out.add(new FieldIssue("common.calibrationSchedule[" + i + "].intervalHours", "cannot_relax",
                        "the interval can only be shortened (family schedule " + base + " h)"));
            }
            if (base != null && !Docs.bool(s.get("lockout"), true)) {
                out.add(new FieldIssue("common.calibrationSchedule[" + i + "].lockout", "cannot_relax", "lockout cannot be switched off"));
            }
        }
        return out;
    }

    /** Quality gates of a binding: package gates followed by the instance additions (before VERIFY_BEFORE_POST). */
    public static List<String> gates(Map<String, Object> manifest, List<String> additions) {
        List<String> base = new ArrayList<>(Docs.strings(manifest.get("qualityGates")));
        boolean verify = base.remove("VERIFY_BEFORE_POST");
        for (String a : additions) {
            if (!base.contains(a)) {
                base.add(a);
            }
        }
        if (verify) {
            base.add("VERIFY_BEFORE_POST");
        }
        return base;
    }
}
