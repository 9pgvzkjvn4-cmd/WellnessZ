package com.wellnessz.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Guards the modular-monolith boundaries so modules stay extractable and the disclosure rules stay central.
 */
@AnalyzeClasses(packages = "com.wellnessz", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule platformDoesNotDependOnBusinessModules = noClasses()
            .that().resideInAnyPackage("com.wellnessz.common..", "com.wellnessz.secrets..", "com.wellnessz.security..",
                    "com.wellnessz.masterdata..", "com.wellnessz.integration..", "com.wellnessz.notification..",
                    "com.wellnessz.analytics..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.casekernel..", "com.wellnessz.ehsintake..",
                    "com.wellnessz.documents..", "com.wellnessz.e01..", "com.wellnessz.e02..", "com.wellnessz.e03..", "com.wellnessz.e05..",
                    "com.wellnessz.e06..", "com.wellnessz.e07..", "com.wellnessz.e14..", "com.wellnessz.e04..", "com.wellnessz.e08..",
                    "com.wellnessz.e09..", "com.wellnessz.e10..", "com.wellnessz.e11..", "com.wellnessz.e12..");

    @ArchTest
    static final ArchRule kernelDoesNotDependOnBusinessModules = noClasses()
            .that().resideInAPackage("com.wellnessz.casekernel..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e01..", "com.wellnessz.e02..", "com.wellnessz.e03..",
                    "com.wellnessz.e05..", "com.wellnessz.e06..", "com.wellnessz.e07..", "com.wellnessz.e14..", "com.wellnessz.e04..",
                    "com.wellnessz.e08..", "com.wellnessz.e09..", "com.wellnessz.e10..", "com.wellnessz.e11..", "com.wellnessz.e12..",
                    "com.wellnessz.documents..")
            .because("modules plug into the kernel through SPIs (CaseModule, CaseTransitionGuard, ChartContributor, events), never the reverse");

    @ArchTest
    static final ArchRule documentServiceIsAPlatformService = noClasses()
            .that().resideInAPackage("com.wellnessz.documents..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e01..", "com.wellnessz.e02..", "com.wellnessz.e03..",
                    "com.wellnessz.e05..", "com.wellnessz.e06..", "com.wellnessz.e07..", "com.wellnessz.e14..", "com.wellnessz.e04..",
                    "com.wellnessz.e08..", "com.wellnessz.e09..", "com.wellnessz.e10..", "com.wellnessz.e11..", "com.wellnessz.e12..")
            .because("the business modules all use the document service; it must not know about any of them");

    @ArchTest
    static final ArchRule recordAndFitnessModulesAreIndependent = noClasses()
            .that().resideInAPackage("com.wellnessz.e03..")
            .should().dependOnClassesThat().resideInAPackage("com.wellnessz.e05..")
            .because("E05 contributes to the E03 chart through the ChartContributor SPI; the modules stay independently deployable");

    @ArchTest
    static final ArchRule fitnessDoesNotDependOnRecord = noClasses()
            .that().resideInAPackage("com.wellnessz.e05..")
            .should().dependOnClassesThat().resideInAPackage("com.wellnessz.e03..")
            .because("shared needs (diagnosis, documents, standards) go through the kernel, the document service and master data");

    @ArchTest
    static final ArchRule earlierModulesDoNotDependOnLaterOnes = noClasses()
            .that().resideInAnyPackage("com.wellnessz.e01..", "com.wellnessz.e02..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e03..", "com.wellnessz.e05..", "com.wellnessz.e06..",
                    "com.wellnessz.e07..", "com.wellnessz.e14..", "com.wellnessz.e04..", "com.wellnessz.e08..",
                    "com.wellnessz.e09..", "com.wellnessz.e10..", "com.wellnessz.e11..", "com.wellnessz.e12..");

    @ArchTest
    static final ArchRule increment2ModulesDoNotDependOnIncrement3 = noClasses()
            .that().resideInAnyPackage("com.wellnessz.e03..", "com.wellnessz.e05..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e06..", "com.wellnessz.e07..", "com.wellnessz.e14..")
            .because("E03 / E05 call increment-3 behaviour only through kernel SPIs (DeviceCalibrationCheck, ResultFollowUpListener, FitnessClearanceGuard)");

    @ArchTest
    static final ArchRule earlierIncrementsDoNotDependOnIncrement4 = noClasses()
            .that().resideInAnyPackage("com.wellnessz.e03..", "com.wellnessz.e05..", "com.wellnessz.e06..", "com.wellnessz.e07..",
                    "com.wellnessz.e14..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e04..", "com.wellnessz.e08..")
            .because("increment-4 modules plug in through kernel SPIs (ExternalReferralGateway, CaseCostRecorder, ClaimContextProvider)");

    @ArchTest
    static final ArchRule earlierIncrementsDoNotDependOnIncrement5 = noClasses()
            .that().resideInAnyPackage("com.wellnessz.e01..", "com.wellnessz.e02..", "com.wellnessz.e03..", "com.wellnessz.e04..",
                    "com.wellnessz.e05..", "com.wellnessz.e06..", "com.wellnessz.e07..", "com.wellnessz.e08..", "com.wellnessz.e14..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e09..", "com.wellnessz.e10..", "com.wellnessz.e11..",
                    "com.wellnessz.e12..")
            .because("increment-5 modules plug in through kernel SPIs (SupportProgrammeReferral) and in-process events");

    @ArchTest
    static final ArchRule restrictedProgrammesAreIndependent = noClasses()
            .that().resideInAnyPackage("com.wellnessz.e09..", "com.wellnessz.e10..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e03..", "com.wellnessz.e04..", "com.wellnessz.e06..",
                    "com.wellnessz.e07..", "com.wellnessz.e08..", "com.wellnessz.e11..", "com.wellnessz.e12..", "com.wellnessz.e14..")
            .because("restricted-class programmes keep their own records; E10 refers to E09 only through SupportProgrammeReferral");

    @ArchTest
    static final ArchRule restrictedProgrammesDoNotDependOnEachOther = noClasses()
            .that().resideInAPackage("com.wellnessz.e10..")
            .should().dependOnClassesThat().resideInAPackage("com.wellnessz.e09..")
            .because("the testing programme refers into the support programme only through the kernel SPI");

    @ArchTest
    static final ArchRule travelHealthAndPromotionAreIndependent = noClasses()
            .that().resideInAnyPackage("com.wellnessz.e11..", "com.wellnessz.e12..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e04..", "com.wellnessz.e06..", "com.wellnessz.e08..",
                    "com.wellnessz.e09..", "com.wellnessz.e10..", "com.wellnessz.e14..")
            .because("E11 builds on the E07 immunisation registry, E12 on the E03 record; bookings go through AppointmentBooking");

    @ArchTest
    static final ArchRule promotionDoesNotDependOnTravelHealth = noClasses()
            .that().resideInAPackage("com.wellnessz.e12..")
            .should().dependOnClassesThat().resideInAPackage("com.wellnessz.e11..");

    @ArchTest
    static final ArchRule treatmentAndClaimsAreIndependent = noClasses()
            .that().resideInAPackage("com.wellnessz.e04..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e08..", "com.wellnessz.e06..", "com.wellnessz.e07..",
                    "com.wellnessz.e14..")
            .because("E04 posts costs through CaseCostRecorder and reads claim context through ClaimContextProvider");

    @ArchTest
    static final ArchRule claimsDoesNotDependOnTreatmentOrClinicalRecord = noClasses()
            .that().resideInAPackage("com.wellnessz.e08..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e04..", "com.wellnessz.e03..", "com.wellnessz.e06..",
                    "com.wellnessz.e07..", "com.wellnessz.e14..")
            .because("claims never read the clinical record; IMEs go through ExternalReferralGateway, costs arrive through CaseCostRecorder");

    @ArchTest
    static final ArchRule absenceIsIndependentOfSurveillanceAndScheduling = noClasses()
            .that().resideInAPackage("com.wellnessz.e06..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e07..", "com.wellnessz.e14..")
            .because("E06 signals E07 through the AbsenceSignalListener SPI and books through AppointmentBooking");

    @ArchTest
    static final ArchRule surveillanceIsIndependentOfAbsenceAndScheduling = noClasses()
            .that().resideInAPackage("com.wellnessz.e07..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e06..", "com.wellnessz.e14..")
            .because("E07 books invitations and campaign slots through the AppointmentBooking SPI");

    @ArchTest
    static final ArchRule schedulingIsIndependentOfClinicalModules = noClasses()
            .that().resideInAPackage("com.wellnessz.e14..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e03..", "com.wellnessz.e05..", "com.wellnessz.e06..",
                    "com.wellnessz.e07..")
            .because("scheduling never reads clinical content; it serves the other modules through SPIs");

    @ArchTest
    static final ArchRule intakeDoesNotDependOnTriage = noClasses()
            .that().resideInAPackage("com.wellnessz.e01..")
            .should().dependOnClassesThat().resideInAPackage("com.wellnessz.e02..")
            .because("E02 depends on E01 (RecipientResolver SPI); the reverse would create a cycle");

    @ArchTest
    static final ArchRule intakeDoesNotCallCaseKernelDirectly = noClasses()
            .that().resideInAPackage("com.wellnessz.ehsintake..")
            .should().dependOnClassesThat().resideInAPackage("com.wellnessz.casekernel..")
            .because("EHS intake communicates with the case kernel only via ehs.incident.received.v1 events");

    @ArchTest
    static final ArchRule commonHasNoSpringSecurity = noClasses()
            .that().resideInAPackage("com.wellnessz.common..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.security..");

    @ArchTest
    static final ArchRule noStandardStreams = noClasses()
            .that().resideOutsideOfPackage("com.wellnessz.secrets")
            .should().accessField(System.class, "out").orShould().accessField(System.class, "err")
            .because("use structured logging; never print PHI to stdout");
}
