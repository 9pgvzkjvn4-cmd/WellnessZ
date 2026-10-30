package com.wellnessz.e10.support;

import java.util.Map;

import org.springframework.http.HttpStatus;

import com.wellnessz.common.error.ApiException;

/** 422 business-rule violation that names the offending request field (problem detail property {@code field}). */
public class FieldProblem extends ApiException {

    private static final long serialVersionUID = 1L;
    private final String field;

    public FieldProblem(String code, String message, String field) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
        this.field = field;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("field", field);
    }
}
