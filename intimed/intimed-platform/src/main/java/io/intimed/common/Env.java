package io.intimed.common;

/** The four environment settings of IM-E08 – LOCAL (MacBook) is what this code base runs as; DEV/UAT/PROD behaviour is
 *  modelled for guardrails (e.g. recommendations are never auto-applied in UAT/PROD, SC-19). */
public enum Env {
    LOCAL, DEV, UAT, PROD;

    public boolean allowsAcceptAll() { return this == LOCAL || this == DEV; }
    public boolean requiresPublishGate() { return this == UAT || this == PROD; }
    public Env next() { return switch (this) { case LOCAL -> DEV; case DEV -> UAT; case UAT -> PROD; case PROD -> PROD; }; }
}
