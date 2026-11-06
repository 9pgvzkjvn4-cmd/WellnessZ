package io.intimed.partner;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.events.EventBus;
import io.intimed.harness.HarnessService;
import io.intimed.security.audit.AuditService;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Partner certification badges (IM-E07-BL-005). A badge is granted when a partner submission is CERTIFIED after a
 * passing exchange-test run: it records the level, grant date, harness version, the run id and the evidence-pack seal
 * (SHA-256 of the signed manifest), and is shown on the catalogue model ({@code CatalogueService} reads active badges)
 * and at {@code GET /api/partner/badges}.
 * <p>Levels: {@code GOLD} = passing harness run + security posture ACCEPTABLE (MDS2 + SBOM received, no known
 * vulnerabilities, not end-of-life); {@code SILVER} = passing harness run with a posture exception.
 * <p>Automatic revocation: any later harness / regression run of the badged driver that FAILS ({@code run.completed}
 * event), or the withdrawal of the driver ({@link DriverRegistry#onWithdraw}), revokes the badge with the reason and
 * the triggering run recorded. Every grant / revocation is audited.
 */
@Service
public class BadgeService {
    public static final String HARNESS_VERSION = "IntiMed exchange-test harness 2.0 (" + HarnessService.CHECKS.keySet().iterator().next() + "…"
            + HarnessService.CHECKS.keySet().stream().reduce((a, b) -> b).orElse("") + ")";
    private static final String SYSTEM = "system:badge-monitor";

    private final DocumentStore store;
    private final AuditService audit;

    public BadgeService(DocumentStore store, AuditService audit, EventBus events, DriverRegistry drivers) {
        this.store = store;
        this.audit = audit;
        events.subscribe(this::onEvent);
        drivers.onWithdraw((tenant, ref) -> revokeForDriver(tenant, ref, "driver withdrawn", null, SYSTEM));
    }

    void onEvent(String tenant, ObjectNode e) {
        if (!"run.completed".equals(e.path("type").asString())) return;
        if (!"FAILED".equals(e.at("/data/status").asString())) return;
        String runId = e.at("/data/run").asString("");
        store.get(tenant, "run", runId).ifPresent(run -> revokeForDriver(tenant, run.path("driver").asString(), "harness run " + runId + " failed", runId, SYSTEM));
    }

    public ObjectNode grant(String tenant, ObjectNode submission, JsonNode run, JsonNode posture, String actor) {
        if (!"PASSED".equals(run.path("status").asString())) throw ApiException.conflict("badge-run", "a badge requires a passing harness run");
        String driver = submission.get("driverRef").asString();
        // one active badge per driver: an earlier one is superseded
        for (ObjectNode b : store.listByRef(tenant, "badge", driver)) if ("ACTIVE".equals(b.path("status").asString())) revoke(tenant, b, "superseded by a new certification", null, actor);
        String id = Hashing.id("bdg");
        boolean postureOk = "ACCEPTABLE".equals(posture.path("status").asString());
        ObjectNode b = Json.obj().put("id", id).put("driver", driver).put("submissionId", submission.get("id").asString())
                .put("manufacturer", submission.path("manufacturer").asString()).put("model", submission.path("model").asString())
                .put("level", postureOk ? "GOLD" : "SILVER").put("levelBasis", postureOk ? "Passing exchange test + acceptable security posture (MDS2, SBOM, no known vulnerabilities)"
                        : "Passing exchange test; security posture requires an exception (" + posture.path("status").asString("UNASSESSED") + ")")
                .put("grantedAt", Instant.now().toString()).put("grantedBy", actor).put("harnessVersion", HARNESS_VERSION)
                .put("runId", run.get("id").asString()).put("evidenceSeal", run.path("seal").asString()).put("checks", run.path("passed").asInt() + "/" + run.path("total").asInt())
                .put("status", "ACTIVE");
        b.putArray("modelIds").add(submission.get("modelId").asString());
        store.put(tenant, "badge", id, b, "ACTIVE", driver);
        audit.record(tenant, actor, "partner.badge.grant", "badge/" + id, Map.of("driver", driver, "level", b.get("level").asString(), "run", b.get("runId").asString(), "seal", b.get("evidenceSeal").asString()));
        return b;
    }

    /** Revokes every active badge of a driver; returns the number revoked. */
    public int revokeForDriver(String tenant, String driver, String reason, String runId, String actor) {
        int n = 0;
        for (ObjectNode b : store.listByRef(tenant, "badge", driver)) {
            if (!"ACTIVE".equals(b.path("status").asString())) continue;
            revoke(tenant, b, reason, runId, actor);
            n++;
        }
        return n;
    }

    public ObjectNode revoke(String tenant, String id, String reason, String actor) {
        ObjectNode b = store.require(tenant, "badge", id);
        if (!"ACTIVE".equals(b.path("status").asString())) throw ApiException.conflict("badge-revoked", "badge is not active");
        if (reason == null || reason.isBlank()) throw ApiException.bad("reason-required", "a revocation reason is required");
        return revoke(tenant, b, reason.length() > 500 ? reason.substring(0, 500) : reason, null, actor);
    }

    private ObjectNode revoke(String tenant, ObjectNode b, String reason, String runId, String actor) {
        b.put("status", "REVOKED").put("revokedAt", Instant.now().toString()).put("revokedBy", actor).put("revocationReason", reason);
        if (runId != null) b.put("revokedByRun", runId);
        store.put(tenant, "badge", b.get("id").asString(), b, "REVOKED", b.get("driver").asString());
        audit.record(tenant, actor, "partner.badge.revoke", "badge/" + b.get("id").asString(), Map.of("driver", b.get("driver").asString(), "reason", reason));
        return b;
    }

    public List<ObjectNode> list(String tenant, String status) {
        return status == null || status.isBlank() ? store.list(tenant, "badge") : store.listByStatus(tenant, "badge", status.toUpperCase(java.util.Locale.ROOT));
    }
}
