package com.wellnessz.common.model;

/**
 * How much of a case a principal may see. Ordered from least to most disclosure.
 */
public enum DisclosureLevel {
    /** No access. */
    NONE,
    /** Operational status only: case status, fitness status and dates – no clinical content. */
    STATUS_ONLY,
    /** The data subject viewing their own record. */
    SELF,
    /** Standard clinical access for OH professionals. */
    CLINICAL,
    /** Clinical access including restricted classes (e.g. psychological health). */
    RESTRICTED_CLINICAL;

    public boolean atLeast(DisclosureLevel other) {
        return this.ordinal() >= other.ordinal();
    }

    public static DisclosureLevel max(DisclosureLevel a, DisclosureLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
