package com.wellnessz.e08.cost;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseCostRecorder;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.common.text.ClinicalTextDetector;
import com.wellnessz.e08.E08Events;
import com.wellnessz.e08.claim.ClaimService;
import com.wellnessz.e08.model.Claim;
import com.wellnessz.e08.model.ClaimEnums.ClaimStatus;
import com.wellnessz.e08.model.ClaimEnums.CostCategory;
import com.wellnessz.e08.model.ClaimEnums.CostKind;
import com.wellnessz.e08.model.ClaimEnums.ExportStatus;
import com.wellnessz.e08.model.CostExport;
import com.wellnessz.e08.model.E08ClaimRepository;
import com.wellnessz.e08.model.E08CostExportRepository;
import com.wellnessz.e08.model.E08LedgerEntryRepository;
import com.wellnessz.e08.model.LedgerEntry;
import com.wellnessz.e08.support.ClaimAccess;
import com.wellnessz.e08.support.ClaimsSupport;
import com.wellnessz.e08.support.Money;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Cost tracking and finance export (E08-F05): the case cost ledger – claim entries and, through the {@link CaseCostRecorder}
 * SPI, E04 pre-authorisation estimates and provider invoices (case-level until a claim exists) – roll-ups by subsidiary /
 * department / cost centre with small-group suppression (no names), budgets and budget vs actual, the monthly ERP export with
 * SHA-256 checksum and its reconciliation, and the closed-claim reconciliation of paid totals. Incurred cost = INVOICE and
 * PAYMENT entries; ESTIMATE and RESERVE entries are exposure, shown on the claim only.
 */
@Service
public class CostService implements CaseCostRecorder {

    static final Set<String> INCURRED = Set.of(CostKind.INVOICE.name(), CostKind.PAYMENT.name());

    public record LedgerView(UUID id, UUID claimId, String level, String category, String kind, Money amount, LocalDate incurredOn,
                             String providerCode, String description, String invoiceNumber, String sourceType, String sourceRef, String createdBy,
                             Instant createdAt) {
    }

    public record LedgerSummary(List<LedgerView> entries, Map<String, Money> totalsByKind, String currency) {
    }

    public record BudgetView(int year, String unitCode, Money amount, String updatedBy, Instant updatedAt) {
    }

    public record TrendPoint(String month, Money actual) {
    }

    public record BudgetVsActual(String unitCode, String label, Money budget, Money actual, Money variance, BigDecimal variancePct,
                                 List<TrendPoint> trend) {
    }

    public record ExportView(UUID id, String month, String batchId, int rows, Money total, String checksum, String status, Money erpTotal,
                             String erpReference, Instant reconciledAt, String reconciledBy, String createdBy, Instant createdAt) {
    }

    public record ReconciliationRow(String claimRef, Money paidTotal, Money ledgerTotal, Money difference, boolean reconciled) {
    }

    private final E08LedgerEntryRepository ledger;
    private final E08ClaimRepository claims;
    private final E08CostExportRepository exports;
    private final OhCaseRepository cases;
    private final ClaimService claimService;
    private final ClaimAccess access;
    private final ClaimsSupport support;
    private final EventBus bus;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final int minGroupSize;

    public CostService(E08LedgerEntryRepository ledger, E08ClaimRepository claims, E08CostExportRepository exports, OhCaseRepository cases,
                       ClaimService claimService, ClaimAccess access, ClaimsSupport support, EventBus bus, JdbcTemplate jdbc, AuditService audit,
                       @Value("${wellnessz.analytics.min-group-size:5}") int minGroupSize) {
        this.ledger = ledger;
        this.claims = claims;
        this.exports = exports;
        this.cases = cases;
        this.claimService = claimService;
        this.access = access;
        this.support = support;
        this.bus = bus;
        this.jdbc = jdbc;
        this.audit = audit;
        this.minGroupSize = Math.max(1, minGroupSize);
    }

    // ------------------------------------------------------------------ SPI (E04)

    @Override
    @Transactional
    public UUID record(String actor, CostEntry entry) {
        if (entry == null || entry.caseId() == null || entry.category() == null || entry.kind() == null) {
            throw ApiException.badRequest("invalid_cost_entry", "caseId, category and kind are required");
        }
        if (entry.sourceType() == null || !entry.sourceType().matches("[A-Z][A-Z0-9_]{1,39}")) {
            throw ApiException.badRequest("invalid_cost_entry", "sourceType must be an upper-case code");
        }
        if (entry.sourceRef() != null && entry.sourceRef().length() > 100) {
            throw ApiException.badRequest("invalid_cost_entry", "sourceRef too long");
        }
        OhCase oc = cases.findById(entry.caseId()).orElseThrow(() -> ApiException.notFound("Case"));
        BigDecimal amount = validAmount(entry.amount() == null ? null : Money.scale(entry.amount()));
        String currency = validCurrency(entry.currency());
        String description = entry.description() == null || entry.description().isBlank() || ClinicalTextDetector.looksClinical(entry.description())
                ? null : entry.description().strip(); // amounts only – clinical wording never reaches the ledger
        LedgerEntry e = entry.sourceRef() == null ? null
                : ledger.findBySourceTypeAndSourceRefAndKind(entry.sourceType(), entry.sourceRef(), entry.kind().name()).orElse(null);
        Instant now = support.now();
        boolean created = e == null;
        if (created) {
            e = LedgerEntry.create();
            e.setCaseId(oc.getId());
            e.setEmployeeId(entry.employeeId() != null ? entry.employeeId() : oc.getEmployeeId());
            e.setSourceType(entry.sourceType());
            e.setSourceRef(entry.sourceRef());
            e.setKind(entry.kind().name());
            e.setCreatedBy(actor == null ? "system" : actor);
            e.setCreatedAt(now);
            snapshotUnit(e, oc);
        }
        e.setClaimId(claimService.primaryClaim(oc.getId()).map(Claim::getId).orElse(null));
        e.setCategory(entry.category().name());
        e.setAmount(Money.scale(amount));
        e.setCurrency(currency);
        e.setIncurredOn(entry.incurredOn() != null ? entry.incurredOn() : support.today());
        e.setProviderCode(entry.providerCode() == null || !entry.providerCode().matches("[A-Z0-9_]{2,40}") ? null : entry.providerCode());
        e.setDescription(description == null ? null : description.substring(0, Math.min(description.length(), 500)));
        e.setUpdatedAt(now);
        ledger.save(e);
        audit.recordAs(actor == null ? "system" : actor, "MODULE", created ? "CLAIM_COST_RECORD" : "CLAIM_COST_UPDATE", "COST_ENTRY",
                e.getId().toString(), AuditService.Outcome.SUCCESS, entry.sourceType() + " " + entry.kind());
        return e.getId();
    }

    // ------------------------------------------------------------------ claim ledger

    public record CostCommand(String category, String kind, BigDecimal amount, String currency, LocalDate incurredOn, String description,
                              String invoiceNumber) {
    }

    @Transactional
    public LedgerView add(CurrentUser user, UUID claimId, CostCommand cmd) {
        Claim c = access.require(user, claimId, ClaimAccess.roles(user, Roles.CLAIMS_COORDINATOR, Roles.FINANCE), "CLAIM_COST_ADD");
        CostCategory category = enumOf(CostCategory.class, cmd.category(), "category");
        CostKind kind = enumOf(CostKind.class, cmd.kind(), "kind");
        if (cmd.incurredOn() == null || cmd.incurredOn().isAfter(support.today().plusDays(366))) {
            throw ApiException.badRequest("invalid_incurred_on", "incurredOn is required (not more than a year ahead)");
        }
        if (cmd.invoiceNumber() != null && !cmd.invoiceNumber().matches("[A-Za-z0-9_./\\-]{1,60}")) {
            throw ApiException.badRequest("invalid_invoice_number", "Invalid invoice number");
        }
        OhCase oc = cases.findById(c.getCaseId()).orElseThrow(() -> ApiException.notFound("Case"));
        Instant now = support.now();
        LedgerEntry e = LedgerEntry.create();
        e.setClaimId(c.getId());
        e.setCaseId(c.getCaseId());
        e.setEmployeeId(c.getEmployeeId());
        e.setCategory(category.name());
        e.setKind(kind.name());
        e.setAmount(Money.scale(validAmount(cmd.amount())));
        e.setCurrency(validCurrency(cmd.currency()));
        e.setIncurredOn(cmd.incurredOn());
        e.setDescription(ClaimsSupport.screened("description", cmd.description(), 500));
        e.setInvoiceNumber(cmd.invoiceNumber());
        e.setSourceType("CLAIM");
        e.setCreatedBy(user.username());
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        snapshotUnit(e, oc);
        ledger.save(e);
        audit.record("CLAIM_COST_ADD", "CLAIM", claimId.toString(), AuditService.Outcome.SUCCESS, category + " " + kind);
        return view(e);
    }

    @Transactional(readOnly = true)
    public LedgerSummary ledger(CurrentUser user, UUID claimId) {
        Claim c = access.require(user, claimId, ClaimAccess.roles(user, Roles.CLAIMS_COORDINATOR, Roles.FINANCE), "CLAIM_COSTS_READ");
        List<LedgerEntry> list = ledger.findForClaim(c.getId(), c.getCaseId());
        Map<String, BigDecimal> rates = rates();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (CostKind k : CostKind.values()) {
            totals.put(k.name(), BigDecimal.ZERO);
        }
        for (LedgerEntry e : list) {
            CostMath.toBase(e.getAmount(), e.getCurrency(), rates).ifPresent(v -> totals.merge(e.getKind(), v, BigDecimal::add));
        }
        Map<String, Money> money = new LinkedHashMap<>();
        totals.forEach((k, v) -> money.put(k, Money.of(v, support.baseCurrency())));
        audit.record("CLAIM_COSTS_READ", "CLAIM", claimId.toString(), AuditService.Outcome.SUCCESS, "rows=" + list.size());
        return new LedgerSummary(list.stream().map(this::view).toList(), money, support.baseCurrency());
    }

    // ------------------------------------------------------------------ roll-ups

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rollup(String groupBy, LocalDate from, LocalDate to) {
        Grouping g = grouping(groupBy);
        LocalDate f = from == null ? support.today().withDayOfYear(1) : from;
        LocalDate t = to == null ? support.today() : to;
        if (t.isBefore(f)) {
            throw ApiException.badRequest("invalid_range", "'to' must not be before 'from'");
        }
        Map<String, BigDecimal> rates = rates();
        List<CostMath.Item> items = new ArrayList<>();
        for (LedgerEntry e : ledger.findByIncurredOnBetweenOrderByIncurredOn(f, t)) {
            if (!INCURRED.contains(e.getKind())) {
                continue;
            }
            CostMath.toBase(e.getAmount(), e.getCurrency(), rates).ifPresent(v -> items.add(new CostMath.Item(e.getCaseId(), g.key.apply(e),
                    CostCategory.valueOf(e.getCategory()), v)));
        }
        String currency = support.baseCurrency();
        List<Map<String, Object>> out = new ArrayList<>();
        for (CostMath.Row r : CostMath.rollup(items, headcount(g), minGroupSize)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", r.key());
            row.put("label", label(g, r.key()));
            row.put("cases", r.cases());
            for (CostCategory c : CostCategory.values()) {
                row.put(c.name(), r.suppressed() ? null : Money.of(r.byCategory().get(c), currency));
            }
            row.put("total", r.suppressed() ? null : Money.of(r.total(), currency));
            row.put("costPerEmployee", r.suppressed() || r.costPerEmployee() == null ? null : Money.of(r.costPerEmployee(), currency));
            row.put("suppressed", r.suppressed());
            row.values().removeIf(java.util.Objects::isNull);
            out.add(row);
        }
        audit.record("CLAIM_COST_ROLLUP", "COST_LEDGER", null, AuditService.Outcome.SUCCESS, g.name + " rows=" + out.size());
        return out;
    }

    // ------------------------------------------------------------------ budgets

    @Transactional
    public BudgetView putBudget(CurrentUser user, int year, String unitCode, BigDecimal amount, String currency) {
        if (year < 2000 || year > 2100) {
            throw ApiException.badRequest("invalid_year", "Invalid year");
        }
        if (unitCode == null || !unitCode.matches("[A-Za-z0-9_\\-]{1,64}")) {
            throw ApiException.badRequest("invalid_unit", "Invalid unit code");
        }
        BigDecimal a = Money.scale(validAmount(amount));
        String cur = validCurrency(currency);
        Instant now = support.now();
        jdbc.update("""
                insert into claims.budget (budget_year, unit_code, amount, currency, updated_by, updated_at) values (?, ?, ?, ?, ?, ?)
                on conflict (budget_year, unit_code) do update set amount = excluded.amount, currency = excluded.currency,
                       updated_by = excluded.updated_by, updated_at = excluded.updated_at""", year, unitCode, a, cur, user.username(), Timestamp.from(now));
        audit.record("CLAIM_BUDGET_SET", "BUDGET", year + "/" + unitCode, AuditService.Outcome.SUCCESS, null);
        return new BudgetView(year, unitCode, Money.of(a, cur), user.username(), now);
    }

    @Transactional(readOnly = true)
    public List<BudgetVsActual> budgetVsActual(int year, String groupBy) {
        Grouping g = grouping(groupBy);
        Map<String, BigDecimal> rates = rates();
        String currency = support.baseCurrency();
        Map<String, BigDecimal> budgets = new TreeMap<>();
        Set<String> namespace = headcount(g).keySet();
        jdbc.query("select unit_code, amount, currency from claims.budget where budget_year = ?", rs -> {
            String unit = rs.getString("unit_code");
            if (namespace.contains(unit)) {
                CostMath.toBase(rs.getBigDecimal("amount"), rs.getString("currency"), rates).ifPresent(v -> budgets.put(unit, v));
            }
        }, year);
        Map<String, Map<Integer, BigDecimal>> actual = new TreeMap<>();
        for (LedgerEntry e : ledger.findByIncurredOnBetweenOrderByIncurredOn(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))) {
            if (INCURRED.contains(e.getKind())) {
                CostMath.toBase(e.getAmount(), e.getCurrency(), rates).ifPresent(v -> actual.computeIfAbsent(g.key.apply(e), k -> new HashMap<>())
                        .merge(e.getIncurredOn().getMonthValue(), v, BigDecimal::add));
            }
        }
        int lastMonth = year == support.today().getYear() ? support.today().getMonthValue() : year < support.today().getYear() ? 12 : 0;
        Set<String> units = new TreeSet<>(budgets.keySet());
        units.addAll(actual.keySet());
        List<BudgetVsActual> out = new ArrayList<>();
        for (String unit : units) {
            Map<Integer, BigDecimal> months = actual.getOrDefault(unit, Map.of());
            BigDecimal act = months.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal budget = budgets.get(unit);
            List<TrendPoint> trend = new ArrayList<>();
            for (int m = 1; m <= lastMonth; m++) {
                trend.add(new TrendPoint(YearMonth.of(year, m).toString(), Money.of(months.getOrDefault(m, BigDecimal.ZERO), currency)));
            }
            out.add(new BudgetVsActual(unit, label(g, unit), Money.of(budget, currency), Money.of(act, currency),
                    budget == null ? null : Money.of(act.subtract(budget), currency), CostMath.variancePct(budget, act), trend));
        }
        return out;
    }

    // ------------------------------------------------------------------ ERP export

    @Transactional
    public ExportView export(CurrentUser user, String month) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month == null ? "" : month);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("invalid_month", "month must be YYYY-MM");
        }
        if (ym.isAfter(YearMonth.from(support.today()))) {
            throw ClaimsSupport.unprocessable("month_not_closed", "Future months cannot be exported");
        }
        boolean exists = exports.findByExportMonthOrderByCreatedAtDesc(ym.toString()).stream()
                .anyMatch(x -> !ExportStatus.MISMATCH.name().equals(x.getStatus()));
        if (exists) {
            throw ApiException.conflict("export_exists", "An export for " + ym + " already exists");
        }
        Map<String, BigDecimal> rates = rates();
        List<ErpExport.Line> lines = new ArrayList<>();
        for (LedgerEntry e : ledger.findByIncurredOnBetweenOrderByIncurredOn(ym.atDay(1), ym.atEndOfMonth())) {
            if (INCURRED.contains(e.getKind())) {
                CostMath.toBase(e.getAmount(), e.getCurrency(), rates)
                        .ifPresent(v -> lines.add(new ErpExport.Line(e.getCostCentre(), e.getSubsidiaryCode(), e.getCategory(), v)));
            }
        }
        String currency = support.baseCurrency();
        ErpExport.Batch batch = ErpExport.build(ym.toString(), currency, lines);
        CostExport x = CostExport.create();
        x.setExportMonth(ym.toString());
        x.setBatchId("ERP-" + ym + "-" + x.getId().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        x.setRowCount(batch.rows().size());
        x.setTotal(batch.total());
        x.setCurrency(currency);
        x.setChecksum(batch.checksum());
        x.setStatus(ExportStatus.GENERATED.name());
        x.setContent(batch.csv());
        x.setCreatedBy(user.username());
        x.setCreatedAt(support.now());
        exports.save(x);
        bus.publish(new E08Events.CostExportGenerated(UUID.randomUUID().toString(), x.getBatchId(), x.getExportMonth(), x.getTotal().toPlainString(),
                currency, x.getRowCount(), x.getChecksum()), E08Events.ERP_FINANCE);
        audit.record("CLAIM_COST_EXPORT", "COST_EXPORT", x.getId().toString(), AuditService.Outcome.SUCCESS, ym + " rows=" + x.getRowCount());
        return view(x);
    }

    @Transactional(readOnly = true)
    public List<ExportView> exports() {
        return exports.findAllByOrderByCreatedAtDesc().stream().map(CostService::view).toList();
    }

    @Transactional(readOnly = true)
    public CostExport exportContent(UUID id) {
        CostExport x = exports.findById(id).orElseThrow(() -> ApiException.notFound("Export"));
        audit.record("CLAIM_COST_EXPORT_DOWNLOAD", "COST_EXPORT", id.toString(), AuditService.Outcome.SUCCESS, x.getChecksum());
        return x;
    }

    @Transactional
    public ExportView reconcileExport(CurrentUser user, UUID id, BigDecimal erpTotal, String currency, String erpReference) {
        CostExport x = exports.findById(id).orElseThrow(() -> ApiException.notFound("Export"));
        if (erpTotal == null) {
            throw ApiException.badRequest("invalid_erp_total", "erpTotal is required");
        }
        if (currency != null && !currency.equalsIgnoreCase(x.getCurrency())) {
            throw ClaimsSupport.unprocessable("currency_mismatch", "erpTotal must be in the export currency " + x.getCurrency());
        }
        if (erpReference == null || !erpReference.matches("[A-Za-z0-9_./:\\-]{1,100}")) {
            throw ApiException.badRequest("invalid_erp_reference", "erpReference is required (letters, digits, _ . / : -)");
        }
        BigDecimal erp = Money.scale(erpTotal);
        x.setErpTotal(erp);
        x.setErpReference(erpReference);
        x.setStatus(erp.compareTo(x.getTotal()) == 0 ? ExportStatus.RECONCILED.name() : ExportStatus.MISMATCH.name());
        x.setReconciledAt(support.now());
        x.setReconciledBy(user.username());
        audit.record("CLAIM_COST_EXPORT_RECONCILE", "COST_EXPORT", id.toString(), AuditService.Outcome.SUCCESS, x.getStatus());
        return view(x);
    }

    // ------------------------------------------------------------------ closed-claim reconciliation

    @Transactional(readOnly = true)
    public List<ReconciliationRow> reconciliation(String month) {
        YearMonth ym;
        try {
            ym = month == null ? YearMonth.from(support.today()) : YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("invalid_month", "month must be YYYY-MM");
        }
        Instant from = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, BigDecimal> rates = rates();
        String currency = support.baseCurrency();
        List<ReconciliationRow> out = new ArrayList<>();
        for (Claim c : claims.findAllByOrderByNotifiedAtDesc()) {
            if (!ClaimStatus.CLOSED.name().equals(c.getStatus()) || c.getClosedAt() == null || c.getClosedAt().isBefore(from)
                    || !c.getClosedAt().isBefore(to)) {
                continue;
            }
            BigDecimal paid = c.getPaidTotal() == null ? BigDecimal.ZERO
                    : CostMath.toBase(c.getPaidTotal(), c.getPaidCurrency(), rates).orElse(c.getPaidTotal());
            BigDecimal payments = BigDecimal.ZERO;
            for (LedgerEntry e : ledger.findForClaim(c.getId(), c.getCaseId())) {
                if (CostKind.PAYMENT.name().equals(e.getKind())) {
                    payments = payments.add(CostMath.toBase(e.getAmount(), e.getCurrency(), rates).orElse(BigDecimal.ZERO));
                }
            }
            CostMath.Reconciliation r = CostMath.reconcile(paid, payments);
            out.add(new ReconciliationRow(c.getClaimRef(), Money.of(r.paidTotal(), currency), Money.of(r.ledgerTotal(), currency),
                    Money.of(r.difference(), currency), r.reconciled()));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private record Grouping(String name, Function<LedgerEntry, String> key, Function<Employee, String> employeeKey) {
    }

    private static Grouping grouping(String groupBy) {
        String g = groupBy == null ? "SUBSIDIARY" : groupBy.toUpperCase(Locale.ROOT);
        return switch (g) {
            case "SUBSIDIARY" -> new Grouping(g, LedgerEntry::getSubsidiaryCode, Employee::getSubsidiaryCode);
            case "DEPARTMENT" -> new Grouping(g, LedgerEntry::getDepartmentCode, Employee::getDepartmentCode);
            case "COST_CENTRE" -> new Grouping(g, LedgerEntry::getCostCentre, Employee::getCostCentre);
            default -> throw ApiException.badRequest("invalid_group_by", "groupBy must be SUBSIDIARY, DEPARTMENT or COST_CENTRE");
        };
    }

    /** Active headcount per unit (cost per employee), also the namespace of valid unit codes for budgets. */
    private Map<String, Long> headcount(Grouping g) {
        Map<String, Long> out = new HashMap<>();
        for (Employee e : support.allEmployees()) {
            String k = g.employeeKey.apply(e);
            if (e.isActive() && k != null) {
                out.merge(k, 1L, Long::sum);
            }
        }
        return out;
    }

    private String label(Grouping g, String key) {
        return "DEPARTMENT".equals(g.name) ? support.departmentName(key) : key;
    }

    private Map<String, BigDecimal> rates() {
        return CostMath.rates(support.config("claims.fx-rates", ""), support.baseCurrency());
    }

    private String validCurrency(String currency) {
        String c = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (!c.matches("[A-Z]{3}") || !rates().containsKey(c)) {
            throw ClaimsSupport.unprocessable("currency_not_supported", "Unsupported currency (no conversion rate configured)");
        }
        return c;
    }

    private static BigDecimal validAmount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.compareTo(new BigDecimal("1000000000")) >= 0 || amount.scale() > 2) {
            throw ApiException.badRequest("invalid_amount", "amount must be between 0 and 999,999,999.99 with at most two decimals");
        }
        return amount;
    }

    private void snapshotUnit(LedgerEntry e, OhCase oc) {
        Employee emp = support.employee(e.getEmployeeId()).orElse(null);
        e.setSubsidiaryCode(oc.getSubsidiaryCode() != null ? oc.getSubsidiaryCode() : emp == null ? null : emp.getSubsidiaryCode());
        e.setDepartmentCode(emp != null && emp.getDepartmentCode() != null ? emp.getDepartmentCode() : oc.getOrgUnitCode());
        e.setCostCentre(oc.getCostCentre() != null ? oc.getCostCentre() : emp == null ? null : emp.getCostCentre());
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String v, String field) {
        try {
            return Enum.valueOf(type, v == null ? "" : v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_" + field, "Invalid value for '" + field + "'");
        }
    }

    private LedgerView view(LedgerEntry e) {
        return new LedgerView(e.getId(), e.getClaimId(), e.getClaimId() == null ? "CASE" : "CLAIM", e.getCategory(), e.getKind(),
                Money.of(e.getAmount(), e.getCurrency()), e.getIncurredOn(), e.getProviderCode(), e.getDescription(), e.getInvoiceNumber(),
                e.getSourceType(), e.getSourceRef(), e.getCreatedBy(), e.getCreatedAt());
    }

    private static ExportView view(CostExport x) {
        return new ExportView(x.getId(), x.getExportMonth(), x.getBatchId(), x.getRowCount(), Money.of(x.getTotal(), x.getCurrency()), x.getChecksum(),
                x.getStatus(), Money.of(x.getErpTotal(), x.getCurrency()), x.getErpReference(), x.getReconciledAt(), x.getReconciledBy(),
                x.getCreatedBy(), x.getCreatedAt());
    }
}
