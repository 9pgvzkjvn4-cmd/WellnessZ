package io.intimed;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Architecture boundaries of the logical architecture (§5.1): engine core is independent of the control plane. */
@AnalyzeClasses(packages = "io.intimed", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule engineDoesNotDependOnControlPlane = noClasses().that().resideInAPackage("io.intimed.engine..")
            .should().dependOnClassesThat().resideInAnyPackage("io.intimed.console..", "io.intimed.harness..", "io.intimed.web..", "io.intimed.demo..", "io.intimed.catalogue..");

    @ArchTest
    static final ArchRule securityDoesNotDependOnEngineOrControlPlane = noClasses().that().resideInAPackage("io.intimed.security..")
            .should().dependOnClassesThat().resideInAnyPackage("io.intimed.engine..", "io.intimed.console..", "io.intimed.harness..", "io.intimed.web..", "io.intimed.demo..");

    @ArchTest
    static final ArchRule nothingDependsOnTheDemo = noClasses().that().resideOutsideOfPackage("io.intimed.demo..")
            .should().dependOnClassesThat().resideInAPackage("io.intimed.demo..");

    @ArchTest
    static final ArchRule templatesAreHostAgnostic = noClasses().that().resideInAPackage("io.intimed.templates..")
            .should().dependOnClassesThat().resideInAnyPackage("io.intimed.engine..", "io.intimed.console..", "io.intimed.harness..");
}
