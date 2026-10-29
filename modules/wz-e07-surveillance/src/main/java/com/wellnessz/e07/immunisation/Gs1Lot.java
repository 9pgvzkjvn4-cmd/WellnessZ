package com.wellnessz.e07.immunisation;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wellnessz.common.error.ApiException;

/**
 * GS1 vaccine-pack barcode parsing for fast immunisation entry: human-readable form {@code (01)GTIN(17)YYMMDD(10)LOT} or the
 * raw scanner form {@code 01…17YYMMDD10LOT<GS>21…} (optional symbology prefix {@code ]d2} / {@code ]C1}). AI 17 is the
 * expiry date; day {@code 00} means the last day of the month.
 */
public final class Gs1Lot {

    private static final Pattern PAREN = Pattern.compile("\\((\\d{2,4})\\)([^()]*)");
    private static final char GS = '\u001d';
    private static final Pattern LOT = Pattern.compile("[A-Za-z0-9\\-./]{1,20}");

    private Gs1Lot() {
    }

    public record Lot(String gtin, String lotNumber, LocalDate expiry, String serial) {
    }

    public static Lot parse(String barcode) {
        if (barcode == null || barcode.isBlank() || barcode.length() > 120) {
            throw ApiException.badRequest("invalid_barcode", "Scan a GS1 vaccine barcode");
        }
        String s = barcode.strip();
        if (s.startsWith("]d2") || s.startsWith("]C1") || s.startsWith("]Q3")) {
            s = s.substring(3);
        }
        String gtin = null;
        String lot = null;
        String expiry = null;
        String serial = null;
        if (s.startsWith("(")) {
            Matcher m = PAREN.matcher(s);
            int end = 0;
            while (m.find()) {
                if (m.start() != end) {
                    throw ApiException.badRequest("invalid_barcode", "Unreadable GS1 barcode");
                }
                end = m.end();
                String ai = m.group(1);
                String v = m.group(2).strip();
                switch (ai) {
                    case "01" -> gtin = v;
                    case "10" -> lot = v;
                    case "17" -> expiry = v;
                    case "21" -> serial = v;
                    default -> {
                        // other application identifiers (e.g. 11 production date) are ignored
                    }
                }
            }
            if (end != s.length()) {
                throw ApiException.badRequest("invalid_barcode", "Unreadable GS1 barcode");
            }
        } else {
            int i = 0;
            while (i < s.length()) {
                if (s.charAt(i) == GS) {
                    i++;
                    continue;
                }
                if (i + 2 > s.length()) {
                    throw ApiException.badRequest("invalid_barcode", "Unreadable GS1 barcode");
                }
                String ai = s.substring(i, i + 2);
                i += 2;
                int fixed = switch (ai) {
                    case "01" -> 14;
                    case "11", "15", "17" -> 6;
                    default -> -1;
                };
                String v;
                if (fixed > 0) {
                    if (i + fixed > s.length()) {
                        throw ApiException.badRequest("invalid_barcode", "Unreadable GS1 barcode");
                    }
                    v = s.substring(i, i + fixed);
                    i += fixed;
                } else if ("10".equals(ai) || "21".equals(ai)) {
                    int stop = s.indexOf(GS, i);
                    int e = stop < 0 ? s.length() : stop;
                    v = s.substring(i, e);
                    i = e;
                } else {
                    throw ApiException.badRequest("invalid_barcode", "Unsupported GS1 application identifier");
                }
                switch (ai) {
                    case "01" -> gtin = v;
                    case "10" -> lot = v;
                    case "17" -> expiry = v;
                    case "21" -> serial = v;
                    default -> {
                    }
                }
            }
        }
        if (lot == null || !LOT.matcher(lot).matches()) {
            throw ApiException.badRequest("invalid_barcode", "The barcode does not contain a lot number (AI 10)");
        }
        if (gtin != null && !gtin.matches("\\d{14}")) {
            throw ApiException.badRequest("invalid_barcode", "Invalid GTIN (AI 01)");
        }
        return new Lot(gtin, lot, expiry == null ? null : expiry(expiry), serial);
    }

    /** {@code YYMMDD} → date (century 2000; day 00 = last day of the month). */
    static LocalDate expiry(String yymmdd) {
        if (!yymmdd.matches("\\d{6}")) {
            throw ApiException.badRequest("invalid_barcode", "Invalid expiry date (AI 17)");
        }
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int mm = Integer.parseInt(yymmdd.substring(2, 4));
        int dd = Integer.parseInt(yymmdd.substring(4, 6));
        if (mm < 1 || mm > 12) {
            throw ApiException.badRequest("invalid_barcode", "Invalid expiry date (AI 17)");
        }
        YearMonth ym = YearMonth.of(2000 + yy, mm);
        if (dd == 0) {
            return ym.atEndOfMonth();
        }
        if (dd > ym.lengthOfMonth()) {
            throw ApiException.badRequest("invalid_barcode", "Invalid expiry date (AI 17)");
        }
        return ym.atDay(dd);
    }
}
