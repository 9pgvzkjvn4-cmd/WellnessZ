package com.wellnessz.ehsintake.excel;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wellnessz.ehsintake.model.IncidentRecord;

/**
 * Parses EHS incident exports (.xlsx). Hardened against malicious workbooks:
 * OOXML magic-byte check, zip-bomb limits (inflate ratio, entry size), row cap, formulas are never evaluated
 * (cached values only), POI's XML parsers have external entities disabled. Column headers are matched by
 * configurable aliases so different EHS export layouts can be onboarded without code changes.
 */
@Component
public class ExcelIncidentParser {

    private static final Map<String, Set<String>> ALIASES = new HashMap<>();
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME, DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy H:mm"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

    static {
        ALIASES.put("externalRef", Set.of("incident id", "incident ref", "incident reference", "reference", "incident number", "id"));
        ALIASES.put("occurredAt", Set.of("date", "incident date", "occurred at", "date/time", "incident date/time", "event date"));
        ALIASES.put("location", Set.of("location", "site", "station", "airport"));
        ALIASES.put("employeeId", Set.of("employee id", "staff id", "employee number", "staff number", "person id"));
        ALIASES.put("employeeName", Set.of("employee name", "name", "injured person", "person name"));
        ALIASES.put("orgUnitCode", Set.of("department", "department code", "org unit", "org unit code", "cost centre"));
        ALIASES.put("incidentType", Set.of("type", "incident type", "category", "event type"));
        ALIASES.put("severity", Set.of("severity", "risk rating", "classification"));
        ALIASES.put("injury", Set.of("injury", "injured", "injury sustained", "personal injury"));
        ALIASES.put("description", Set.of("description", "details", "narrative", "summary"));
        ALIASES.put("incidentClass", Set.of("incident class", "classification code", "outcome class"));
        ALIASES.put("bodyPart", Set.of("body part", "body part affected", "injured body part"));
        ALIASES.put("lostTime", Set.of("lost time", "lost time injury", "lti", "days lost"));
        ALIASES.put("exposureType", Set.of("exposure", "exposure type", "agent"));
        ALIASES.put("unitCode", Set.of("subsidiary", "unit", "business unit", "company"));
        ALIASES.put("shift", Set.of("shift"));
        ALIASES.put("reporterEmail", Set.of("reporter email", "reported by email", "hse officer email"));
    }

    private final int maxRows;
    private final ZoneId zone;
    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    public ExcelIncidentParser(@Value("${wellnessz.ehs.excel.max-rows:10000}") int maxRows,
                               @Value("${wellnessz.ehs.timezone:UTC}") String zone) {
        this.maxRows = maxRows;
        this.zone = ZoneId.of(zone);
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(100L * 1024 * 1024);
    }

    public record RowError(int row, String message) {
    }

    public record ParseResult(List<IncidentRecord> records, List<RowError> errors, int totalRows) {
    }

    public ParseResult parse(InputStream input, String sourceSystem) throws IOException {
        BufferedInputStream in = new BufferedInputStream(input);
        in.mark(4);
        byte[] magic = in.readNBytes(4);
        in.reset();
        if (magic.length < 4 || magic[0] != 'P' || magic[1] != 'K' || magic[2] != 3 || magic[3] != 4) {
            throw new IllegalArgumentException("Not an .xlsx (OOXML) workbook");
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                throw new IllegalArgumentException("Workbook has no header row");
            }
            Map<String, Integer> columns = mapHeader(header);
            if (!columns.containsKey("externalRef")) {
                throw new IllegalArgumentException("Required column 'Incident ID' not found");
            }
            List<IncidentRecord> records = new ArrayList<>();
            List<RowError> errors = new ArrayList<>();
            int total = 0;
            for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlank(row)) {
                    continue;
                }
                if (++total > maxRows) {
                    errors.add(new RowError(r + 1, "Row limit of " + maxRows + " exceeded; remaining rows ignored"));
                    break;
                }
                try {
                    records.add(toRecord(row, columns, sourceSystem));
                } catch (IllegalArgumentException e) {
                    errors.add(new RowError(r + 1, e.getMessage()));
                }
            }
            return new ParseResult(records, errors, total);
        }
    }

    private Map<String, Integer> mapHeader(Row header) {
        Map<String, Integer> columns = new HashMap<>();
        for (Cell cell : header) {
            String name = formatter.formatCellValue(cell).trim().toLowerCase(Locale.ROOT);
            ALIASES.forEach((field, aliases) -> {
                if (aliases.contains(name)) {
                    columns.putIfAbsent(field, cell.getColumnIndex());
                }
            });
        }
        return columns;
    }

    private IncidentRecord toRecord(Row row, Map<String, Integer> c, String sourceSystem) {
        String ref = text(row, c, "externalRef", 100);
        if (ref == null || !ref.matches("[A-Za-z0-9._/\\-]{1,100}")) {
            throw new IllegalArgumentException("Missing or invalid incident id");
        }
        String severity = text(row, c, "severity", 20);
        String employeeId = text(row, c, "employeeId", 64);
        if (employeeId != null && !employeeId.matches("[A-Za-z0-9\\-]{1,64}")) {
            throw new IllegalArgumentException("Invalid employee id");
        }
        String lostTime = text(row, c, "lostTime", 10);
        String incidentClass = text(row, c, "incidentClass", 40);
        String bodyPart = text(row, c, "bodyPart", 60);
        String reporter = text(row, c, "reporterEmail", 320);
        return new IncidentRecord(sourceSystem, ref, date(row, c), text(row, c, "location", 200), employeeId,
                text(row, c, "employeeName", 200), text(row, c, "orgUnitCode", 64), text(row, c, "incidentType", 100),
                severity == null ? null : severity.toUpperCase(Locale.ROOT), bool(text(row, c, "injury", 10)),
                text(row, c, "description", 4000), code(incidentClass), code(bodyPart),
                lostTime == null ? null : (bool(lostTime) || lostTime.matches("[1-9][0-9]*")),
                code(text(row, c, "exposureType", 60)), code(text(row, c, "unitCode", 20)), code(text(row, c, "shift", 40)),
                reporter != null && reporter.matches("[^@\\s]{1,64}@[^@\\s]{1,255}") ? reporter : null);
    }

    private String text(Row row, Map<String, Integer> c, String field, int max) {
        Integer idx = c.get(field);
        if (idx == null) {
            return null;
        }
        Cell cell = row.getCell(idx);
        if (cell == null) {
            return null;
        }
        // formulas: use cached result, never evaluate
        String v = formatter.formatCellValue(cell).replaceAll("\\p{Cntrl}", " ").trim();
        if (v.isEmpty()) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }

    private Instant date(Row row, Map<String, Integer> c) {
        Integer idx = c.get("occurredAt");
        if (idx == null || row.getCell(idx) == null) {
            return null;
        }
        Cell cell = row.getCell(idx);
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().atZone(zone).toInstant();
        }
        String s = formatter.formatCellValue(cell).trim();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDateTime.parse(s, f).atZone(zone).toInstant();
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        try {
            return LocalDate.parse(s).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Unrecognised incident date");
        }
    }

    /** Normalises vocabulary values to UPPER_SNAKE_CASE codes. */
    static String code(String v) {
        if (v == null) {
            return null;
        }
        String c = v.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        return c.isEmpty() ? null : c.substring(0, Math.min(c.length(), 60));
    }

    private static boolean bool(String v) {
        return v != null && Set.of("y", "yes", "true", "1").contains(v.toLowerCase(Locale.ROOT));
    }

    private boolean isBlank(Row row) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }
}
