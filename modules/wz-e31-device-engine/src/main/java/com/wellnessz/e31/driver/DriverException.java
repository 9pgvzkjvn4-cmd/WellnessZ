package com.wellnessz.e31.driver;

import java.util.List;

/** Schema violations of a driver package (all findings, PHI-free). */
public class DriverException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> errors;

    public DriverException(List<String> errors) {
        super("Driver package invalid: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
