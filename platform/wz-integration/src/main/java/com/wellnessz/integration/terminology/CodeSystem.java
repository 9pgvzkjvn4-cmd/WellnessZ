package com.wellnessz.integration.terminology;

/** Clinical and occupational code systems supported by the platform (canonical FHIR system URIs). */
public enum CodeSystem {
    SNOMED_CT("http://snomed.info/sct"),
    ICD_10("http://hl7.org/fhir/sid/icd-10"),
    ICD_11("http://id.who.int/icd/release/11/mms"),
    LOINC("http://loinc.org"),
    /** Occupational Data for Health (HL7 ODH IG) value sets, e.g. occupation / industry. */
    ODH("http://terminology.hl7.org/CodeSystem/PHOccupationalDataForHealthODH");

    private final String uri;

    CodeSystem(String uri) {
        this.uri = uri;
    }

    public String uri() {
        return uri;
    }
}
