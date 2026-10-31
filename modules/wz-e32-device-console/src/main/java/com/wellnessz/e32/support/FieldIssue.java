package com.wellnessz.e32.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.wellnessz.common.error.ApiException;

/** One validation finding of a wizard step or parameter set ({@code errors[{field, code, message}]}, contract §13.2). */
public record FieldIssue(String field, String code, String message) {

    public Map<String, Object> toMap() {
        return Docs.of("field", field, "code", code, "message", message);
    }

    public static List<Map<String, Object>> toMaps(List<FieldIssue> issues) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FieldIssue i : issues) {
            out.add(i.toMap());
        }
        return out;
    }

    /** 422 {@code step_invalid} (or another code) carrying the findings. */
    public static class Invalid extends ApiException {

        private static final long serialVersionUID = 1L;
        private final transient List<FieldIssue> issues;

        public Invalid(String code, String message, List<FieldIssue> issues) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
            this.issues = List.copyOf(issues);
        }

        public List<FieldIssue> issues() {
            return issues == null ? List.of() : issues;
        }

        @Override
        public Map<String, Object> properties() {
            return Map.of("errors", toMaps(issues()));
        }
    }
}
