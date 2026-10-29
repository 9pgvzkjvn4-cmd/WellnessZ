package com.wellnessz.common.text;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic detector for clinical content in text that must stay non-clinical: manager / HR referral fields
 * (E01-BL-007/008) and notifications. It is a guard-rail, not a classifier – it errs on the side of rejecting.
 */
public final class ClinicalTextDetector {

    /** ICD-10 style codes, e.g. {@code F32.1}, {@code S93.4}. */
    private static final Pattern ICD_CODE = Pattern.compile("\\b[A-TV-Z][0-9]{2}(\\.[0-9A-Z]{1,4})?\\b");
    private static final Pattern CLINICAL_TERMS = Pattern.compile("\\b(diagnos\\w*|prognos\\w*|depress\\w*|anxiety|bipolar|"
            + "schizo\\w*|psychiatr\\w*|psychos\\w*|ptsd|adhd|autis\\w*|dementia|epilep\\w*|seizure\\w*|diabet\\w*|"
            + "cancer|tumou?r|hiv|aids|hepatitis|pregnan\\w*|miscarriage|medication|prescri\\w*|antidepressant\\w*|"
            + "alcoholi\\w*|addict\\w*|overdose|suicid\\w*|self[- ]harm|disorder|syndrome|fracture\\w*|concussion|"
            + "migraine|asthma|arthritis|positive test|tested positive|blood test|mri|x-ray|surgery|operation|"
            // increment 2: common occupational-health diagnoses that must not reach managers / HR in certificates or plans
            + "prolapse\\w*|herni\\w*|sciatica|tendin\\w*|bursitis|carpal tunnel|dermatitis|eczema|psoriasis|infection\\w*|"
            + "infectious|gastroenteritis|norovirus|salmonell\\w*|typhoid|tuberculosis|covid\\w*|influenza|hypertensi\\w*|"
            + "cardiac|heart attack|stroke|angina|arrhythmi\\w*|copd|bronchitis|pneumonia|tinnitus|hearing loss|vertigo|glaucoma|"
            + "cataract|insomnia|sleep apno?ea|obes\\w*|ana?emi\\w*|chemotherapy|radiotherapy|dialysis|physiotherap\\w*|"
            + "psycholog\\w*|counsell?ing|antibiotic\\w*|steroid\\w*|opioid\\w*|wound|lesion\\w*)\\b");

    private ClinicalTextDetector() {
    }

    /** @return the first matching term (for user feedback), or empty when the text looks non-clinical */
    public static Optional<String> findClinicalTerm(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher m = CLINICAL_TERMS.matcher(text.toLowerCase(Locale.ROOT));
        if (m.find()) {
            return Optional.of(m.group(1));
        }
        Matcher icd = ICD_CODE.matcher(text);
        return icd.find() ? Optional.of(icd.group()) : Optional.empty();
    }

    public static boolean looksClinical(String text) {
        return findClinicalTerm(text).isPresent();
    }
}
