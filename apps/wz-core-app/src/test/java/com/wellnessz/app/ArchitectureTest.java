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
                    "com.wellnessz.documents..", "com.wellnessz.e01..", "com.wellnessz.e02..", "com.wellnessz.e03..", "com.wellnessz.e05..");

    @ArchTest
    static final ArchRule kernelDoesNotDependOnBusinessModules = noClasses()
            .that().resideInAPackage("com.wellnessz.casekernel..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e01..", "com.wellnessz.e02..", "com.wellnessz.e03..",
                    "com.wellnessz.e05..", "com.wellnessz.documents..")
            .because("modules plug into the kernel through SPIs (CaseModule, CaseTransitionGuard, ChartContributor, events), never the reverse");

    @ArchTest
    static final ArchRule documentServiceIsAPlatformService = noClasses()
            .that().resideInAPackage("com.wellnessz.documents..")
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e01..", "com.wellnessz.e02..", "com.wellnessz.e03..",
                    "com.wellnessz.e05..")
            .because("E03 and E05 both use the document service; it must not know about either");

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
            .should().dependOnClassesThat().resideInAnyPackage("com.wellnessz.e03..", "com.wellnessz.e05..");

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
