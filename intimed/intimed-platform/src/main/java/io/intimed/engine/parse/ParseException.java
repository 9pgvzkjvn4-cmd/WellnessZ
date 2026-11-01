package io.intimed.engine.parse;

/** Malformed / unparseable device payload → NAK (AE/AR) and dead-letter queue (SC-12). Message never contains PHI. */
public class ParseException extends RuntimeException {
    public ParseException(String message) { super(message); }
}
