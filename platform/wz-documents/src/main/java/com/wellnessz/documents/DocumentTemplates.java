package com.wellnessz.documents;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.wellnessz.common.model.Roles;

/**
 * Document template catalogue (E03-BL-015, E05-BL-003). Templates flagged {@code containsClinical=false} may be shared
 * with managers and HR; their merge fields are screened by the clinical-text guard so a diagnosis cannot slip in.
 * Wording is a draft for DPO / CMO approval.
 */
@Component
public class DocumentTemplates {

    public record MergeField(String key, String label, boolean required, int maxLength) {
    }

    public record Template(String code, String type, String title, String audience, boolean containsClinical, String recordClass,
                           Set<String> generatorRoles, Set<String> signerRoles, List<MergeField> mergeFields, List<String> body) {
    }

    private static final Set<String> OH = Set.of(Roles.OH_CLINICIAN, Roles.CASE_MANAGER, Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO,
            Roles.NURSING_HEAD, Roles.PSYCH_CLINICIAN);
    private static final Set<String> CLINICIANS = Set.of(Roles.OH_CLINICIAN, Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO,
            Roles.NURSING_HEAD, Roles.PSYCH_CLINICIAN);
    private static final Set<String> AME_CMO = Set.of(Roles.AME, Roles.CMO);

    private static MergeField f(String key, String label) {
        return new MergeField(key, label, true, 400);
    }

    private static MergeField o(String key, String label) {
        return new MergeField(key, label, false, 400);
    }

    private static MergeField t(String key, String label, boolean required) {
        return new MergeField(key, label, required, 4000);
    }

    private final Map<String, Template> templates = List.of(
            new Template("FITNESS_CERTIFICATE", "FITNESS_CERTIFICATE", "Fitness for work certificate", "EMPLOYEE,MANAGER,HR", false,
                    "FITNESS_CERTIFICATE", OH, CLINICIANS,
                    List.of(f("employeeName", "Employee"), f("employeeId", "Employee number"), o("jobTitle", "Role"),
                            f("standardTitle", "Assessed against"), f("outcome", "Fitness outcome"), t("restrictions", "Work restrictions / limitations", false),
                            f("validFrom", "Valid from"), o("validUntil", "Valid until"), o("reviewDate", "Review date"), f("issuedBy", "Issued by")),
                    List.of("Occupational Health has assessed {{employeeName}} ({{employeeId}}), {{jobTitle}}, against: {{standardTitle}}.",
                            "Outcome: {{outcome}}.",
                            "Work restrictions / limitations: {{restrictions}}",
                            "Valid from {{validFrom}} until {{validUntil}}. Review date: {{reviewDate}}.",
                            "This certificate states the work-related outcome only. It contains no medical diagnosis or clinical information. "
                                    + "Questions about adjustments should be addressed to Occupational Health.")),
            new Template("RESTRICTION_NOTE", "RESTRICTION_NOTE", "Work restriction note", "EMPLOYEE,MANAGER,HR", false,
                    "FITNESS_CERTIFICATE", OH, CLINICIANS,
                    List.of(f("employeeName", "Employee"), f("employeeId", "Employee number"), o("jobTitle", "Role"),
                            t("restrictions", "Restrictions", true), f("validFrom", "From"), f("validUntil", "Until"), o("reviewDate", "Review date"),
                            f("contact", "OH contact")),
                    List.of("{{employeeName}} ({{employeeId}}), {{jobTitle}}, should work with the following restrictions:",
                            "{{restrictions}}",
                            "From {{validFrom}} until {{validUntil}} (review {{reviewDate}}).",
                            "No clinical information is included. Contact: {{contact}}.")),
            new Template("FOOD_HANDLER_CLEARANCE", "FOOD_HANDLER_CLEARANCE", "Food handler fitness clearance", "EMPLOYEE,MANAGER,HR", false,
                    "FITNESS_CERTIFICATE", OH, CLINICIANS,
                    List.of(f("employeeName", "Employee"), f("employeeId", "Employee number"), o("department", "Department"),
                            f("status", "Food handling status"), f("effectiveFrom", "Effective from"), o("expectedReturn", "Expected return"),
                            f("issuedBy", "Issued by")),
                    List.of("Food handler status for {{employeeName}} ({{employeeId}}), {{department}}: {{status}}.",
                            "Effective from {{effectiveFrom}}. Expected return: {{expectedReturn}}.",
                            "Issued under the food-safety (HACCP) fitness procedure. No clinical information is included.")),
            new Template("RTW_PLAN", "RTW_PLAN", "Return-to-work plan", "EMPLOYEE,MANAGER,HR", false, "CORRESPONDENCE", OH, CLINICIANS,
                    List.of(f("employeeName", "Employee"), f("caseRef", "Case"), f("startDate", "Start date"), t("goal", "Goal", true),
                            t("phases", "Phases", true), o("reviewDates", "Review dates"), f("contact", "OH contact")),
                    List.of("Return-to-work plan for {{employeeName}} (case {{caseRef}}), starting {{startDate}}.",
                            "Goal: {{goal}}",
                            "Phases: {{phases}}",
                            "Reviews: {{reviewDates}}. Contact: {{contact}}.",
                            "This plan describes work arrangements only and contains no clinical information.")),
            new Template("JOB_OFFER", "JOB_OFFER", "Alternative duty offer", "EMPLOYEE,HR", false, "CORRESPONDENCE",
                    Set.of(Roles.HR, Roles.OH_CLINICIAN, Roles.CASE_MANAGER, Roles.NURSING_HEAD), Set.of(Roles.HR, Roles.NURSING_HEAD),
                    List.of(f("employeeName", "Employee"), f("jobTitle", "Alternative role"), t("duties", "Duties", true), f("location", "Location"),
                            f("shift", "Shift"), f("hoursPerWeek", "Hours per week"), f("payBasis", "Pay"), o("payNote", "Pay note"),
                            t("restrictions", "Restrictions respected", false), f("startDate", "Start date"), o("endDate", "End date"),
                            f("contactName", "Contact"), o("contactEmail", "Contact e-mail"), f("respondBy", "Please respond by")),
                    List.of("Dear {{employeeName}}, we are pleased to offer you alternative duties as {{jobTitle}} at {{location}}.",
                            "Duties: {{duties}}",
                            "Shift: {{shift}}; {{hoursPerWeek}} hours per week from {{startDate}} until {{endDate}}.",
                            "Pay: {{payBasis}}. {{payNote}}",
                            "These duties respect the following restrictions: {{restrictions}}",
                            "Please accept or decline in the employee portal by {{respondBy}}. Contact: {{contactName}} {{contactEmail}}.")),
            new Template("IHR_DECISION_LETTER", "IHR_DECISION_LETTER", "Ill-health retirement / redeployment decision", "EMPLOYEE,HR", false,
                    "CORRESPONDENCE", Set.of(Roles.CMO), Set.of(Roles.CMO),
                    List.of(f("employeeName", "Employee"), f("decisionType", "Assessment"), t("decision", "Decision", true),
                            f("effectiveDate", "Effective date"), f("appealDeadline", "Appeal deadline"), f("cmoName", "Chief Medical Officer")),
                    List.of("Dear {{employeeName}}, this letter confirms the outcome of your {{decisionType}} assessment.",
                            "{{decision}}",
                            "Effective date: {{effectiveDate}}. You may appeal this decision until {{appealDeadline}} through HR or Occupational Health.",
                            "{{cmoName}}, Chief Medical Officer")),
            new Template("REFERRAL_LETTER", "REFERRAL_LETTER", "Referral letter", "PARTNER", true, "CORRESPONDENCE", OH, CLINICIANS,
                    List.of(f("employeeName", "Patient"), o("dateOfBirth", "Date of birth"), f("recipient", "To"), t("reason", "Reason for referral", true),
                            t("history", "Relevant history", false), t("question", "Question for the specialist", true), f("referrer", "Referring clinician")),
                    List.of("To: {{recipient}}", "Re: {{employeeName}} (date of birth {{dateOfBirth}})", "Reason for referral: {{reason}}",
                            "Relevant history: {{history}}", "Question: {{question}}", "Referring clinician: {{referrer}}",
                            "Confidential – shared with the patient's recorded consent.")),
            new Template("CABIN_CREW_MEDICAL_REPORT", "CABIN_CREW_MEDICAL_REPORT", "Cabin crew medical report", "EMPLOYEE", true,
                    "AEROMEDICAL_RECORD", AME_CMO, AME_CMO,
                    List.of(f("employeeName", "Cabin crew member"), f("employeeId", "Staff number"), o("dateOfBirth", "Date of birth"),
                            f("examinationDate", "Date of assessment"), f("assessment", "Assessment"), o("limitations", "Limitations"),
                            f("validUntil", "Valid until"), f("ameName", "Aeromedical examiner"), o("ameNumber", "AME certificate number")),
                    List.of("Cabin crew medical report (layout per local CAA requirements – draft for CAA format confirmation).",
                            "Name: {{employeeName}}  Staff number: {{employeeId}}  Date of birth: {{dateOfBirth}}",
                            "Date of assessment: {{examinationDate}}", "Assessment: {{assessment}}", "Limitations: {{limitations}}",
                            "Valid until: {{validUntil}}", "Examiner: {{ameName}} ({{ameNumber}})")),
            new Template("CREW_MEDICAL_REPORT", "CREW_MEDICAL_REPORT", "Pilot medical assessment report", "EMPLOYEE", true,
                    "AEROMEDICAL_RECORD", AME_CMO, AME_CMO,
                    List.of(f("employeeName", "Licence holder"), f("employeeId", "Staff number"), o("dateOfBirth", "Date of birth"),
                            f("medicalClass", "Medical class"), f("examinationDate", "Date of examination"), f("assessment", "Assessment"),
                            o("limitations", "Limitation codes"), f("validUntil", "Valid until"), f("ameName", "Aeromedical examiner"),
                            o("ameNumber", "AME certificate number")),
                    List.of("Medical assessment report – {{medicalClass}} (layout per local CAA requirements – draft for CAA format confirmation).",
                            "Name: {{employeeName}}  Staff number: {{employeeId}}  Date of birth: {{dateOfBirth}}",
                            "Date of examination: {{examinationDate}}", "Assessment: {{assessment}}", "Limitations: {{limitations}}",
                            "Valid until: {{validUntil}}", "Examiner: {{ameName}} ({{ameNumber}})")),
            // increment 3 – E06 OH advice report: fitness, adjustments and prognosis only (no diagnosis), released after the employee step
            new Template("OH_ADVICE_REPORT", "OH_ADVICE_REPORT", "Occupational Health advice report", "EMPLOYEE,MANAGER,HR", false,
                    "ABSENCE_RECORD", OH, CLINICIANS,
                    List.of(f("employeeName", "Employee"), f("employeeId", "Employee number"), f("requestRef", "Advice request"),
                            o("requestedBy", "Requested by"), f("fitness", "Fitness for work"), t("adjustments", "Recommended adjustments", false),
                            t("restrictions", "Work restrictions", false), f("prognosis", "Prognosis"), o("expectedReturn", "Expected return to work"),
                            o("reviewDate", "Next OH review"), t("answers", "Answers to the questions asked", false),
                            t("employeeComment", "Employee comment", false), f("issuedBy", "Issued by")),
                    List.of("Occupational Health advice for {{employeeName}} ({{employeeId}}) in response to advice request {{requestRef}} from {{requestedBy}}.",
                            "Fitness for work: {{fitness}}.", "Recommended adjustments: {{adjustments}}", "Work restrictions: {{restrictions}}",
                            "Prognosis: {{prognosis}}. Expected return to work: {{expectedReturn}}. Next OH review: {{reviewDate}}.",
                            "Answers to the questions asked: {{answers}}", "Employee comment: {{employeeComment}}",
                            "Issued by {{issuedBy}}, Occupational Health. This report states functional advice only. It contains no medical "
                                    + "diagnosis or clinical information and was released with the employee's knowledge.")),
            // increment 3 – E07 vaccination certificate (employee only)
            new Template("VACCINATION_CERTIFICATE", "VACCINATION_CERTIFICATE", "Vaccination certificate", "EMPLOYEE", true,
                    "OH_RECORD", OH, CLINICIANS,
                    List.of(f("employeeName", "Employee"), f("employeeId", "Employee number"), t("vaccinations", "Vaccinations", true),
                            f("issuedOn", "Issued on"), f("issuedBy", "Issued by")),
                    List.of("Certificate of vaccination for {{employeeName}} ({{employeeId}}), issued on {{issuedOn}}.",
                            "Vaccinations recorded by Occupational Health: {{vaccinations}}",
                            "Issued by {{issuedBy}}, Occupational Health. Present this certificate where proof of vaccination is required.")),
            // increment 3 – E07 pre-employment outcome for HR onboarding (status only)
            new Template("PRE_EMPLOYMENT_OUTCOME", "PRE_EMPLOYMENT_OUTCOME", "Pre-employment health clearance", "HR", false,
                    "PRE_EMPLOYMENT", OH, CLINICIANS,
                    List.of(f("candidateName", "Candidate"), f("offerRef", "Offer reference"), f("jobTitle", "Role"), f("outcome", "Clearance status"),
                            o("conditions", "Workplace adjustments for onboarding"), f("issuedBy", "Issued by")),
                    List.of("Pre-employment health clearance for {{candidateName}}, offer {{offerRef}}, role {{jobTitle}}.",
                            "Clearance status: {{outcome}}.", "Workplace adjustments for onboarding: {{conditions}}",
                            "Issued by {{issuedBy}}, Occupational Health. Status only – the health questionnaire and examination stay with OH.")),
            new Template("RESULT_LETTER", "RESULT_LETTER", "Result letter", "EMPLOYEE", true, "OH_RECORD", OH, CLINICIANS,
                    List.of(f("employeeName", "Employee"), f("testName", "Test"), f("performedOn", "Date"), t("resultSummary", "Result", true),
                            t("interpretation", "What this means", true), t("action", "Next steps", false), f("clinician", "Clinician")),
                    List.of("Dear {{employeeName}}, your {{testName}} on {{performedOn}} has been reviewed by Occupational Health.",
                            "Result: {{resultSummary}}", "What this means: {{interpretation}}", "Next steps: {{action}}", "{{clinician}}, Occupational Health")))
            .stream().collect(Collectors.toUnmodifiableMap(Template::code, Function.identity()));

    public Optional<Template> find(String code) {
        return Optional.ofNullable(templates.get(code));
    }

    public List<Template> all() {
        return templates.values().stream().sorted(java.util.Comparator.comparing(Template::code)).toList();
    }

    /** Record class used by the retention schedule for a document type. */
    public static String recordClassFor(String type) {
        return switch (type) {
            case "FITNESS_CERTIFICATE", "RESTRICTION_NOTE", "FOOD_HANDLER_CLEARANCE" -> "FITNESS_CERTIFICATE";
            case "CABIN_CREW_MEDICAL_REPORT", "CREW_MEDICAL_REPORT" -> "AEROMEDICAL_RECORD";
            case "RESULT_LETTER", "VACCINATION_CERTIFICATE", "VACCINATION_RECORD" -> "OH_RECORD";
            case "SICK_CERTIFICATE", "OH_ADVICE_REPORT" -> "ABSENCE_RECORD";
            case "PRE_EMPLOYMENT_DOCUMENT", "PRE_EMPLOYMENT_OUTCOME" -> "PRE_EMPLOYMENT";
            case "EXTERNAL_AEROMEDICAL_REPORT" -> "AEROMEDICAL_RECORD";
            case "PHOTO" -> "PHOTO";
            case "EXTERNAL_REPORT" -> "EXTERNAL_REPORT";
            default -> "CORRESPONDENCE";
        };
    }
}
