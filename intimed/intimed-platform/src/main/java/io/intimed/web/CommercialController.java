package io.intimed.web;

import io.intimed.commercial.BenchmarkService;
import io.intimed.commercial.BillingService;
import io.intimed.commercial.BrandingService;
import io.intimed.commercial.MonthlyReportService;
import io.intimed.commercial.TenantHealthService;
import io.intimed.commercial.TrialService;
import io.intimed.common.ApiException;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.Role;
import io.intimed.tenancy.TenantService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Commercial & executive API (IM-E09): monthly executive report (IM-E09-BL-003), tenant health score (IM-E09-BL-005),
 * billing export and reconciliation (IM-E09-BL-002), trial tenants (IM-E09-BL-001), white-label branding
 * (IM-E09-BL-006) and privacy-preserving benchmarks (IM-E02-BL-007 / IM-E09-BL-004). Role-checked, tenant-scoped
 * (the tenant always comes from the authenticated identity) and audited; outputs carry counts and rates only.
 */
@RestController
@RequestMapping("/api")
public class CommercialController {
    private final MonthlyReportService reports;
    private final TenantHealthService health;
    private final BillingService billing;
    private final TrialService trials;
    private final BrandingService branding;
    private final BenchmarkService benchmarks;
    private final AuditService audit;

    public CommercialController(MonthlyReportService reports, TenantHealthService health, BillingService billing, TrialService trials, BrandingService branding,
                                BenchmarkService benchmarks, AuditService audit) {
        this.reports = reports;
        this.health = health;
        this.billing = billing;
        this.trials = trials;
        this.branding = branding;
        this.benchmarks = benchmarks;
        this.audit = audit;
    }

    private static String t() { return Current.tenant(); }

    private static void executive() { Current.require(Role.TENANT_ADMIN, Role.PLATFORM_SRE, Role.REGULATORY, Role.AUDITOR); }

    // ---------------------------------------------------------------- monthly report (IM-E09-BL-003)
    @GetMapping("/reports/monthly")
    public ObjectNode monthly(@RequestParam(required = false) String month) { executive(); return reports.generate(t(), month, Current.actor()); }

    @GetMapping("/reports/monthly.csv")
    public ResponseEntity<String> monthlyCsv(@RequestParam(required = false) String month) {
        executive();
        ObjectNode r = reports.generate(t(), month, Current.actor());
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"intimed-report-" + r.get("month").asString() + ".csv\"")
                .body(MonthlyReportService.csv(r));
    }

    @GetMapping("/reports/monthly/snapshots")
    public List<ObjectNode> snapshots() { executive(); return reports.snapshots(t()); }

    // ---------------------------------------------------------------- health (IM-E09-BL-005)
    @GetMapping("/tenant/health")
    public ObjectNode health() { Current.require(Role.TENANT_ADMIN, Role.PLATFORM_SRE, Role.DEVICE_ADMIN); return health.compute(t()); }

    // ---------------------------------------------------------------- billing (IM-E09-BL-002)
    @GetMapping("/billing/export")
    public ResponseEntity<?> export(@RequestParam(required = false) String month, @RequestParam(required = false, defaultValue = "json") String format) {
        Current.require(Role.TENANT_ADMIN);
        ObjectNode e = billing.export(t(), month, Current.actor());
        return switch (format) {
            case "json" -> ResponseEntity.ok(e);
            case "csv" -> ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                    .header("X-IntiMed-Signature", "hmac-sha256=" + e.at("/signatures/csv").asString()).header("X-IntiMed-Key-Id", e.get("keyId").asString())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"intimed-metering-" + e.get("month").asString() + ".csv\"").body(e.get("csv").asString());
            case "jsonl" -> ResponseEntity.ok().contentType(new MediaType("application", "x-ndjson", java.nio.charset.StandardCharsets.UTF_8))
                    .header("X-IntiMed-Signature", "hmac-sha256=" + e.at("/signatures/jsonl").asString()).header("X-IntiMed-Key-Id", e.get("keyId").asString())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"intimed-metering-" + e.get("month").asString() + ".jsonl\"").body(e.get("jsonl").asString());
            default -> throw ApiException.bad("invalid-format", "format must be json, csv or jsonl");
        };
    }

    @PostMapping("/billing/verify")
    public Map<String, Object> verify(@RequestBody JsonNode b) {
        Current.require(Role.TENANT_ADMIN);
        String body = b.path("body").asString("");
        if (body.length() > 4 * 1024 * 1024) throw ApiException.bad("too-large", "body too large");
        boolean ok = billing.verify(t(), body, b.path("signature").asString(""), Current.actor());
        audit.record(t(), Current.actor(), "billing.verify", "billing", Map.of("valid", ok));
        return Map.of("valid", ok);
    }

    @PostMapping("/billing/reconcile")
    public ObjectNode reconcile(@RequestBody JsonNode b) { Current.require(Role.TENANT_ADMIN); return billing.reconcile(t(), b, Current.actor()); }

    // ---------------------------------------------------------------- trials (IM-E09-BL-001) – platform operator
    @GetMapping("/trials")
    public List<ObjectNode> trialList() { Current.require(Role.PLATFORM_SRE); return trials.list(); }

    @PostMapping("/trials")
    public TenantService.Tenant createTrial(@RequestBody JsonNode b) {
        Current.require(Role.PLATFORM_SRE);
        return trials.create(b.path("id").asString(""), b.path("name").asString(""), b.path("residency").asString("eu-west"), b.path("days").asInt(30),
                b.path("graceDays").isInt() ? b.get("graceDays").asInt() : null, Current.actor());
    }

    @PostMapping("/trials/{id}/extend")
    public TenantService.Tenant extendTrial(@PathVariable String id, @RequestBody JsonNode b) {
        Current.require(Role.PLATFORM_SRE);
        return trials.extend(id, b.path("days").asInt(0), Current.actor());
    }

    @PostMapping("/trials/sweep")
    public List<ObjectNode> sweep() {
        Current.require(Role.PLATFORM_SRE);
        audit.record(t(), Current.actor(), "trial.sweep.manual", "trials", null);
        return trials.sweep(java.time.Instant.now());
    }

    // ---------------------------------------------------------------- branding (IM-E09-BL-006)
    @GetMapping("/tenant/branding")
    public ObjectNode getBranding() { return branding.get(t()); }

    @PutMapping("/tenant/branding")
    public ObjectNode putBranding(@RequestBody JsonNode b) { Current.require(Role.TENANT_ADMIN); return branding.update(t(), b, Current.actor()); }

    @GetMapping(value = "/tenant/branding.css", produces = "text/css")
    public ResponseEntity<String> brandingCss() {
        return ResponseEntity.ok().contentType(new MediaType("text", "css", java.nio.charset.StandardCharsets.UTF_8)).body(branding.css(t()));
    }

    // ---------------------------------------------------------------- benchmarks (IM-E02-BL-007 / IM-E09-BL-004)
    @GetMapping("/benchmarks")
    public ObjectNode benchmarks() { return benchmarks.benchmarks(t(), Current.actor()); }

    @PutMapping("/benchmarks/opt-in")
    public Map<String, Object> optIn(@RequestBody JsonNode b) {
        Current.require(Role.TENANT_ADMIN);
        if (!b.path("optIn").isBoolean()) throw ApiException.bad("invalid-opt-in", "optIn must be true or false");
        return Map.of("optIn", benchmarks.setOptIn(t(), b.get("optIn").asBoolean(), Current.actor()));
    }

    @PostMapping("/benchmarks/contribute")
    public Map<String, Object> contribute() {
        Current.require(Role.PLATFORM_SRE);
        int n = benchmarks.contributeToKnowledgeBase();
        audit.record(t(), Current.actor(), "benchmark.contribute", "knowledge-base", Map.of("cells", n));
        return Map.of("contributedCells", n);
    }
}
