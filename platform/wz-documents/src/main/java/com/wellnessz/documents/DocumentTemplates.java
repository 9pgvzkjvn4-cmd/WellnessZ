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
            // increment 4 – E04 treatment plan for the employee (printable)
            new Template("TREATMENT_PLAN", "TREATMENT_PLAN", "Treatment plan", "EMPLOYEE", true, "OH_RECORD", OH, CLINICIANS,
                    List.of(f("employeeName", "Employee"), f("caseRef", "Case"), t("goals", "Goals", true), t("interventions", "Interventions", true),
                            t("followUps", "Follow-up appointments", false), o("expectedRecovery", "Expected recovery"), f("clinician", "Responsible clinician")),
                    List.of("Treatment plan for {{employeeName}} – case {{caseRef}}.", "Goals: {{goals}}", "Interventions: {{interventions}}",
                            "Follow-up appointments: {{followUps}}", "Expected recovery: {{expectedRecovery}}",
                            "Responsible clinician: {{clinician}}, Occupational Health. Please contact OH if you cannot attend an appointment.")),
            // increment 4 – E04 pre-authorisation / guarantee of payment request to an insurer or TPA (shared with consent)
            new Template("PREAUTH_REQUEST", "PREAUTH_REQUEST", "Pre-authorisation request", "INSURER", true, "CLAIM_RECORD", OH, CLINICIANS,
                    List.of(f("employeeName", "Patient"), f("employeeId", "Employee number"), f("payer", "Insurer / TPA"), f("requestRef", "Request reference"),
                            f("service", "Requested service"), f("provider", "Provider"), f("estimate", "Cost estimate"),
                            t("justification", "Clinical justification", true), f("requestedBy", "Requested by")),
                    List.of("To: {{payer}} – pre-authorisation / guarantee of payment {{requestRef}}.",
                            "Patient: {{employeeName}} ({{employeeId}})", "Requested service: {{service}} at {{provider}}; estimate {{estimate}}.",
                            "Clinical justification: {{justification}}", "Requested by {{requestedBy}}, Occupational Health. "
                                    + "Shared with the employee's recorded consent to insurer sharing.")),
            // increment 4 – E08 statutory / insurer first report of injury (facts of the event, minimum clinical data)
            new Template("FIRST_REPORT_OF_INJURY", "FIRST_REPORT_OF_INJURY", "First report of injury", "INSURER", true, "CLAIM_RECORD",
                    Set.of(Roles.CLAIMS_COORDINATOR, Roles.OH_CLINICIAN, Roles.OH_PHYSICIAN, Roles.CMO, Roles.CASE_MANAGER),
                    Set.of(Roles.CLAIMS_COORDINATOR, Roles.OH_CLINICIAN, Roles.OH_PHYSICIAN, Roles.CMO, Roles.CASE_MANAGER),
                    List.of(f("employer", "Employer"), f("employeeName", "Injured employee"), f("employeeId", "Employee number"), f("jobTitle", "Occupation"),
                            f("occurredAt", "Date and time of injury"), f("location", "Place of injury"), t("description", "How the injury happened", true),
                            f("bodyPart", "Part of body"), f("injuryNature", "Nature of injury"), o("lostTime", "Time lost from work"),
                            f("reportRef", "Report reference"), f("reportedBy", "Reported by")),
                    List.of("First report of injury {{reportRef}} – {{employer}}.", "Injured employee: {{employeeName}} ({{employeeId}}), {{jobTitle}}.",
                            "Date and time: {{occurredAt}}. Place: {{location}}.", "How it happened: {{description}}",
                            "Part of body: {{bodyPart}}. Nature of injury: {{injuryNature}}. Time lost: {{lostTime}}.",
                            "Reported by {{reportedBy}}. Statutory notification – contains the minimum injury information required by law.")),
            // increment 4 – E08 medico-legal report (legal access set, privilege marking)
            new Template("MEDICO_LEGAL_REPORT", "MEDICO_LEGAL_REPORT", "Medico-legal report", "LEGAL", true, "CLAIM_RECORD", OH, CLINICIANS,
                    List.of(f("employeeName", "Employee"), f("caseRef", "Case"), f("requestRef", "Legal request"), t("questions", "Questions from counsel", true),
                            t("opinion", "Medical opinion", true), o("privilege", "Privilege"), f("author", "Author")),
                    List.of("Medico-legal report for {{employeeName}} – case {{caseRef}}, request {{requestRef}}.", "Questions: {{questions}}",
                            "Opinion: {{opinion}}", "Privilege: {{privilege}}", "Author: {{author}}. Prepared at the request of legal counsel – "
                                    + "confidential, not for disclosure outside the legal access set.")),
            // increment 5 – E09 aeromedical / authority report on a safety-critical mental-health fitness decision
            new Template("AUTHORITY_FITNESS_REPORT", "AUTHORITY_FITNESS_REPORT", "Fitness report to the authority", "AUTHORITY", true,
                    "RESTRICTED_RECORD", Set.of(Roles.AME, Roles.OH_PHYSICIAN, Roles.CMO, Roles.PSYCH_CLINICIAN), Set.of(Roles.AME, Roles.OH_PHYSICIAN, Roles.CMO),
                    List.of(f("employeeName", "Licence holder"), f("employeeId", "Staff number"), o("licence", "Licence / certificate"),
                            f("assessmentDate", "Date of assessment"), f("decision", "Decision"), o("limitations", "Limitations"),
                            o("reviewDate", "Next review"), f("examiner", "Examiner")),
                    List.of("Report to the competent authority – fitness assessment of {{employeeName}} ({{employeeId}}), {{licence}}.",
                            "Date of assessment: {{assessmentDate}}. Decision: {{decision}}. Limitations: {{limitations}}. Next review: {{reviewDate}}.",
                            "Examiner: {{examiner}}. Restricted – submitted only where the regulation requires it.")),
            // increment 5 – E10 electronic chain-of-custody form (no result content)
            new Template("CHAIN_OF_CUSTODY_FORM", "CHAIN_OF_CUSTODY_FORM", "Chain-of-custody form", "LABORATORY", true, "RESTRICTED_RECORD",
                    Set.of(Roles.COLLECTOR, Roles.MRO, Roles.OH_PHYSICIAN), Set.of(Roles.COLLECTOR, Roles.MRO, Roles.OH_PHYSICIAN),
                    List.of(f("specimenId", "Specimen ID"), f("donorId", "Donor ID"), f("testReason", "Reason for test"), f("specimenType", "Specimen"),
                            f("collectedAt", "Collected at"), f("collectionSite", "Collection site"), f("collector", "Collector"),
                            f("sealIntact", "Seal intact"), f("laboratory", "Laboratory"), o("remarks", "Remarks")),
                    List.of("Chain of custody – specimen {{specimenId}} ({{specimenType}}), donor {{donorId}}, reason {{testReason}}.",
                            "Collected {{collectedAt}} at {{collectionSite}} by {{collector}}. Seal intact: {{sealIntact}}.",
                            "Laboratory: {{laboratory}}. Remarks: {{remarks}}", "Each hand-over is recorded electronically with time and signer.")),
            // increment 5 – E11 travel health certificate (employee)
            new Template("TRAVEL_HEALTH_CERTIFICATE", "TRAVEL_HEALTH_CERTIFICATE", "Travel health certificate", "EMPLOYEE", true, "OH_RECORD", OH, CLINICIANS,
                    List.of(f("employeeName", "Traveller"), f("employeeId", "Staff number"), f("destinations", "Destinations"),
                            t("vaccinations", "Vaccinations and prophylaxis", true), o("validUntil", "Valid until"), f("issuedBy", "Issued by")),
                    List.of("Travel health certificate for {{employeeName}} ({{employeeId}}) – destinations: {{destinations}}.",
                            "Vaccinations and prophylaxis recorded by Occupational Health: {{vaccinations}}", "Valid until: {{validUntil}}.",
                            "Issued by {{issuedBy}}, Occupational Health.")),
            // increment 5 – E11 notifiable-disease / exposure report to the public-health authority
            new Template("PUBLIC_HEALTH_REPORT", "PUBLIC_HEALTH_REPORT", "Public-health notification", "AUTHORITY", true, "PUBLIC_HEALTH", OH, CLINICIANS,
                    List.of(f("reportRef", "Report reference"), f("authority", "Authority"), f("condition", "Notifiable condition / exposure"),
                            f("onsetDate", "Onset / exposure date"), f("employeeName", "Person"), f("employeeId", "Staff number"),
                            o("contacts", "Contacts identified"), t("measures", "Measures taken", true), f("reportedBy", "Reported by")),
                    List.of("Public-health notification {{reportRef}} to {{authority}}.", "Condition / exposure: {{condition}} – onset {{onsetDate}}.",
                            "Person: {{employeeName}} ({{employeeId}}). Contacts identified: {{contacts}}.", "Measures taken: {{measures}}",
                            "Reported by {{reportedBy}}, Occupational Health – statutory notification.")),
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
            case "PREAUTH_REQUEST", "FIRST_REPORT_OF_INJURY", "MEDICO_LEGAL_REPORT", "PROVIDER_REPORT", "PROVIDER_INVOICE" -> "CLAIM_RECORD";
            case "TREATMENT_PLAN", "TRAVEL_HEALTH_CERTIFICATE" -> "OH_RECORD";
            case "AUTHORITY_FITNESS_REPORT", "CHAIN_OF_CUSTODY_FORM" -> "RESTRICTED_RECORD";
            case "PUBLIC_HEALTH_REPORT" -> "PUBLIC_HEALTH";
            case "EXTERNAL_AEROMEDICAL_REPORT" -> "AEROMEDICAL_RECORD";
            case "PHOTO" -> "PHOTO";
            case "EXTERNAL_REPORT" -> "EXTERNAL_REPORT";
            default -> "CORRESPONDENCE";
        };
    }
}
