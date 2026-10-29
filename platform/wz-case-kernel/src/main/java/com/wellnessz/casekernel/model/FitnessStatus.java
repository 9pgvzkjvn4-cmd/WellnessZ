package com.wellnessz.casekernel.model;

/**
 * Operational fitness outcome – the only health-derived information shared with rostering / HR.
 * {@code UNFIT_PENDING} is set by a crew decrease-in-fitness declaration until an AME decides (E01-BL-016).
 */
public enum FitnessStatus { FIT, FIT_WITH_RESTRICTIONS, TEMPORARILY_UNFIT, PERMANENTLY_UNFIT, UNDER_REVIEW, NOT_ASSESSED, UNFIT_PENDING }
