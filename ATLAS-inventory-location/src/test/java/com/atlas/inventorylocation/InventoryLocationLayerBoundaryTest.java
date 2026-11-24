package com.atlas.inventorylocation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class InventoryLocationLayerBoundaryTest {
    @Test
    void controllerDoesNotDependOnRepository() {
        noClasses().that().resideInAPackage("com.atlas.inventorylocation.controller..")
                .should().dependOnClassesThat().resideInAPackage("com.atlas.inventorylocation.repository..")
                .check(productionClasses());
    }

    @Test
    void serviceDoesNotDependOnController() {
        noClasses().that().resideInAPackage("com.atlas.inventorylocation.service..")
                .should().dependOnClassesThat().resideInAPackage("com.atlas.inventorylocation.controller..")
                .check(productionClasses());
    }

    @Test
    void repositoryDoesNotDependOnHigherLayers() {
        noClasses().that().resideInAPackage("com.atlas.inventorylocation.repository..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.atlas.inventorylocation.controller..",
                        "com.atlas.inventorylocation.service..",
                        "com.atlas.inventorylocation.dto..")
                .check(productionClasses());
    }

    @Test
    void dtosDoNotDependOnRuntimeLayers() {
        noClasses().that().resideInAPackage("com.atlas.inventorylocation.dto..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.atlas.inventorylocation.controller..",
                        "com.atlas.inventorylocation.service..",
                        "com.atlas.inventorylocation.repository..")
                .check(productionClasses());
    }

    @Test
    void repositoryAndDtoLayersDoNotDependOnExceptionHandling() {
        noClasses().that().resideInAnyPackage(
                        "com.atlas.inventorylocation.repository..",
                        "com.atlas.inventorylocation.dto..")
                .should().dependOnClassesThat().resideInAPackage("com.atlas.inventorylocation.exception..")
                .check(productionClasses());
    }

    @Test
    void securityLayerDoesNotDependOnBusinessLayers() {
        noClasses().that().resideInAPackage("com.atlas.inventorylocation.security..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.atlas.inventorylocation.controller..",
                        "com.atlas.inventorylocation.service..",
                        "com.atlas.inventorylocation.repository..")
                .check(productionClasses());
    }

    private JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.atlas.inventorylocation");
    }
}
