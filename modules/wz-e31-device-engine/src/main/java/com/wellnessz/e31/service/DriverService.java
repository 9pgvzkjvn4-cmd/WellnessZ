package com.wellnessz.e31.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.device.DeviceObservationSink;
import com.wellnessz.casekernel.device.DevicePackageSignatures;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.driver.BundledDrivers;
import com.wellnessz.e31.driver.DriverException;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.driver.DriverParser;
import com.wellnessz.e31.driver.DriverValidator;
import com.wellnessz.e31.gate.GateEnvironment;
import com.wellnessz.e31.pipeline.PipelineProcessor;
import com.wellnessz.e31.simulator.DryRun;
import com.wellnessz.e31.simulator.GoldenDatasets;
import com.wellnessz.e31.support.Ids;
import com.wellnessz.e31.terminology.TerminologyService;
import com.wellnessz.security.audit.AuditService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Driver SDK services (E31-HLR-03, E31-F03, contract sections 6 and 12): validation (schema, secret scan, mapping coverage –
 * nothing persisted), conformance (every test vector through the simulator, evidence SHA-256) and four-eyes signing
 * (INTEGRATION_TESTER requests, a different DEVICE_APPROVER approves; E34 signs; the signed package is handed back for the
 * E32 registry).
 */
@Service("e31DriverService")
public class DriverService {

    private final DriverParser parser;
    private final BundledDrivers bundled;
    private final GoldenDatasets golden;
    private final TerminologyService terminology;
    private final PipelineProcessor processor;
    private final DeviceSpis spis;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    public DriverService(BundledDrivers bundled, GoldenDatasets golden, TerminologyService terminology, PipelineProcessor processor, DeviceSpis spis,
                         JdbcTemplate jdbc, AuditService audit, JsonMapper json, Clock clock) {
        this.parser = new DriverParser(json);
        this.bundled = bundled;
        this.golden = golden;
        this.terminology = terminology;
        this.processor = processor;
        this.spis = spis;
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public List<Map<String, Object>> samples() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DriverPackage p : bundled.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("packageId", p.packageId());
            m.put("version", p.version());
            m.put("family", p.family());
            m.put("models", p.models());
            m.put("adapter", p.adapter());
            m.put("options", p.options());
            m.put("sha256", p.sha256());
            m.put("status", "SAMPLE");
            out.add(m);
        }
        return out;
    }

    public JsonNode sampleSource(String packageId, String version) {
        return json.readTree(bundled.source(packageId, version).orElseThrow(() -> ApiException.notFound("Driver package")));
    }

    Set<String> deployedTargets() {
        return spis.sinks().stream().map(DeviceObservationSink::target).collect(Collectors.toSet());
    }

    public DriverValidator.Report validate(String actor, String packageJson) {
        DriverValidator.Report r = DriverValidator.validate(parser, packageJson, terminology, deployedTargets());
        audit.recordAs(actor, "DEVICE", "DEVICE_DRIVER_VALIDATE", "DEVICE_DRIVER", r.packageId() == null ? "-" : r.packageId() + "@" + r.version(),
                AuditService.Outcome.SUCCESS, r.valid() ? "valid" : "errors=" + r.errors().size());
        return r;
    }

    public record VectorResult(String name, String outcome, List<String> diff) {
    }

    public record Conformance(boolean passed, String packageId, String version, List<VectorResult> results, String evidenceSha256) {
    }

    public Conformance conformance(String actor, String packageJson) {
        DriverPackage pkg;
        try {
            pkg = parser.parse(packageJson);
        } catch (DriverException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "step_invalid", String.join("; ", e.errors()));
        }
        Conformance c = conformance(pkg);
        audit.recordAs(actor, "DEVICE", "DEVICE_DRIVER_VALIDATE", "DEVICE_DRIVER", pkg.ref(), AuditService.Outcome.SUCCESS,
                "conformance " + (c.passed() ? "passed" : "failed"));
        return c;
    }

    public Conformance conformance(DriverPackage pkg) {
        List<VectorResult> results = new ArrayList<>();
        StringBuilder evidence = new StringBuilder(pkg.sha256());
        for (DriverPackage.TestVector v : pkg.testVectors()) {
            List<String> diff;
            try {
                byte[] input = golden.content(v.input());
                Instant now = clock.instant();
                DryRun.Result r = DryRun.run(processor.adapters(), terminology, pkg, input, contentType(pkg, v.input()), headers(pkg, v.input()), now, null,
                        GateEnvironment.SIMULATOR, 120);
                diff = DryRun.diff(r, v.expect());
            } catch (ApiException e) {
                diff = List.of("input: golden dataset " + v.input() + " not found");
            }
            results.add(new VectorResult(v.name(), diff.isEmpty() ? "PASS" : "FAIL", diff));
            evidence.append('|').append(v.name()).append(':').append(diff.isEmpty() ? "PASS" : "FAIL");
        }
        boolean passed = !results.isEmpty() && results.stream().allMatch(r -> "PASS".equals(r.outcome()));
        return new Conformance(passed, pkg.packageId(), pkg.version(), results, Ids.sha256Hex(evidence.toString()));
    }

    static String contentType(DriverPackage pkg, String input) {
        return switch (pkg.adapter()) {
            case "HL7V2" -> "application/hl7-v2";
            case "FHIR" -> "application/fhir+json";
            case "POCT1A", "FILE_XML" -> "application/xml";
            case "FILE_CSV" -> "text/csv";
            case "FILE_GDT" -> "text/plain; charset=ISO-8859-1";
            default -> "application/json";
        };
    }

    static Map<String, String> headers(DriverPackage pkg, String input) {
        String name = input.substring(input.lastIndexOf('/') + 1);
        return Map.of("X-File-Name", name);
    }

    // ------------------------------------------------------------------ four-eyes signing

    public Map<String, Object> requestSignature(String actor, String packageJson) {
        DriverValidator.Report report = DriverValidator.validate(parser, packageJson, terminology, deployedTargets());
        if (!report.valid()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "step_invalid", "The package is not valid: " + String.join("; ", report.errors()));
        }
        DriverPackage pkg = parser.parse(packageJson);
        Conformance c = conformance(pkg);
        if (!c.passed()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "step_invalid", "Conformance failed – the package cannot be signed");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into deviceint.driver_sign_request (id, package_id, version, canonical_sha256, package_json, requested_by, requested_at, status)
                values (?, ?, ?, ?, ?, ?, ?, 'REQUESTED')""", id, pkg.packageId(), pkg.version(), pkg.sha256(), packageJson, actor,
                Timestamp.from(clock.instant()));
        audit.recordAs(actor, "DEVICE", "DEVICE_DRIVER_VALIDATE", "DEVICE_DRIVER_SIGN_REQUEST", id.toString(), AuditService.Outcome.SUCCESS, pkg.ref());
        return Map.of("requestId", id, "status", "REQUESTED", "packageId", pkg.packageId(), "version", pkg.version(), "canonicalSha256", pkg.sha256(),
                "conformanceEvidenceSha256", c.evidenceSha256());
    }

    /** Approval by a different person: E34 signs the canonical manifest; the signed package goes to the E32 registry. */
    public Map<String, Object> approveSignature(String actor, UUID requestId) {
        Map<String, Object> r = jdbc.queryForList("select * from deviceint.driver_sign_request where id = ?", requestId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Signing request"));
        if (!"REQUESTED".equals(r.get("status"))) {
            throw ApiException.conflict("already_decided", "The signing request was already decided");
        }
        if (actor.equals(r.get("requested_by"))) {
            throw ApiException.conflict("four_eyes_violation", "The requester cannot approve their own signing request");
        }
        DriverPackage pkg = parser.parse((String) r.get("package_json"));
        DevicePackageSignatures.Signature sig = spis.signatures().sign(actor, pkg.canonicalJson().getBytes(StandardCharsets.UTF_8));
        ObjectNode signed = (ObjectNode) parser.tree((String) r.get("package_json"));
        ObjectNode s = signed.putObject("signature");
        s.put("keyId", sig.keyId());
        s.put("algorithm", sig.algorithm());
        s.put("value", sig.signatureBase64());
        jdbc.update("update deviceint.driver_sign_request set status = 'APPROVED', decided_by = ?, decided_at = ?, key_id = ?, signature = ? where id = ?",
                actor, Timestamp.from(clock.instant()), sig.keyId(), sig.signatureBase64(), requestId);
        audit.recordAs(actor, "DEVICE", "DEVICE_PACKAGE_SIGN", "DEVICE_DRIVER", pkg.ref(), AuditService.Outcome.SUCCESS, "key=" + sig.keyId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("status", "APPROVED");
        out.put("canonicalSha256", pkg.sha256());
        out.put("signedPackage", signed);
        out.put("registry", "POST /api/v1/device-catalogue/drivers");
        return out;
    }
}
