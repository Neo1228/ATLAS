package com.atlas.inventory.location;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class InventoryLocationLayerBoundaryTest {
    @Test
    void controllerDoesNotDependOnRepository() {
        noClasses().that().resideInAPackage("com.atlas.inventory.location.controller..")
                .should().dependOnClassesThat().resideInAPackage("com.atlas.inventory.location.repository..")
                .check(productionClasses());
    }

    @Test
    void serviceDoesNotDependOnController() {
        noClasses().that().resideInAPackage("com.atlas.inventory.location.service..")
                .should().dependOnClassesThat().resideInAPackage("com.atlas.inventory.location.controller..")
                .check(productionClasses());
    }

    @Test
    void repositoryDoesNotDependOnHigherLayers() {
        noClasses().that().resideInAPackage("com.atlas.inventory.location.repository..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.atlas.inventory.location.controller..",
                        "com.atlas.inventory.location.service..",
                        "com.atlas.inventory.location.dto..")
                .check(productionClasses());
    }

    @Test
    void dtosDoNotDependOnRuntimeLayers() {
        noClasses().that().resideInAPackage("com.atlas.inventory.location.dto..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.atlas.inventory.location.controller..",
                        "com.atlas.inventory.location.service..",
                        "com.atlas.inventory.location.repository..")
                .check(productionClasses());
    }

    @Test
    void repositoryAndDtoLayersDoNotDependOnExceptionHandling() {
        noClasses().that().resideInAnyPackage(
                        "com.atlas.inventory.location.repository..",
                        "com.atlas.inventory.location.dto..")
                .should().dependOnClassesThat().resideInAPackage("com.atlas.inventory.location.exception..")
                .check(productionClasses());
    }

    @Test
    void securityLayerDoesNotDependOnBusinessLayers() {
        noClasses().that().resideInAPackage("com.atlas.inventory.location.security..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.atlas.inventory.location.controller..",
                        "com.atlas.inventory.location.service..",
                        "com.atlas.inventory.location.repository..")
                .check(productionClasses());
    }

    private JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.atlas.inventory.location");
    }
}
