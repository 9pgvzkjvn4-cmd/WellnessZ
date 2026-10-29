package com.wellnessz.e08.web;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e08.cost.CostService;
import com.wellnessz.e08.model.CostExport;
import com.wellnessz.e08.support.Money;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E08-F05 cost ledger, unit roll-ups, budgets and the ERP finance export. */
@RestController("e08ClaimCostController")
@RequestMapping("/api/v1")
public class ClaimCostController {

    private final CostService costs;
    private final CurrentUserProvider users;

    public ClaimCostController(CostService costs, CurrentUserProvider users) {
        this.costs = costs;
        this.users = users;
    }

    public record CostBody(@NotBlank @Pattern(regexp = Patterns.CATEGORY) String category, @NotBlank @Pattern(regexp = Patterns.KIND) String kind,
                           @NotNull BigDecimal amount, @NotBlank @Pattern(regexp = Patterns.CURRENCY) String currency, @NotNull LocalDate incurredOn,
                           @Size(max = 500) String description, @Pattern(regexp = "|[A-Za-z0-9_./\\-]{1,60}") String invoiceNumber) {
    }

    @PostMapping("/claims/{id}/costs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','FINANCE')")
    public CostService.LedgerView add(@PathVariable UUID id, @Valid @RequestBody CostBody b) {
        return costs.add(users.require(), id, new CostService.CostCommand(b.category(), b.kind(), b.amount(), b.currency(), b.incurredOn(),
                b.description(), b.invoiceNumber() == null || b.invoiceNumber().isBlank() ? null : b.invoiceNumber()));
    }

    @GetMapping("/claims/{id}/costs")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','FINANCE')")
    public List<CostService.LedgerView> ledger(@PathVariable UUID id) {
        return costs.ledger(users.require(), id).entries();
    }

    /** Ledger with totals per kind in the reporting currency (claim cost tab summary). */
    @GetMapping("/claims/{id}/costs/summary")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','FINANCE')")
    public CostService.LedgerSummary summary(@PathVariable UUID id) {
        return costs.ledger(users.require(), id);
    }

    @GetMapping("/claims/costs/rollup")
    @PreAuthorize("hasAnyRole('FINANCE','CMO','CLAIMS_COORDINATOR')")
    public List<Map<String, Object>> rollup(@RequestParam(defaultValue = "SUBSIDIARY") @Pattern(regexp = Patterns.GROUP_BY) String groupBy,
                                            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return costs.rollup(groupBy, from, to);
    }

    public record BudgetBody(@NotNull BigDecimal amount, @NotBlank @Pattern(regexp = Patterns.CURRENCY) String currency) {
    }

    @PutMapping("/claims/budgets/{year}/{unitCode}")
    @PreAuthorize("hasRole('FINANCE')")
    public CostService.BudgetView budget(@PathVariable @Min(2000) @Max(2100) int year, @PathVariable @Pattern(regexp = Patterns.UNIT) String unitCode,
                                         @Valid @RequestBody BudgetBody b) {
        return costs.putBudget(users.require(), year, unitCode, b.amount(), b.currency());
    }

    @GetMapping("/claims/costs/budget-vs-actual")
    @PreAuthorize("hasAnyRole('FINANCE','CMO')")
    public List<CostService.BudgetVsActual> budgetVsActual(@RequestParam @Min(2000) @Max(2100) int year,
                                                           @RequestParam(defaultValue = "SUBSIDIARY") @Pattern(regexp = Patterns.GROUP_BY) String groupBy) {
        return costs.budgetVsActual(year, groupBy);
    }

    public record ExportBody(@NotBlank @Pattern(regexp = Patterns.MONTH) String month) {
    }

    @PostMapping("/claims/costs/exports")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('FINANCE')")
    public CostService.ExportView export(@Valid @RequestBody ExportBody b) {
        return costs.export(users.require(), b.month());
    }

    @GetMapping("/claims/costs/exports")
    @PreAuthorize("hasRole('FINANCE')")
    public List<CostService.ExportView> exports() {
        return costs.exports();
    }

    @GetMapping("/claims/costs/exports/{id}/content")
    @PreAuthorize("hasRole('FINANCE')")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        CostExport x = costs.exportContent(id);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"claims-costs-" + x.getExportMonth() + ".csv\"")
                .header("X-Checksum-SHA256", x.getChecksum())
                .body(x.getContent().getBytes(StandardCharsets.UTF_8));
    }

    /** {@code erpTotal} is a money object {@code {amount, currency}} in the export's currency. */
    public record ReconcileBody(@NotNull Money erpTotal, @NotBlank @Pattern(regexp = "[A-Za-z0-9_./:\\-]{1,100}") String erpReference) {
    }

    @PostMapping("/claims/costs/exports/{id}/reconcile")
    @PreAuthorize("hasRole('FINANCE')")
    public CostService.ExportView reconcile(@PathVariable UUID id, @Valid @RequestBody ReconcileBody b) {
        BigDecimal amount;
        try {
            amount = b.erpTotal().amount() == null ? null : new BigDecimal(b.erpTotal().amount().trim());
        } catch (NumberFormatException e) {
            throw com.wellnessz.common.error.ApiException.badRequest("invalid_erp_total", "erpTotal.amount must be a decimal string");
        }
        return costs.reconcileExport(users.require(), id, amount, b.erpTotal().currency(), b.erpReference());
    }
}
