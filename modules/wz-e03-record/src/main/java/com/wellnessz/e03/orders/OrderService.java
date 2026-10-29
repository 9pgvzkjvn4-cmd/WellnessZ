package com.wellnessz.e03.orders;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.e03.cds.CdsAlert;
import com.wellnessz.e03.cds.CdsOverride;
import com.wellnessz.e03.cds.CdsService;
import com.wellnessz.e03.model.LabOrder;
import com.wellnessz.e03.model.LabOrderRepository;
import com.wellnessz.e03.model.ResultRepository;
import com.wellnessz.e03.support.CodeRef;
import com.wellnessz.e03.support.JsonSupport;
import com.wellnessz.e03.support.RecordGuard;
import com.wellnessz.e03.terminology.TerminologyCatalog;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.databind.json.JsonMapper;

/**
 * Orders and order sets (E03-F04, E03-BL-010): LAB / IMAGING orders produce an HL7 v2.5 ORM^O01 that the lab gateway pulls
 * from the queue ({@code /integration/lis/orders}); clinic-device orders (audiometry, spirometry, vision, ECG) go straight to
 * the clinic work-list. Ordering runs the duplicate-test check (same code within 30 days).
 */
@Service
public class OrderService {

    static final Set<String> LIS_CATEGORIES = Set.of("LAB", "IMAGING");

    private final LabOrderRepository orders;
    private final ResultRepository results;
    private final RecordGuard guard;
    private final CaseAccess caseAccess;
    private final CaseTimeline timeline;
    private final TerminologyCatalog terminology;
    private final CdsService cds;
    private final Hl7Messages hl7;
    private final JsonSupport json;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;
    private final List<OrderSet> orderSets;

    public OrderService(LabOrderRepository orders, ResultRepository results, RecordGuard guard, CaseAccess caseAccess, CaseTimeline timeline,
                        TerminologyCatalog terminology, CdsService cds, Hl7Messages hl7, JsonSupport json, JdbcTemplate jdbc,
                        AuditService audit, Clock clock, JsonMapper mapper) throws IOException {
        this.orders = orders;
        this.results = results;
        this.guard = guard;
        this.caseAccess = caseAccess;
        this.timeline = timeline;
        this.terminology = terminology;
        this.cds = cds;
        this.hl7 = hl7;
        this.json = json;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        try (InputStream in = getClass().getResourceAsStream("/order-sets/order-sets.json")) {
            if (in == null) {
                throw new IllegalStateException("order-sets.json missing");
            }
            this.orderSets = List.of(mapper.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), OrderSet[].class));
        }
    }

    // ------------------------------------------------------------------ records

    public record OrderSet(String code, String title, String category, List<CodeRef> items) {
    }

    public record OrderSetItem(String category, CodeRef code) {
    }

    public record OrderSetView(String code, String title, String category, List<OrderSetItem> items) {
    }

    public record OrderView(UUID id, String placerOrderNumber, String employeeId, UUID caseId, String category, List<CodeRef> items,
                            String orderSetCode, String priority, String status, String clinicalNote, String orderedBy, Instant orderedAt,
                            Instant sentAt, int resultCount) {
    }

    public record OrderRequest(UUID caseId, @Pattern(regexp = "LAB|IMAGING|AUDIOMETRY|SPIROMETRY|VISION|ECG") String category,
                               @Valid @Size(max = 30) List<CodeRef> items, @Pattern(regexp = "[A-Z0-9_]{1,40}") String orderSetCode,
                               @Pattern(regexp = "ROUTINE|URGENT") String priority, @Size(max = 2000) String clinicalNote,
                               @Valid @Size(max = 20) List<CdsOverride> overrides) {
    }

    public record LisOrder(UUID orderId, String placerOrderNumber, String hl7) {
    }

    public List<OrderSetView> orderSets() {
        return orderSets.stream().map(s -> new OrderSetView(s.code(), s.title(), s.category(),
                s.items().stream().map(i -> new OrderSetItem(s.category(), i)).toList())).toList();
    }

    // ------------------------------------------------------------------ place / list / cancel

    @Transactional
    public OrderView place(CurrentUser user, String employeeId, OrderRequest r) {
        RecordGuard.Access access = guard.write(user, employeeId, DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "ORDER_CREATE");
        String category = r.category();
        List<CodeRef> requested;
        if (r.orderSetCode() != null) {
            OrderSet set = orderSets.stream().filter(s -> s.code().equals(r.orderSetCode())).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("unknown_order_set", "Unknown order set"));
            if (category != null && !category.equals(set.category())) {
                throw ApiException.badRequest("category_mismatch", "The order set belongs to category " + set.category());
            }
            category = set.category();
            requested = set.items();
        } else {
            requested = r.items();
        }
        if (category == null || requested == null || requested.isEmpty()) {
            throw ApiException.badRequest("invalid_order", "Give a category and at least one item, or an order set");
        }
        List<CodeRef> items = new ArrayList<>();
        for (CodeRef c : requested) {
            items.add(terminology.require(c));
        }
        if (r.caseId() != null) {
            OhCase c = caseAccess.clinical(user, r.caseId(), AccessPolicy.Action.UPDATE, "ORDER_CREATE");
            if (!c.getEmployeeId().equals(employeeId)) {
                throw ApiException.badRequest("case_employee_mismatch", "The case belongs to another employee");
            }
        }
        Instant now = clock.instant();
        List<CdsAlert> alerts = cds.duplicateTestAlerts(employeeId, items.stream().map(CodeRef::code).toList(), now);
        Map<String, String> applied = cds.enforce(employeeId, "ORDER", user.username(), alerts, r.overrides());
        LabOrder o = new LabOrder();
        o.setId(UUID.randomUUID());
        o.setPlacerOrderNumber(nextNumber(now));
        o.setEmployeeId(employeeId);
        o.setCaseId(r.caseId());
        o.setCategory(category);
        o.setItems(json.write(items));
        o.setOrderSetCode(r.orderSetCode());
        o.setPriority(r.priority() == null ? "ROUTINE" : r.priority());
        o.setClinicalNote(r.clinicalNote());
        o.setClassification(DataClassification.CLINICAL.name());
        o.setOrderedBy(user.username());
        o.setOrderedAt(now);
        o.setUpdatedAt(now);
        if (LIS_CATEGORIES.contains(category)) {
            o.setStatus("QUEUED");
            o.setHl7Message(hl7.orm(o.getPlacerOrderNumber(), employeeId, access.employee().getDisplayName(), user.username(),
                    o.getPriority(), items, now));
        } else {
            o.setStatus("SENT"); // clinic device work-list
            o.setSentAt(now);
        }
        orders.save(o);
        items.forEach(c -> terminology.recordUsage(user.username(), c.system(), c.code()));
        if (r.caseId() != null) {
            timeline.record(r.caseId(), "ORDER_PLACED", "Order " + o.getPlacerOrderNumber() + " placed (" + category + ")");
        }
        audit.record("ORDER_CREATE", "ORDER", o.getId().toString(), AuditService.Outcome.SUCCESS,
                o.getPlacerOrderNumber() + " cdsAlerts=" + alerts.size() + " overridden=" + applied.size());
        return view(o);
    }

    String nextNumber(Instant now) {
        int year = now.atZone(ZoneOffset.UTC).getYear();
        Long next = jdbc.queryForObject("""
                insert into ohrecord.order_sequence (year, next_val) values (?, 1)
                on conflict (year) do update set next_val = ohrecord.order_sequence.next_val + 1
                returning next_val""", Long.class, year);
        long value = next == null ? 0L : next.longValue();
        return "ORD-%d-%06d".formatted(year, value);
    }

    OrderView view(LabOrder o) {
        return new OrderView(o.getId(), o.getPlacerOrderNumber(), o.getEmployeeId(), o.getCaseId(), o.getCategory(),
                json.readList(o.getItems(), CodeRef.class), o.getOrderSetCode(), o.getPriority(), o.getStatus(), o.getClinicalNote(),
                o.getOrderedBy(), o.getOrderedAt(), o.getSentAt(), results.findByOrderId(o.getId()).size());
    }

    @Transactional(readOnly = true)
    public List<OrderView> list(CurrentUser user, String employeeId) {
        guard.read(user, employeeId, "ORDER_LIST");
        List<OrderView> out = orders.findByEmployeeIdOrderByOrderedAtDesc(employeeId).stream().map(this::view).toList();
        audit.record("ORDER_LIST", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public OrderView cancel(CurrentUser user, UUID id) {
        LabOrder o = orders.findById(id).orElseThrow(() -> ApiException.notFound("Order"));
        guard.write(user, o.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.UPDATE, "ORDER_CANCEL");
        if (!o.getOrderedBy().equals(user.username())) {
            throw ApiException.forbidden("Only the ordering clinician can cancel this order");
        }
        if (Set.of("RESULTED", "CANCELLED").contains(o.getStatus())) {
            throw ApiException.conflict("not_cancellable", "Order is already " + o.getStatus().toLowerCase(java.util.Locale.ROOT));
        }
        Instant now = clock.instant();
        o.setStatus("CANCELLED");
        o.setCancelledBy(user.username());
        o.setCancelledAt(now);
        o.setUpdatedAt(now);
        audit.record("ORDER_CANCEL", "ORDER", id.toString(), AuditService.Outcome.SUCCESS, o.getPlacerOrderNumber());
        return view(o);
    }

    // ------------------------------------------------------------------ LIS gateway pull queue

    @Transactional(readOnly = true)
    public List<LisOrder> lisQueue(String status) {
        List<LisOrder> out = orders.findByStatusOrderByOrderedAtAsc(status).stream()
                .filter(o -> LIS_CATEGORIES.contains(o.getCategory()))
                .map(o -> new LisOrder(o.getId(), o.getPlacerOrderNumber(), o.getHl7Message())).toList();
        audit.record("LIS_QUEUE_READ", "ORDER", status, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public LisOrder acknowledge(UUID id) {
        LabOrder o = orders.findById(id).orElseThrow(() -> ApiException.notFound("Order"));
        if (!"QUEUED".equals(o.getStatus())) {
            throw ApiException.conflict("not_queued", "Only queued orders can be acknowledged");
        }
        Instant now = clock.instant();
        o.setStatus("SENT");
        o.setSentAt(now);
        o.setUpdatedAt(now);
        audit.record("LIS_ORDER_ACK", "ORDER", id.toString(), AuditService.Outcome.SUCCESS, o.getPlacerOrderNumber());
        return new LisOrder(o.getId(), o.getPlacerOrderNumber(), null);
    }

    /** Updates the order status after results arrived: RESULTED when every item has a result, else PARTIAL. */
    @Transactional
    public void onResults(LabOrder o) {
        if ("CANCELLED".equals(o.getStatus())) {
            return;
        }
        Set<String> resulted = new java.util.HashSet<>();
        results.findByOrderId(o.getId()).forEach(r -> resulted.add(r.getCode()));
        List<CodeRef> items = json.readList(o.getItems(), CodeRef.class);
        // a device study (one audiogram / spirometry / vision / ECG record) completes its order
        boolean complete = !LIS_CATEGORIES.contains(o.getCategory()) && !resulted.isEmpty()
                || items.stream().allMatch(i -> resulted.contains(i.code()));
        o.setStatus(complete ? "RESULTED" : "PARTIAL");
        o.setUpdatedAt(clock.instant());
    }
}
