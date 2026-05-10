package com.usal.whbackend;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.usal.whbackend", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // Services must not depend on controllers — the key layering rule in standard Spring Boot
    @ArchTest
    static final ArchRule servicesMustNotDependOnControllers =
            noClasses()
                    .that().resideInAPackage("..service..")
                    .should().dependOnClassesThat()
                    .haveSimpleNameEndingWith("Controller");

    // Repositories are data-access only — they must not know about services or controllers
    @ArchTest
    static final ArchRule repositoriesMustNotDependOnServiceOrControllers =
            noClasses()
                    .that().resideInAPackage("..repository..")
                    .should().dependOnClassesThat()
                    .haveSimpleNameEndingWith("Service")
                    .orShould().dependOnClassesThat()
                    .haveSimpleNameEndingWith("Controller");
}
