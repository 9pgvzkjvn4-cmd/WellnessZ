package com.wellnessz.e32.request;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Request-a-new-model workflow (E32-HLR-07, E32-F08, contract §13.9): the administrator submits model and connectivity
 * details; the integration team scopes against the existing family adapters (adapter, effort, target sprint); the requester
 * is notified at every status change; KPI = median scoping turnaround in working days (target ≤ 5).
 */
@Service("e32ModelRequestService")
public class ModelRequestService {

    static final Set<String> STATUSES = Set.of("SCOPING", "SCOPED", "IN_DEVELOPMENT", "CERTIFIED", "REJECTED");

    private final E32Support support;

    public ModelRequestService(E32Support support) {
        this.support = support;
    }

    public record RequestCommand(String manufacturer, String model, String family, String connectivity, List<String> interfaceDocumentIds,
                                 List<String> sites, String urgency) {
    }

    public record ScopingCommand(Boolean existingFamilyAdapter, String adapter, Integer effortDays, String targetSprint, String status, String note) {
    }

    @Transactional
    public Map<String, Object> submit(CurrentUser user, RequestCommand cmd) {
        support.requireEnabled();
        if (blank(cmd.manufacturer()) || blank(cmd.model()) || blank(cmd.family()) || blank(cmd.connectivity())) {
            throw ApiException.badRequest("validation_failed", "manufacturer, model, family and connectivity are required");
        }
        boolean knownFamily = false;
        for (DeviceCodes.Family f : DeviceCodes.Family.values()) {
            knownFamily |= f.name().equals(cmd.family());
        }
        if (!knownFamily) {
            throw ApiException.badRequest("validation_failed", "unknown family");
        }
        String urgency = cmd.urgency() == null ? "NORMAL" : cmd.urgency();
        if (!List.of("LOW", "NORMAL", "HIGH").contains(urgency)) {
            throw ApiException.badRequest("validation_failed", "urgency must be LOW, NORMAL or HIGH");
        }
        UUID id = UUID.randomUUID();
        String ref = support.nextModelRequestRef();
        Timestamp now = Timestamp.from(support.now());
        support.jdbc().update("""
                insert into devicecat.model_request (id, request_ref, manufacturer, model, family, connectivity, interface_document_ids, sites, urgency,
                    status, requested_by, requested_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, ?, ?)""",
                id, ref, trim(cmd.manufacturer(), 200), trim(cmd.model(), 300), cmd.family(), trim(cmd.connectivity(), 1000),
                Docs.joinCsv(cmd.interfaceDocumentIds() == null ? List.of() : cmd.interfaceDocumentIds()),
                Docs.joinCsv(cmd.sites() == null ? List.of() : cmd.sites()), urgency, user.username(), now, now);
        support.notifyRole("INTEGRATION_TESTER", "device.model-request-update", levelParams(ref, "SUBMITTED"), null);
        support.audit("DEVICE_MODEL_CREATE", "DEVICE_MODEL_REQUEST", ref, "requested");
        return get(id.toString());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        support.requireEnabled();
        return support.jdbc().queryForList("select id from devicecat.model_request order by requested_at desc", UUID.class).stream()
                .map(id -> get(id.toString())).filter(r -> status == null || status.equals(r.get("status"))).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String idOrRef) {
        List<Map<String, Object>> rows = support.jdbc().queryForList(
                "select * from devicecat.model_request where cast(id as varchar) = ? or request_ref = ?", idOrRef, idOrRef);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Model request");
        }
        Map<String, Object> r = rows.get(0);
        Instant requested = ((Timestamp) r.get("requested_at")).toInstant();
        Timestamp scoped = (Timestamp) r.get("first_scoped_at");
        return Docs.of("id", String.valueOf(r.get("id")), "requestRef", r.get("request_ref"), "manufacturer", r.get("manufacturer"),
                "model", r.get("model"), "family", r.get("family"), "connectivity", r.get("connectivity"),
                "interfaceDocumentIds", Docs.csv(Docs.str(r.get("interface_document_ids"))), "sites", Docs.csv(Docs.str(r.get("sites"))),
                "urgency", r.get("urgency"), "status", r.get("status"), "scoping", r.get("scoping") == null ? null : support.readMap(Docs.str(r.get("scoping"))),
                "requestedBy", r.get("requested_by"), "requestedAt", requested, "scopedBy", r.get("scoped_by"),
                "firstScopedAt", scoped == null ? null : scoped.toInstant(),
                "scopingWorkingDays", scoped == null ? null : workingDays(requested, scoped.toInstant()));
    }

    @Transactional
    public Map<String, Object> scope(CurrentUser user, String id, ScopingCommand cmd) {
        support.requireEnabled();
        Map<String, Object> r = get(id);
        if (cmd.status() == null || !STATUSES.contains(cmd.status())) {
            throw ApiException.badRequest("validation_failed", "status must be SCOPING, SCOPED, IN_DEVELOPMENT, CERTIFIED or REJECTED");
        }
        if ("SCOPED".equals(cmd.status()) && (cmd.adapter() == null || cmd.effortDays() == null || cmd.targetSprint() == null)) {
            throw ApiException.badRequest("validation_failed", "a scoped request needs adapter, effortDays and targetSprint");
        }
        if (cmd.effortDays() != null && (cmd.effortDays() < 0 || cmd.effortDays() > 500)) {
            throw ApiException.badRequest("validation_failed", "effortDays must be 0–500");
        }
        Map<String, Object> scoping = Docs.of("existingFamilyAdapter", cmd.existingFamilyAdapter(), "adapter", cmd.adapter(),
                "effortDays", cmd.effortDays(), "targetSprint", trim(cmd.targetSprint(), 20), "note", trim(cmd.note(), 1000));
        Timestamp now = Timestamp.from(support.now());
        boolean firstScope = r.get("firstScopedAt") == null && !"SCOPING".equals(cmd.status());
        support.jdbc().update("""
                update devicecat.model_request set status = ?, scoping = ?, scoped_by = ?, updated_at = ?,
                    first_scoped_at = case when ? then ? else first_scoped_at end where id = ?""",
                cmd.status(), support.write(scoping), user.username(), now, firstScope, now, UUID.fromString(Docs.str(r.get("id"))));
        support.notifyUser(Docs.str(r.get("requestedBy")), "device.model-request-update", levelParams(Docs.str(r.get("requestRef")), cmd.status()));
        support.audit("DEVICE_MODEL_UPDATE", "DEVICE_MODEL_REQUEST", Docs.str(r.get("requestRef")), cmd.status());
        return get(Docs.str(r.get("id")));
    }

    private Map<String, String> levelParams(String ref, String status) {
        Map<String, String> p = support.params(ref, null);
        p.put("level", status);
        return p;
    }

    /** KPI: median scoping turnaround (working days), share within 5 working days. */
    @Transactional(readOnly = true)
    public Map<String, Object> kpi() {
        support.requireEnabled();
        List<Double> days = new ArrayList<>();
        int open = 0;
        for (Map<String, Object> r : list(null)) {
            Object d = r.get("scopingWorkingDays");
            if (d instanceof Number n) {
                days.add(n.doubleValue());
            } else if (!"REJECTED".equals(r.get("status"))) {
                open++;
            }
        }
        days.sort(Double::compare);
        Double median = days.isEmpty() ? null : days.size() % 2 == 1 ? days.get(days.size() / 2)
                : (days.get(days.size() / 2 - 1) + days.get(days.size() / 2)) / 2.0;
        long within = days.stream().filter(x -> x <= 5).count();
        return Docs.of("scopedRequests", days.size(), "awaitingScoping", open, "medianScopingWorkingDays", median, "targetWorkingDays", 5,
                "withinTargetPct", days.isEmpty() ? null : Math.round(1000.0 * within / days.size()) / 10.0);
    }

    /** Whole working days (Monday–Friday) between two instants, counted from the day after the request. */
    public static int workingDays(Instant from, Instant to) {
        LocalDate a = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate b = to.atZone(ZoneOffset.UTC).toLocalDate();
        int n = 0;
        for (LocalDate d = a.plusDays(1); !d.isAfter(b); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
            }
        }
        return n;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
