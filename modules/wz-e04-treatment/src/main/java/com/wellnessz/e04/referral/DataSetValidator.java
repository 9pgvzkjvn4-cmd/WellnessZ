package com.wellnessz.e04.referral;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.wellnessz.common.error.ApiException;

/**
 * Validation of a referral's structured data set against the service catalogue (E04-BL-005): field types on every save,
 * completeness of the required fields before sending.
 */
public final class DataSetValidator {

    static final int MAX_TEXT = 2000;
    static final int MAX_LIST_ITEMS = 20;

    private DataSetValidator() {
    }

    public record Field(String code, String label, String type, boolean required) {
    }

    /** 400 / 422 naming the offending field so the wizard can highlight it. */
    public static class DataSetException extends ApiException {
        private static final long serialVersionUID = 1L;
        private final String field;

        public DataSetException(HttpStatus status, String code, String message, String field) {
            super(status, code, message);
            this.field = field;
        }

        @Override
        public Map<String, Object> properties() {
            return Map.of("field", field);
        }
    }

    /** Parses the catalogue JSON ({@code [{code, label, type, required}]}) already read as maps. */
    public static List<Field> fields(List<Map<String, Object>> raw) {
        List<Field> out = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            out.add(new Field(String.valueOf(m.get("code")), String.valueOf(m.get("label")), String.valueOf(m.get("type")),
                    !Boolean.FALSE.equals(m.get("required"))));
        }
        return out;
    }

    /**
     * @param complete require every required field (send); otherwise only the types of the supplied values are checked
     * @return the cleaned data set (strings stripped, empty values dropped)
     */
    public static Map<String, Object> validate(List<Field> fields, Map<String, Object> data, boolean complete) {
        Map<String, Object> in = data == null ? Map.of() : data;
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : in.keySet()) {
            if (fields.stream().noneMatch(f -> f.code().equals(key))) {
                throw new DataSetException(HttpStatus.BAD_REQUEST, "unknown_field", "Field '" + key + "' is not part of this service's data set",
                        key.length() > 40 ? key.substring(0, 40) : key);
            }
        }
        for (Field f : fields) {
            Object v = in.get(f.code());
            if (isEmpty(v)) {
                if (complete && f.required()) {
                    throw new DataSetException(HttpStatus.UNPROCESSABLE_CONTENT, "data_set_incomplete",
                            "Required referral information missing: " + f.label(), f.code());
                }
                continue;
            }
            out.put(f.code(), check(f, v));
        }
        return out;
    }

    static boolean isEmpty(Object v) {
        return v == null || v instanceof String s && s.isBlank() || v instanceof List<?> l && l.isEmpty();
    }

    private static Object check(Field f, Object v) {
        switch (f.type()) {
            case "DATE" -> {
                if (v instanceof String s) {
                    try {
                        return LocalDate.parse(s.strip()).toString();
                    } catch (DateTimeParseException e) {
                        // fall through to the error below
                    }
                }
                throw invalid(f, "a date (YYYY-MM-DD)");
            }
            case "CODE" -> {
                if (v instanceof String s && s.strip().matches("[A-Za-z0-9._\\-]{1,40}")) {
                    return s.strip();
                }
                throw invalid(f, "a code");
            }
            case "LIST" -> {
                if (v instanceof String s && s.length() <= 500) {
                    return s.strip();
                }
                if (v instanceof List<?> l && l.size() <= MAX_LIST_ITEMS) {
                    List<String> items = new ArrayList<>();
                    for (Object o : l) {
                        if (!(o instanceof String s) || s.length() > 200) {
                            throw invalid(f, "a list of short values");
                        }
                        items.add(s.strip());
                    }
                    return items;
                }
                throw invalid(f, "a list of short values");
            }
            default -> {
                if (v instanceof String s && s.length() <= MAX_TEXT) {
                    return s.strip();
                }
                if (v instanceof Number || v instanceof Boolean) {
                    return String.valueOf(v);
                }
                throw invalid(f, "text of at most " + MAX_TEXT + " characters");
            }
        }
    }

    private static DataSetException invalid(Field f, String expected) {
        return new DataSetException(HttpStatus.BAD_REQUEST, "invalid_field", f.label() + " must be " + expected, f.code());
    }
}
