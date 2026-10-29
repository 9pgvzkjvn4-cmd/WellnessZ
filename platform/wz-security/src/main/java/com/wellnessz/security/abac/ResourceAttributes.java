package com.wellnessz.security.abac;

import com.wellnessz.common.model.DataClassification;

/**
 * Attributes of the protected resource evaluated by ABAC policies.
 *
 * @param resourceType    e.g. {@code CASE}, {@code ENCOUNTER}
 * @param ownerEmployeeId data subject's employee id
 * @param orgUnitPath     materialised org path of the data subject, e.g. {@code /AIRLINE/OPS/CABIN/LHR}
 * @param classification  data classification of the resource
 */
public record ResourceAttributes(String resourceType, String ownerEmployeeId, String orgUnitPath,
                                 DataClassification classification) {
}
