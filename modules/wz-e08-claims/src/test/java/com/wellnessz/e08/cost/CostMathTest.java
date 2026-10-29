package com.wellnessz.e08.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wellnessz.e08.model.ClaimEnums.CostCategory;

/** Currency conversion, unit roll-up with small-group suppression, reconciliation, budget variance and the ERP export checksum. */
class CostMathTest {

    static final Map<String, BigDecimal> RATES = CostMath.rates("AED:0.21, usd:0.79, bad, XYZ:-1, EUR:x", "GBP");

    @Test
    void ratesParseConfigurationAndAlwaysContainTheBase() {
        assertThat(RATES).containsEntry("GBP", BigDecimal.ONE).containsEntry("AED", new BigDecimal("0.21")).containsEntry("USD", new BigDecimal("0.79"))
                .doesNotContainKeys("XYZ", "EUR");
        assertThat(CostMath.toBase(new BigDecimal("1000"), "AED", RATES)).contains(new BigDecimal("210.00"));
        assertThat(CostMath.toBase(new BigDecimal("10"), "CHF", RATES)).isEmpty();
    }

    @Test
    void rollupCountsCasesSumsCategoriesAndSuppressesSmallGroups() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        List<CostMath.Item> items = List.of(
                new CostMath.Item(a, "GND", CostCategory.MEDICAL, new BigDecimal("100.00")),
                new CostMath.Item(a, "GND", CostCategory.MEDICAL, new BigDecimal("50.50")),
                new CostMath.Item(b, "GND", CostCategory.INDEMNITY, new BigDecimal("1000")),
                new CostMath.Item(c, "GND", CostCategory.REHAB, new BigDecimal("49.50")),
                new CostMath.Item(d, "ENG", CostCategory.MEDICAL, new BigDecimal("330")));
        List<CostMath.Row> rows = CostMath.rollup(items, Map.of("GND", 4L, "ENG", 1L), 3);
        assertThat(rows).extracting(CostMath.Row::key).containsExactly("ENG", "GND");
        CostMath.Row eng = rows.get(0);
        assertThat(eng.suppressed()).isTrue();
        assertThat(eng.cases()).isNull();
        assertThat(eng.total()).isNull();
        assertThat(eng.byCategory()).isNull();
        CostMath.Row gnd = rows.get(1);
        assertThat(gnd.suppressed()).isFalse();
        assertThat(gnd.cases()).isEqualTo(3);
        assertThat(gnd.byCategory().get(CostCategory.MEDICAL)).isEqualByComparingTo("150.50");
        assertThat(gnd.byCategory().get(CostCategory.LEGAL)).isEqualByComparingTo("0");
        assertThat(gnd.total()).isEqualByComparingTo("1200.00");
        assertThat(gnd.costPerEmployee()).isEqualByComparingTo("300.00");
    }

    @Test
    void entriesWithoutUnitRollUpAsUnassigned() {
        List<CostMath.Row> rows = CostMath.rollup(List.of(new CostMath.Item(UUID.randomUUID(), null, CostCategory.ADMIN, BigDecimal.TEN)), Map.of(), 1);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.key()).isEqualTo("UNASSIGNED");
            assertThat(r.costPerEmployee()).isNull();
        });
    }

    @Test
    void reconciliationAndVariance() {
        CostMath.Reconciliation ok = CostMath.reconcile(new BigDecimal("980"), new BigDecimal("980.00"));
        assertThat(ok.reconciled()).isTrue();
        assertThat(ok.difference()).isEqualByComparingTo("0");
        CostMath.Reconciliation off = CostMath.reconcile(new BigDecimal("1250"), new BigDecimal("1000"));
        assertThat(off.reconciled()).isFalse();
        assertThat(off.difference()).isEqualByComparingTo("-250.00");
        assertThat(CostMath.reconcile(null, null).reconciled()).isTrue();
        assertThat(CostMath.variancePct(new BigDecimal("1000"), new BigDecimal("1250"))).isEqualByComparingTo("25.0");
        assertThat(CostMath.variancePct(null, BigDecimal.ONE)).isNull();
    }

    // ------------------------------------------------------------------ ERP export

    @Test
    void exportGroupsByUnitAndCategoryWithAChecksumOverTheExactCsv() throws Exception {
        ErpExport.Batch batch = ErpExport.build("2026-09", "GBP", List.of(
                new ErpExport.Line("CC-GND-400", "GND", "MEDICAL", new BigDecimal("850")),
                new ErpExport.Line("CC-GND-400", "GND", "MEDICAL", new BigDecimal("95")),
                new ErpExport.Line("CC-CAT-110", "CAT", "MEDICAL", new BigDecimal("120")),
                new ErpExport.Line("CC-GND-400", "GND", "ADMIN", new BigDecimal("75.5"))));
        assertThat(batch.rows()).hasSize(3);
        assertThat(batch.total()).isEqualByComparingTo("1140.50");
        assertThat(batch.csv()).isEqualTo(ErpExport.HEADER + "\n"
                + "2026-09,CC-CAT-110,CAT,MEDICAL,GBP,120.00,1\n"
                + "2026-09,CC-GND-400,GND,ADMIN,GBP,75.50,1\n"
                + "2026-09,CC-GND-400,GND,MEDICAL,GBP,945.00,2\n");
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(batch.csv().getBytes(StandardCharsets.UTF_8)));
        assertThat(batch.checksum()).isEqualTo(expected).hasSize(64);
        // deterministic: the same lines in another order give the same file and checksum
        ErpExport.Batch again = ErpExport.build("2026-09", "GBP", List.of(
                new ErpExport.Line("CC-GND-400", "GND", "ADMIN", new BigDecimal("75.5")),
                new ErpExport.Line("CC-CAT-110", "CAT", "MEDICAL", new BigDecimal("120")),
                new ErpExport.Line("CC-GND-400", "GND", "MEDICAL", new BigDecimal("95")),
                new ErpExport.Line("CC-GND-400", "GND", "MEDICAL", new BigDecimal("850"))));
        assertThat(again.checksum()).isEqualTo(batch.checksum());
    }

    @Test
    void emptyExportAndFormulaInjectionGuard() {
        ErpExport.Batch empty = ErpExport.build("2026-08", "GBP", List.of());
        assertThat(empty.rows()).isEmpty();
        assertThat(empty.total()).isEqualByComparingTo("0");
        assertThat(empty.csv()).isEqualTo(ErpExport.HEADER + "\n");
        assertThat(ErpExport.cell("=HYPERLINK(1)")).isEqualTo("'=HYPERLINK(1)");
        assertThat(ErpExport.cell("a,b")).isEqualTo("\"a,b\"");
    }
}
