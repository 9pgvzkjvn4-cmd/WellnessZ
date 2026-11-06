package io.intimed.web;

import io.intimed.common.ApiException;
import io.intimed.operations.RegressionService;
import io.intimed.operations.ShadowReviewService;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Site operations API: scheduled regression suites with trends (IM-E04-BL-007) and the shadow-mode discrepancy report
 * with four-eyes clinical sign-off (IM-E04-BL-005). Every endpoint is role-checked, tenant-scoped and audited.
 */
@RestController
@RequestMapping("/api")
public class OperationsController {
    private final RegressionService regression;
    private final ShadowReviewService shadow;
    private final AuditService audit;

    public OperationsController(RegressionService regression, ShadowReviewService shadow, AuditService audit) {
        this.regression = regression;
        this.shadow = shadow;
        this.audit = audit;
    }

    private static String t() { return Current.tenant(); }

    static String configId(String id) {
        if (id == null || !id.matches("[a-z0-9-]{3,64}")) throw ApiException.bad("invalid-config", "invalid configuration id");
        return id;
    }

    // ---------------------------------------------------------------- regression (IM-E04-BL-007)
    @GetMapping("/regression/trends")
    public ObjectNode trends(@RequestParam(required = false) String configId) {
        Current.require(Role.DEVICE_ADMIN, Role.PLATFORM_SRE, Role.TENANT_ADMIN, Role.TEMPLATE_OWNER, Role.INTEGRATION_DEV, Role.REGULATORY, Role.AUDITOR);
        return regression.trends(t(), configId == null || configId.isBlank() ? null : configId(configId));
    }

    @GetMapping("/regression/runs")
    public List<ObjectNode> runs(@RequestParam(required = false) String configId) {
        Current.require(Role.DEVICE_ADMIN, Role.PLATFORM_SRE, Role.TENANT_ADMIN, Role.TEMPLATE_OWNER, Role.INTEGRATION_DEV, Role.REGULATORY, Role.AUDITOR);
        return regression.runs(t(), configId == null || configId.isBlank() ? null : configId(configId)).reversed();
    }

    @PostMapping("/regression/run")
    public Map<String, Object> runNow() {
        Current.require(Role.DEVICE_ADMIN, Role.PLATFORM_SRE);
        List<ObjectNode> runs = regression.runTenant(t(), "MANUAL", Current.actor());
        long passed = runs.stream().filter(r -> "PASSED".equals(r.path("status").asString())).count();
        return Map.of("configs", runs.size(), "passed", passed, "failed", runs.size() - passed, "runs", runs);
    }

    // ---------------------------------------------------------------- shadow sign-off (IM-E04-BL-005)
    @GetMapping("/shadow/{configId}/instances")
    public List<ObjectNode> shadowInstances(@PathVariable String configId) {
        Current.require(Role.TEMPLATE_OWNER, Role.CLINICIAN, Role.PHYSICIAN);
        List<ObjectNode> l = shadow.instances(t(), configId(configId));
        audit.record(t(), Current.actor(), "shadow.instances.view", "config/" + configId, Map.of("count", l.size()));
        return l;
    }

    @PostMapping("/shadow/{configId}/manual")
    public ObjectNode manual(@PathVariable String configId, @RequestBody JsonNode b) {
        Current.require(Role.TEMPLATE_OWNER, Role.CLINICIAN);
        return shadow.recordManual(t(), configId(configId), b.path("instanceId").asString(null), b.path("field").asString(null), b.get("value"),
                b.path("unit").asString(null), Current.actor());
    }

    @GetMapping("/shadow/{configId}/report")
    public ObjectNode report(@PathVariable String configId, @RequestParam(required = false) Double tolerancePct) {
        Current.require(Role.TEMPLATE_OWNER, Role.CLINICIAN, Role.PHYSICIAN, Role.REGULATORY, Role.AUDITOR, Role.DEVICE_ADMIN);
        ObjectNode r = shadow.report(t(), configId(configId), tolerancePct);
        audit.record(t(), Current.actor(), "shadow.report", "config/" + configId, Map.of("pairs", r.get("pairs").asLong(), "agreementPct", r.get("agreementPct").asDouble()));
        return r;
    }

    @PostMapping("/shadow/{configId}/sign-off")
    public ObjectNode signOff(@PathVariable String configId, @RequestBody(required = false) JsonNode b) {
        Current.require(Role.TEMPLATE_OWNER);
        return shadow.signOff(t(), configId(configId), b == null ? null : b.path("comment").asString(null),
                b == null || !b.path("tolerancePct").isNumber() ? null : b.get("tolerancePct").asDouble(), Current.actor());
    }
}
