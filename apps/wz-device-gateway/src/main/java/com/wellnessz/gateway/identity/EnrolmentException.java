package com.wellnessz.gateway.identity;

/** Enrolment refused or failed; {@code code} is the contract failure code (§15.2) or a local code. */
public class EnrolmentException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final int status;
    private final boolean retryable;

    public EnrolmentException(String code, int status, boolean retryable, Throwable cause) {
        super("enrolment failed: " + code + " (HTTP " + status + ")", cause);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
