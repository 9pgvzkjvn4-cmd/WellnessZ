package com.wellnessz.e03.support;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.wellnessz.common.error.ApiException;

/** Problem response with extra, PHI-free properties (e.g. the names of missing mandatory template fields). */
public class RecordProblem extends ApiException {

    private static final long serialVersionUID = 1L;
    private final transient Map<String, Object> properties;

    public RecordProblem(HttpStatus status, String code, String message, Map<String, Object> properties) {
        super(status, code, message);
        this.properties = Map.copyOf(properties);
    }

    public static RecordProblem unprocessable(String code, String message) {
        return new RecordProblem(HttpStatus.UNPROCESSABLE_CONTENT, code, message, Map.of());
    }

    public static RecordProblem missingFields(List<String> fields) {
        return new RecordProblem(HttpStatus.UNPROCESSABLE_CONTENT, "mandatory_fields_missing",
                "Complete the mandatory template fields before signing", Map.of("fields", List.copyOf(fields)));
    }

    @Override
    public Map<String, Object> properties() {
        return properties;
    }
}
