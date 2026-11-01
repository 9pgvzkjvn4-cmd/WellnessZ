package com.wellnessz.e31.support;

/**
 * A pipeline failure with a machine code from the contract (section 21). {@code deterministic} failures (parse, mapping,
 * unit) go to the dead-letter queue at once; others are retried with back-off. Messages never contain PHI.
 */
public class EngineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean deterministic;

    public EngineException(String code, String message, boolean deterministic) {
        super(message);
        this.code = code;
        this.deterministic = deterministic;
    }

    public static EngineException parse(String message) {
        return new EngineException("parse_failed", message, true);
    }

    public static EngineException profile(String message) {
        return new EngineException("profile_violation", message, true);
    }

    public String code() {
        return code;
    }

    public boolean deterministic() {
        return deterministic;
    }
}
