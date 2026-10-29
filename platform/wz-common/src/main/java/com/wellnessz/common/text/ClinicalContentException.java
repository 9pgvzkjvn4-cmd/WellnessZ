package com.wellnessz.common.text;

import java.util.Map;

import org.springframework.http.HttpStatus;

import com.wellnessz.common.error.ApiException;

/**
 * 422 {@code clinical_content_not_allowed}: a field meant for managers, HR or other non-clinical readers appears to contain
 * medical information. The offending field name is returned so the UI can highlight it.
 */
public class ClinicalContentException extends ApiException {

    private static final long serialVersionUID = 1L;
    private final String field;

    public ClinicalContentException(String field) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "clinical_content_not_allowed",
                "Field '" + field + "' appears to contain medical information. Use functional, work-related wording only.");
        this.field = field;
    }

    public String field() {
        return field;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("field", field);
    }

    /** Throws when {@code value} looks clinical. */
    public static void screen(String field, String value) {
        if (value != null && ClinicalTextDetector.looksClinical(value)) {
            throw new ClinicalContentException(field);
        }
    }
}
