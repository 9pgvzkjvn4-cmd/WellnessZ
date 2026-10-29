package com.wellnessz.e06.certificate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure certificate checks (E06-F03): field extraction from the OCR / PDF text, duplicate detection and alteration flags.
 *
 * <ul>
 *   <li>Duplicate: same SHA-256, or same issuer registration + certificate number, or same employee with identical dates.</li>
 *   <li>Alteration flags: {@code DATES_DIFFER_FROM_OCR}, {@code ISSUE_AFTER_START_PLUS_7}, {@code PDF_MODIFIED_AFTER_CREATION},
 *       {@code UNREADABLE}.</li>
 * </ul>
 */
public final class CertificateChecks {

    public static final String DATES_DIFFER_FROM_OCR = "DATES_DIFFER_FROM_OCR";
    public static final String ISSUE_AFTER_START_PLUS_7 = "ISSUE_AFTER_START_PLUS_7";
    public static final String PDF_MODIFIED_AFTER_CREATION = "PDF_MODIFIED_AFTER_CREATION";
    public static final String UNREADABLE = "UNREADABLE";

    static final int MAX_TEXT = 20_000;
    private static final String DATE = "(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/\\d{4}|\\d{1,2}\\.\\d{1,2}\\.\\d{4})";
    private static final Pattern ISSUER = Pattern.compile("(?i)(?:issued by|issuer|doctor|physician|signed)\\s*:?\\s*((?:dr\\.?\\s+)?[A-Z][A-Za-z.'-]{1,40}(?: [A-Z][A-Za-z.'-]{1,40}){0,3})");
    private static final Pattern REGISTRATION = Pattern.compile("(?i)(?:GMC|registration(?: no\\.?| number)?|reg\\.? no\\.?|licen[cs]e(?: no\\.?)?)\\s*:?\\s*([A-Z0-9-]{4,15})");
    private static final Pattern ISSUE_DATE = Pattern.compile("(?i)(?:issue date|date of issue|issued on|dated)\\s*:?\\s*" + DATE);
    private static final Pattern FROM_DATE = Pattern.compile("(?i)\\bfrom\\s*:?\\s*" + DATE);
    private static final Pattern TO_DATE = Pattern.compile("(?i)\\b(?:to|until)\\s*:?\\s*" + DATE);
    private static final Pattern PDF_DATE = Pattern.compile("/(CreationDate|ModDate)\\s*\\(D:(\\d{14})");
    private static final DateTimeFormatter PDF_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);

    private CertificateChecks() {
    }

    /** Fields read from the certificate text. Every value may be {@code null}. */
    public record Extracted(String issuerName, LocalDate issueDate, LocalDate fromDate, LocalDate toDate, String registration) {

        static boolean blank(Extracted x) {
            return x.issuerName() == null && x.issueDate() == null && x.fromDate() == null && x.toDate() == null && x.registration() == null;
        }
    }

    /** A certificate already on file, for duplicate detection. */
    public record Known(UUID id, String employeeId, String sha256, String issuerRegistration, String certificateNumber, LocalDate fromDate,
                        LocalDate toDate) {
    }

    /** The certificate being registered. */
    public record Candidate(String employeeId, String sha256, String issuerRegistration, String certificateNumber, LocalDate fromDate,
                            LocalDate toDate) {
    }

    // ------------------------------------------------------------------ extraction

    public static Extracted extract(String text) {
        if (text == null || text.isBlank()) {
            return new Extracted(null, null, null, null, null);
        }
        String t = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        String issuer = first(ISSUER, t).map(s -> s.strip()).orElse(null);
        String registration = first(REGISTRATION, t).map(s -> s.toUpperCase(Locale.ROOT)).orElse(null);
        return new Extracted(issuer, first(ISSUE_DATE, t).flatMap(CertificateChecks::date).orElse(null),
                first(FROM_DATE, t).flatMap(CertificateChecks::date).orElse(null),
                first(TO_DATE, t).flatMap(CertificateChecks::date).orElse(null), registration);
    }

    private static Optional<String> first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    static Optional<LocalDate> date(String s) {
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return Optional.of(LocalDate.parse(s));
            }
            String[] p = s.split("[/.]");
            return Optional.of(LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0])));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ duplicates

    /** The first known certificate the candidate duplicates, if any. */
    public static Optional<UUID> duplicateOf(Candidate c, Collection<Known> known) {
        for (Known k : known) {
            if (c.sha256() != null && c.sha256().equals(k.sha256())) {
                return Optional.of(k.id());
            }
            if (c.certificateNumber() != null && c.certificateNumber().equalsIgnoreCase(Objects.toString(k.certificateNumber(), ""))
                    && (c.issuerRegistration() == null || k.issuerRegistration() == null
                        || c.issuerRegistration().equalsIgnoreCase(k.issuerRegistration()))) {
                return Optional.of(k.id());
            }
            if (c.employeeId() != null && c.employeeId().equals(k.employeeId()) && c.fromDate() != null && c.fromDate().equals(k.fromDate())
                    && c.toDate() != null && c.toDate().equals(k.toDate())) {
                return Optional.of(k.id());
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ alteration flags

    /**
     * @param declaredFrom dates entered by the uploader / sender
     * @param issueDate    declared issue date (falls back to the OCR issue date)
     * @param readable     whether text could be extracted from the file (false → {@code UNREADABLE}); {@code null} when no file
     * @param pdf          the raw file when it is a PDF, otherwise {@code null}
     */
    public static List<String> alterationFlags(LocalDate declaredFrom, LocalDate declaredTo, LocalDate issueDate, Extracted ocr, Boolean readable,
                                               byte[] pdf) {
        List<String> flags = new ArrayList<>();
        Extracted x = ocr == null ? new Extracted(null, null, null, null, null) : ocr;
        if ((x.fromDate() != null && !x.fromDate().equals(declaredFrom)) || (x.toDate() != null && !x.toDate().equals(declaredTo))) {
            flags.add(DATES_DIFFER_FROM_OCR);
        }
        LocalDate issued = issueDate != null ? issueDate : x.issueDate();
        if (issued != null && declaredFrom != null && issued.isAfter(declaredFrom.plusDays(7))) {
            flags.add(ISSUE_AFTER_START_PLUS_7);
        }
        if (pdf != null && pdfModifiedAfterCreation(pdf)) {
            flags.add(PDF_MODIFIED_AFTER_CREATION);
        }
        if (Boolean.FALSE.equals(readable)) {
            flags.add(UNREADABLE);
        }
        return flags;
    }

    /** PDF metadata: a ModDate more than a minute after the CreationDate, or an incremental update (several %%EOF markers). */
    static boolean pdfModifiedAfterCreation(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        LocalDateTime created = null;
        LocalDateTime modified = null;
        Matcher m = PDF_DATE.matcher(s);
        while (m.find()) {
            try {
                LocalDateTime ts = LocalDateTime.parse(m.group(2), PDF_TS);
                if ("CreationDate".equals(m.group(1)) && created == null) {
                    created = ts;
                } else if ("ModDate".equals(m.group(1))) {
                    modified = modified == null || ts.isAfter(modified) ? ts : modified;
                }
            } catch (DateTimeParseException e) {
                // malformed metadata date – ignored
            }
        }
        if (created != null && modified != null && modified.isAfter(created.plusMinutes(1))) {
            return true;
        }
        int eof = 0;
        int i = s.indexOf("%%EOF");
        while (i >= 0) {
            eof++;
            i = s.indexOf("%%EOF", i + 5);
        }
        return eof > 1;
    }
}
