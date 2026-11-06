package io.intimed.partner;

import io.intimed.catalogue.CatalogueService;
import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.console.intelligence.DocAssistant;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.harness.EvidenceSealer;
import io.intimed.harness.HarnessService;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.IntiPrincipal;
import io.intimed.security.auth.Role;
import io.intimed.security.secrets.SecretScanner;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Vendor self-service submission (IM-E02-BL-006). A device vendor's developer (role {@link Role#PARTNER_DEV}) submits in
 * one request: model metadata, the interface specification (text / Markdown / PDF, ≤ 5 MB, stored with its SHA-256 and
 * – for text – indexed by the documentation assistant), the MDS2 form and SBOM (CycloneDX or SPDX JSON) and a signed
 * driver package. The package goes through the unchanged {@link DriverRegistry#publish} path (Ed25519 signature of a
 * tenant-trusted publisher key, secret scan, structural validation) and lands in the CANDIDATE channel.
 *
 * <pre>
 *  SUBMITTED ──review (DEVICE_ADMIN / REGULATORY)──▶ IN_REVIEW ──passing harness run──▶ HARNESS_PASSED ──certify (four-eyes)──▶ CERTIFIED
 *      └──────────────── reject (reason) ───────────────┴─────────────────────────────────────┴──▶ REJECTED     (vendor: withdraw ▶ WITHDRAWN)
 * </pre>
 * <ul>
 *   <li>Starting the review feeds MDS2 + SBOM into the catalogue security posture of the model (reviewer-attested).</li>
 *   <li>The harness step references a sealed run of <i>this</i> driver (or runs a configuration that uses it); the run id
 *       and the evidence seal are recorded and the seal is verified.</li>
 *   <li>Four-eyes: the certifier must be neither the submitter nor the reviewer who recorded the harness result.</li>
 *   <li>Certification promotes the driver to the CERTIFIED channel and grants a badge ({@link BadgeService}).</li>
 *   <li>Vendors only ever see their own submissions (others answer 404); every action is audited on
 *       {@code partner-submission/<id>}.</li>
 * </ul>
 */
@Service
public class PartnerSubmissionService {
    public static final int MAX_SPEC_BYTES = 5 * 1024 * 1024;
    public static final int MAX_JSON_CHARS = 2 * 1024 * 1024;
    public static final Set<String> SPEC_TYPES = Set.of("text/plain", "text/markdown", "application/pdf");
    public static final Set<String> OPTIONS = Set.of("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9", "P10", "P11");
    private static final Pattern MODEL_ID = Pattern.compile("[A-Z0-9][A-Z0-9-]{2,40}");
    private static final Pattern DI = Pattern.compile("[A-Za-z0-9.+/\\-]{6,40}");
    private static final Role[] REVIEWERS = {Role.DEVICE_ADMIN, Role.REGULATORY};

    private final DocumentStore store;
    private final AuditService audit;
    private final DriverRegistry drivers;
    private final CatalogueService catalogue;
    private final DocAssistant docs;
    private final HarnessService harness;
    private final EvidenceSealer sealer;
    private final BadgeService badges;

    public PartnerSubmissionService(DocumentStore store, AuditService audit, DriverRegistry drivers, CatalogueService catalogue, DocAssistant docs,
                                    HarnessService harness, EvidenceSealer sealer, BadgeService badges) {
        this.store = store;
        this.audit = audit;
        this.drivers = drivers;
        this.catalogue = catalogue;
        this.docs = docs;
        this.harness = harness;
        this.sealer = sealer;
        this.badges = badges;
    }

    // ---------------------------------------------------------------- submit
    public ObjectNode submit(String tenant, JsonNode b, String actor) {
        Current.require(Role.PARTNER_DEV);
        String manufacturer = text(b, "manufacturer", 120, true);
        String model = text(b, "model", 120, true);
        String modelId = text(b, "modelId", 42, true);
        if (!MODEL_ID.matcher(modelId).matches()) throw ApiException.bad("invalid-model-id", "modelId must match " + MODEL_ID.pattern());
        String udi = text(b, "udiDi", 40, false);
        if (udi != null && !DI.matcher(udi).matches()) throw ApiException.bad("invalid-di", "udiDi must be 6–40 characters [A-Za-z0-9.+/-]");
        String description = text(b, "description", 2000, false);
        String family = text(b, "family", 80, false);
        List<String> options = new ArrayList<>();
        b.path("options").forEach(o -> { if (!OPTIONS.contains(o.asString())) throw ApiException.bad("invalid-option", "unknown connectivity option " + o.asString()); options.add(o.asString()); });
        // --- interface specification
        JsonNode specIn = b.path("interfaceSpec");
        String specName = text(specIn, "name", 160, true);
        String ctype = text(specIn, "contentType", 40, true);
        if (!SPEC_TYPES.contains(ctype)) throw ApiException.bad("invalid-spec-type", "interface specification must be one of " + SPEC_TYPES);
        byte[] spec;
        if (specIn.hasNonNull("contentBase64")) {
            String b64 = specIn.get("contentBase64").asString();
            if (b64.length() > (MAX_SPEC_BYTES / 3 + 2) * 4) throw ApiException.bad("spec-too-large", "interface specification exceeds 5 MB");
            try { spec = Base64.getDecoder().decode(b64.replaceAll("\\s", "")); } catch (IllegalArgumentException e) { throw ApiException.bad("invalid-base64", "contentBase64 is not valid Base64"); }
        } else if (specIn.hasNonNull("text")) {
            spec = specIn.get("text").asString().getBytes(StandardCharsets.UTF_8);
        } else throw ApiException.bad("spec-required", "interfaceSpec.text or interfaceSpec.contentBase64 is required");
        if (spec.length == 0 || spec.length > MAX_SPEC_BYTES) throw ApiException.bad("spec-too-large", "interface specification empty or larger than 5 MB");
        String specText = null;
        if ("application/pdf".equals(ctype)) {
            if (spec.length < 5 || !new String(spec, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) throw ApiException.bad("invalid-pdf", "content is not a PDF document");
        } else {
            try { specText = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(spec)).toString(); }
            catch (CharacterCodingException e) { throw ApiException.bad("invalid-text", "text specification must be UTF-8"); }
            if (!SecretScanner.scan(specText).isEmpty()) throw ApiException.unprocessable("spec-secret", "interface specification contains credentials – redact before submission");
        }
        // --- SBOM + MDS2
        JsonNode sbom = jsonDoc(b, "sbom");
        JsonNode mds2 = jsonDoc(b, "mds2");
        String sbomFormat;
        int components, vulns = 0;
        if ("CycloneDX".equals(sbom.path("bomFormat").asString())) {
            sbomFormat = "CycloneDX " + sbom.path("specVersion").asString("");
            if (!sbom.path("components").isArray()) throw ApiException.bad("invalid-sbom", "CycloneDX SBOM without components array");
            components = sbom.get("components").size();
            for (JsonNode v : sbom.path("vulnerabilities")) {
                String st = v.at("/analysis/state").asString("");
                if (!Set.of("not_affected", "false_positive", "resolved").contains(st)) vulns++;
            }
        } else if (sbom.has("spdxVersion")) {
            sbomFormat = sbom.path("spdxVersion").asString();
            if (!sbom.path("packages").isArray()) throw ApiException.bad("invalid-sbom", "SPDX SBOM without packages array");
            components = sbom.get("packages").size();
        } else throw ApiException.bad("invalid-sbom", "SBOM must be CycloneDX (bomFormat) or SPDX (spdxVersion) JSON");
        if (mds2.isEmpty()) throw ApiException.bad("invalid-mds2", "MDS2 form is empty");
        String eol = mds2.path("endOfSupport").asString(mds2.path("eol").asString(null));
        if (eol != null) {
            try { LocalDate.parse(eol); } catch (RuntimeException e) { throw ApiException.bad("invalid-mds2", "MDS2 endOfSupport must be YYYY-MM-DD"); }
        }
        // --- driver package (unchanged publish path: signature, secret scan, validation → CANDIDATE)
        JsonNode pkg = b.at("/driver/package");
        String sig = b.at("/driver/signature").asString(null);
        if (!pkg.isObject() || sig == null || sig.isBlank()) throw ApiException.bad("driver-required", "driver.package and driver.signature are required");
        boolean listed = false;
        for (JsonNode m : pkg.at("/manifest/models")) listed |= modelId.equals(m.asString());
        if (!listed) throw ApiException.bad("driver-model", "driver manifest.models must list " + modelId);
        boolean existing;
        try { catalogue.requireModel(tenant, modelId); existing = true; } catch (ApiException e) { existing = false; }
        DriverPackage d = drivers.publish(tenant, pkg, sig, actor);
        if (!existing) catalogue.registerTenantModel(tenant, modelId, manufacturer, model, family, options.isEmpty() ? d.options() : options, actor);
        // --- store artefacts and the submission
        String id = Hashing.id("sub");
        String specHash = Hashing.sha256(spec);
        ObjectNode art = Json.obj().put("submissionId", id).put("name", specName).put("contentType", ctype).put("size", spec.length).put("sha256", specHash)
                .put("contentBase64", Base64.getEncoder().encodeToString(spec));
        store.put(tenant, "partner-artefact", id + "-spec", art, "STORED", id);
        store.put(tenant, "partner-artefact", id + "-sbom", Json.obj().put("submissionId", id).put("kind", "SBOM").set("document", sbom), "STORED", id);
        store.put(tenant, "partner-artefact", id + "-mds2", Json.obj().put("submissionId", id).put("kind", "MDS2").set("document", mds2), "STORED", id);
        String docId = null;
        if (specText != null && specText.length() <= 2_000_000) docId = docs.upload(tenant, "Partner " + manufacturer + " " + model + ": " + specName, specText, actor).get("id").asString();
        ObjectNode s = Json.obj().put("id", id).put("status", "SUBMITTED").put("manufacturer", manufacturer).put("model", model).put("modelId", modelId)
                .put("newModel", !existing).put("udiDi", udi).put("family", family).put("description", description).put("driverRef", d.ref()).put("driverChannel", "CANDIDATE")
                .put("publisher", d.publisher()).put("submittedBy", actor).put("submittedAt", Instant.now().toString());
        s.set("options", Json.tree(options.isEmpty() ? d.options() : options));
        s.putObject("interfaceSpec").put("name", specName).put("contentType", ctype).put("size", spec.length).put("sha256", specHash).put("docId", docId);
        s.putObject("sbom").put("format", sbomFormat).put("components", components).put("vulnerabilities", vulns).put("sha256", Hashing.sha256(Json.canonical(sbom)));
        s.putObject("mds2").put("sha256", Hashing.sha256(Json.canonical(mds2))).put("endOfSupport", eol);
        history(s, actor, "submit", null, "SUBMITTED", null);
        store.put(tenant, "partner-submission", id, s, "SUBMITTED", actor);
        audit.record(tenant, actor, "partner.submission.create", target(id), Map.of("driver", d.ref(), "modelId", modelId, "specSha256", specHash, "sbomComponents", components));
        return s;
    }

    // ---------------------------------------------------------------- read
    public List<ObjectNode> list(String tenant) {
        IntiPrincipal p = Current.principal();
        if (p.hasAny(REVIEWERS) || p.hasAny(Role.AUDITOR)) return store.list(tenant, "partner-submission");
        Current.require(Role.PARTNER_DEV);
        return store.listByRef(tenant, "partner-submission", Current.actor());
    }

    /** One submission; a vendor only sees its own (404 otherwise – no enumeration oracle). */
    public ObjectNode get(String tenant, String id) {
        ObjectNode s = store.get(tenant, "partner-submission", id).orElseThrow(() -> ApiException.notFound("submission " + id));
        IntiPrincipal p = Current.principal();
        if (p.hasAny(REVIEWERS) || p.hasAny(Role.AUDITOR)) return s;
        if (p.hasAny(Role.PARTNER_DEV) && s.path("submittedBy").asString().equals(Current.actor())) return s;
        throw ApiException.notFound("submission " + id);
    }

    public ObjectNode interfaceSpec(String tenant, String id) {
        get(tenant, id);
        return store.require(tenant, "partner-artefact", id + "-spec");
    }

    public List<Map<String, Object>> auditTrail(String tenant, String id) {
        get(tenant, id);
        return audit.byTarget(tenant, target(id));
    }

    // ---------------------------------------------------------------- reviewer actions
    public ObjectNode startReview(String tenant, String id, String actor) {
        Current.require(REVIEWERS);
        ObjectNode s = require(tenant, id, "SUBMITTED");
        if (actor.equals(s.path("submittedBy").asString())) throw ApiException.forbidden("four-eyes", "the submitter cannot review their own submission");
        ObjectNode mds2 = store.require(tenant, "partner-artefact", id + "-mds2");
        ObjectNode pin = Json.obj().put("mds2", "RECEIVED").put("sbom", "RECEIVED").put("knownVulnerabilities", s.at("/sbom/vulnerabilities").asInt(0))
                .put("source", "PARTNER_SUBMISSION").put("submissionId", id).put("sbomComponents", s.at("/sbom/components").asInt(0));
        if (s.at("/mds2/endOfSupport").isString()) pin.put("eol", s.at("/mds2/endOfSupport").asString());
        ObjectNode posture = catalogue.recordPosture(tenant, s.get("modelId").asString(), pin, actor);
        s.put("reviewStartedBy", actor).put("postureStatus", posture.get("status").asString()).put("mds2Form", mds2.at("/document").size() + " entries");
        return transition(tenant, s, "IN_REVIEW", actor, "review", Map.of("posture", posture.get("status").asString()));
    }

    /** Records a harness result: an existing run id of this driver, or runs a configuration that uses the driver. */
    public ObjectNode recordHarness(String tenant, String id, JsonNode b, String actor) {
        Current.require(REVIEWERS);
        ObjectNode s = store.get(tenant, "partner-submission", id).orElseThrow(() -> ApiException.notFound("submission " + id));
        String st = s.path("status").asString();
        if (!"IN_REVIEW".equals(st) && !"HARNESS_PASSED".equals(st)) throw ApiException.conflict("invalid-status", "submission is " + st + "; a harness result can be recorded IN_REVIEW");
        String driver = s.get("driverRef").asString();
        JsonNode run;
        if (b.hasNonNull("configId")) {
            ObjectNode cfg = store.require(tenant, "config", b.get("configId").asString());
            if (!driver.equals(cfg.path("driver").asString())) throw ApiException.bad("driver-mismatch", "configuration does not use driver " + driver);
            run = harness.run(tenant, cfg.get("id").asString(), actor);
        } else if (b.hasNonNull("runId")) {
            run = store.require(tenant, "run", b.get("runId").asString());
        } else throw ApiException.bad("run-required", "runId or configId is required");
        if (!driver.equals(run.path("driver").asString())) throw ApiException.bad("driver-mismatch", "run " + run.path("id").asString() + " did not test driver " + driver);
        String runId = run.get("id").asString();
        if (!"PASSED".equals(run.path("status").asString())) {
            s.put("lastHarnessRun", runId).put("lastHarnessStatus", run.path("status").asString());
            history(s, actor, "harness", st, st, "run " + runId + " " + run.path("status").asString());
            store.put(tenant, "partner-submission", id, s, st, s.get("submittedBy").asString());
            audit.record(tenant, actor, "partner.submission.harness", target(id), Map.of("run", runId, "status", run.path("status").asString()));
            return s;
        }
        Map<String, Object> verify = sealer.verify(sealer.read(runId));
        if (!Boolean.TRUE.equals(verify.get("valid"))) throw ApiException.conflict("evidence-invalid", "evidence pack of run " + runId + " does not verify");
        s.put("harnessRunId", runId).put("evidenceSeal", run.path("seal").asString()).put("harnessChecks", run.path("passed").asInt() + "/" + run.path("total").asInt())
                .put("harnessRecordedBy", actor).put("lastHarnessRun", runId).put("lastHarnessStatus", "PASSED");
        return transition(tenant, s, "HARNESS_PASSED", actor, "harness", Map.of("run", runId, "seal", run.path("seal").asString()));
    }

    public ObjectNode certify(String tenant, String id, String comment, String actor) {
        Current.require(REVIEWERS);
        ObjectNode s = require(tenant, id, "HARNESS_PASSED");
        if (actor.equals(s.path("submittedBy").asString()) || actor.equals(s.path("harnessRecordedBy").asString())) {
            audit.record(tenant, actor, "partner.submission.certify.refused", target(id), Map.of("reason", "four-eyes"));
            throw ApiException.forbidden("four-eyes", "four-eyes rule: the certifier must differ from the submitter and from the reviewer who recorded the harness result");
        }
        String runId = s.get("harnessRunId").asString();
        ObjectNode run = store.require(tenant, "run", runId);
        if (!"PASSED".equals(run.path("status").asString())) throw ApiException.conflict("run-not-passed", "harness run is not PASSED");
        DriverPackage d = drivers.require(tenant, s.get("driverRef").asString());
        if ("WITHDRAWN".equals(d.channel())) throw ApiException.conflict("driver-withdrawn", "driver has been withdrawn");
        drivers.certify(tenant, d.ref(), runId, actor);
        ObjectNode badge = badges.grant(tenant, s, run, catalogue.posture(tenant, s.get("modelId").asString()), actor);
        s.put("certifiedBy", actor).put("certifiedAt", Instant.now().toString()).put("certificationComment", comment == null ? "" : comment.length() > 500 ? comment.substring(0, 500) : comment)
                .put("driverChannel", "CERTIFIED").put("badgeId", badge.get("id").asString());
        return transition(tenant, s, "CERTIFIED", actor, "certify", Map.of("badge", badge.get("id").asString(), "level", badge.get("level").asString()));
    }

    public ObjectNode reject(String tenant, String id, String reason, String actor) {
        Current.require(REVIEWERS);
        if (reason == null || reason.isBlank()) throw ApiException.bad("reason-required", "a rejection reason is required");
        ObjectNode s = store.get(tenant, "partner-submission", id).orElseThrow(() -> ApiException.notFound("submission " + id));
        String st = s.path("status").asString();
        if (!Set.of("SUBMITTED", "IN_REVIEW", "HARNESS_PASSED").contains(st)) throw ApiException.conflict("invalid-status", "submission is " + st);
        String r = reason.length() > 500 ? reason.substring(0, 500) : reason;
        s.put("rejectedBy", actor).put("rejectionReason", r).put("driverChannel", "WITHDRAWN");
        withdrawDriver(tenant, s.get("driverRef").asString(), "submission rejected", actor);
        return transition(tenant, s, "REJECTED", actor, "reject", Map.of("reason", r));
    }

    /** Vendor withdraws its own submission (a certified driver is withdrawn too – which revokes the badge). */
    public ObjectNode withdraw(String tenant, String id, String actor) {
        ObjectNode s = get(tenant, id);
        boolean own = s.path("submittedBy").asString().equals(actor);
        if (!own) Current.require(REVIEWERS);
        String st = s.path("status").asString();
        if (Set.of("REJECTED", "WITHDRAWN").contains(st)) throw ApiException.conflict("invalid-status", "submission is " + st);
        withdrawDriver(tenant, s.get("driverRef").asString(), "submission withdrawn", actor);
        s.put("driverChannel", "WITHDRAWN").put("withdrawnBy", actor);
        return transition(tenant, s, "WITHDRAWN", actor, "withdraw", Map.of());
    }

    private void withdrawDriver(String tenant, String ref, String reason, String actor) {
        drivers.find(tenant, ref).filter(d -> !"WITHDRAWN".equals(d.channel())).ifPresent(d -> drivers.withdraw(tenant, ref, reason, actor));
    }

    // ---------------------------------------------------------------- helpers
    private ObjectNode require(String tenant, String id, String status) {
        ObjectNode s = store.get(tenant, "partner-submission", id).orElseThrow(() -> ApiException.notFound("submission " + id));
        if (!status.equals(s.path("status").asString())) throw ApiException.conflict("invalid-status", "submission is " + s.path("status").asString() + ", expected " + status);
        return s;
    }

    private ObjectNode transition(String tenant, ObjectNode s, String to, String actor, String action, Map<String, Object> detail) {
        String from = s.path("status").asString();
        s.put("status", to).put("updatedAt", Instant.now().toString());
        history(s, actor, action, from, to, null);
        store.put(tenant, "partner-submission", s.get("id").asString(), s, to, s.get("submittedBy").asString());
        Map<String, Object> det = new java.util.LinkedHashMap<>(detail);
        det.put("from", from);
        det.put("to", to);
        audit.record(tenant, actor, "partner.submission." + action, target(s.get("id").asString()), det);
        return s;
    }

    private static void history(ObjectNode s, String actor, String action, String from, String to, String note) {
        ArrayNode h = s.withArray("history");
        h.addObject().put("at", Instant.now().toString()).put("by", actor).put("action", action).put("from", from).put("to", to).put("note", note);
    }

    private static String target(String id) { return "partner-submission/" + id; }

    private static String text(JsonNode b, String field, int max, boolean required) {
        String v = b.path(field).isValueNode() ? b.path(field).asString("").trim() : "";
        if (v.isEmpty()) {
            if (required) throw ApiException.bad("field-required", field + " is required");
            return null;
        }
        if (v.length() > max) throw ApiException.bad("field-too-long", field + " exceeds " + max + " characters");
        if (v.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t')) throw ApiException.bad("invalid-characters", field + " contains control characters");
        return v;
    }

    private static JsonNode jsonDoc(JsonNode b, String field) {
        JsonNode n = b.path(field);
        if (!n.isObject()) throw ApiException.bad(field + "-required", field + " (JSON object) is required");
        String raw = Json.write(n);
        if (raw.length() > MAX_JSON_CHARS) throw ApiException.bad(field + "-too-large", field + " exceeds 2 MB");
        if (!SecretScanner.scan(raw).isEmpty()) throw ApiException.unprocessable(field + "-secret", field + " contains credentials");
        return n;
    }
}
