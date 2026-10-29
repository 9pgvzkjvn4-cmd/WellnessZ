package com.wellnessz.common.model;

/**
 * Data classification scheme applied to every stored and transmitted data element.
 * Drives encryption, disclosure and retention controls.
 */
public enum DataClassification {
    /** Publicly releasable. */
    PUBLIC,
    /** Internal operational data, e.g. org hierarchy. */
    INTERNAL,
    /** Personal data that is not health data (HR identifiers, contact details). */
    CONFIDENTIAL,
    /** Occupational health clinical data; never leaves the OH domain. */
    CLINICAL,
    /** Special-category clinical data (psychological health, substance testing) – restricted class. */
    RESTRICTED_CLINICAL
}
