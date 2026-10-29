package com.wellnessz.e08.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Monthly ERP finance export (E08-F05): incurred costs grouped by cost centre, subsidiary and category in the reporting
 * currency – units and amounts only, never names or employee identifiers. The checksum is the SHA-256 of the exact CSV bytes,
 * so finance can verify the file received matches the batch announced on {@code erp-finance}.
 */
public final class ErpExport {

    public static final String HEADER = "month,cost_centre,subsidiary,category,currency,amount,entries";

    public record Line(String costCentre, String subsidiary, String category, BigDecimal amount) {
    }

    public record Row(String month, String costCentre, String subsidiary, String category, String currency, BigDecimal amount, int entries) {
    }

    public record Batch(List<Row> rows, BigDecimal total, String csv, String checksum) {
    }

    private ErpExport() {
    }

    public static Batch build(String month, String currency, Collection<Line> lines) {
        Map<String, Row> grouped = new TreeMap<>();
        for (Line l : lines) {
            String cc = l.costCentre() == null ? "UNASSIGNED" : l.costCentre();
            String sub = l.subsidiary() == null ? "UNASSIGNED" : l.subsidiary();
            String key = cc + '\u0000' + sub + '\u0000' + l.category();
            grouped.merge(key, new Row(month, cc, sub, l.category(), currency, l.amount(), 1),
                    (a, b) -> new Row(month, cc, sub, l.category(), currency, a.amount().add(b.amount()), a.entries() + b.entries()));
        }
        List<Row> rows = grouped.values().stream()
                .map(r -> new Row(r.month(), r.costCentre(), r.subsidiary(), r.category(), r.currency(), r.amount().setScale(2, RoundingMode.HALF_UP),
                        r.entries())).toList();
        BigDecimal total = rows.stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (Row r : rows) {
            csv.append(cell(r.month())).append(',').append(cell(r.costCentre())).append(',').append(cell(r.subsidiary())).append(',')
                    .append(cell(r.category())).append(',').append(cell(r.currency())).append(',').append(r.amount().toPlainString()).append(',')
                    .append(r.entries()).append('\n');
        }
        String text = csv.toString();
        return new Batch(rows, total, text, sha256(text));
    }

    /** CSV cell: quoted when needed, and neutralised against spreadsheet formula injection. */
    static String cell(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
