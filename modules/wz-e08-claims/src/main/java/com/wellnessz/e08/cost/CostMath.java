package com.wellnessz.e08.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.wellnessz.e08.model.ClaimEnums.CostCategory;

/**
 * Pure cost arithmetic (E08-F05): conversion to the reporting currency, unit roll-ups with small-group suppression (units with
 * fewer cases than the minimum group size publish no amounts and no case count) and the reconciliation of paid totals.
 */
public final class CostMath {

    private CostMath() {
    }

    // ------------------------------------------------------------------ currency

    /** Parses {@code "GBP:1,AED:0.21"} into rates to the base currency; the base itself is always 1. */
    public static Map<String, BigDecimal> rates(String config, String base) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (config != null) {
            for (String part : config.split(",")) {
                String[] kv = part.trim().split(":");
                if (kv.length == 2 && kv[0].trim().matches("[A-Za-z]{3}")) {
                    try {
                        BigDecimal r = new BigDecimal(kv[1].trim());
                        if (r.signum() > 0) {
                            out.put(kv[0].trim().toUpperCase(Locale.ROOT), r);
                        }
                    } catch (NumberFormatException e) {
                        // ignore malformed rate
                    }
                }
            }
        }
        out.put(base, BigDecimal.ONE);
        return out;
    }

    /** Amount in the base currency, or empty when there is no rate for the currency. */
    public static Optional<BigDecimal> toBase(BigDecimal amount, String currency, Map<String, BigDecimal> rates) {
        BigDecimal r = rates.get(currency);
        return r == null ? Optional.empty() : Optional.of(amount.multiply(r).setScale(2, RoundingMode.HALF_UP));
    }

    // ------------------------------------------------------------------ roll-up

    /** One incurred cost converted to the base currency, attributed to a unit. */
    public record Item(UUID caseId, String unitKey, CostCategory category, BigDecimal amount) {
    }

    /** Roll-up row; amounts and the case count are {@code null} when the row is suppressed. */
    public record Row(String key, Integer cases, Map<CostCategory, BigDecimal> byCategory, BigDecimal total, BigDecimal costPerEmployee,
                      boolean suppressed) {
    }

    public static List<Row> rollup(Collection<Item> items, Map<String, Long> headcount, int minGroupSize) {
        Map<String, Set<UUID>> cases = new TreeMap<>();
        Map<String, Map<CostCategory, BigDecimal>> sums = new TreeMap<>();
        for (Item i : items) {
            String key = i.unitKey() == null ? "UNASSIGNED" : i.unitKey();
            cases.computeIfAbsent(key, k -> new HashSet<>()).add(i.caseId());
            Map<CostCategory, BigDecimal> m = sums.computeIfAbsent(key, k -> emptyCategories());
            m.merge(i.category(), i.amount(), BigDecimal::add);
        }
        return cases.keySet().stream().map(key -> {
            int n = cases.get(key).size();
            Map<CostCategory, BigDecimal> m = sums.get(key);
            BigDecimal total = m.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            if (n < minGroupSize) {
                return new Row(key, null, null, null, null, true);
            }
            long heads = headcount.getOrDefault(key, 0L);
            BigDecimal perEmployee = heads <= 0 ? null : total.divide(BigDecimal.valueOf(heads), 2, RoundingMode.HALF_UP);
            Map<CostCategory, BigDecimal> scaled = new LinkedHashMap<>();
            m.forEach((k, v) -> scaled.put(k, v.setScale(2, RoundingMode.HALF_UP)));
            return new Row(key, n, scaled, total, perEmployee, false);
        }).toList();
    }

    static Map<CostCategory, BigDecimal> emptyCategories() {
        Map<CostCategory, BigDecimal> m = new LinkedHashMap<>();
        for (CostCategory c : CostCategory.values()) {
            m.put(c, BigDecimal.ZERO);
        }
        return m;
    }

    // ------------------------------------------------------------------ reconciliation

    /** Claim paid total (insurer / TPA statement) versus PAYMENT entries in the ledger; reconciled when they agree to the penny. */
    public record Reconciliation(BigDecimal paidTotal, BigDecimal ledgerTotal, BigDecimal difference, boolean reconciled) {
    }

    public static Reconciliation reconcile(BigDecimal paidTotal, BigDecimal ledgerTotal) {
        BigDecimal paid = paidTotal == null ? BigDecimal.ZERO : paidTotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal ledger = ledgerTotal == null ? BigDecimal.ZERO : ledgerTotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal diff = ledger.subtract(paid);
        return new Reconciliation(paid, ledger, diff, diff.signum() == 0);
    }

    /** Variance of actual against budget in percent (one decimal), {@code null} without a budget. */
    public static BigDecimal variancePct(BigDecimal budget, BigDecimal actual) {
        if (budget == null || budget.signum() == 0) {
            return null;
        }
        return actual.subtract(budget).multiply(BigDecimal.valueOf(100)).divide(budget, 1, RoundingMode.HALF_UP);
    }
}
