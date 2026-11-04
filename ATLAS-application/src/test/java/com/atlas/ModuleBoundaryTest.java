package com.atlas;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ModuleBoundaryTest {

    @Test
    void dependenciesRespectLayeredModulePackages() {
        JavaClasses source = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.atlas");

        String[] javaAndFramework = {"java..", "jakarta..", "org.springframework..", "org.slf4j.."};
        String[] javaAndPersistence = append(javaAndFramework, "org.hibernate.annotations..", "org.hibernate.id.uuid..");
        String[] applicationDependencies = {
                "com.atlas.application..", "com.atlas",
                "com.atlas.campaign.service", "com.atlas.campaign.dto.request",
                "com.atlas.campaign.dto.response", "com.atlas.campaign.persistence.state", "com.atlas.campaign.exception",
                "com.atlas.admission.service", "com.atlas.admission.dto.request",
                "com.atlas.admission.dto.response", "com.atlas.admission.persistence.state", "com.atlas.admission.exception",
                "com.atlas.inventory.service", "com.atlas.inventory.dto.request",
                "com.atlas.inventory.dto.response", "com.atlas.inventory.persistence.state", "com.atlas.inventory.exception",
                "com.atlas.order.service", "com.atlas.order.dto.request",
                "com.atlas.order.dto.response", "com.atlas.order.persistence.state", "com.atlas.order.exception",
                "com.atlas.payment.service", "com.atlas.payment.dto.request",
                "com.atlas.payment.dto.response", "com.atlas.payment.persistence.state", "com.atlas.payment.exception",
                "com.atlas.catalog.service", "com.atlas.catalog.dto.request",
                "com.atlas.catalog.dto.response", "com.atlas.catalog.exception",
                "com.atlas.cart.service", "com.atlas.cart.dto.request",
                "com.atlas.cart.dto.response", "com.atlas.cart.exception",
                "java..", "jakarta..", "org.springframework..", "org.slf4j.."
        };
        enforceAllowedDependencies(source, "com.atlas.application..", applicationDependencies);
        enforceAllowedDependencies(source, "com.atlas", applicationDependencies);

        enforceAllowedDependencies(source, "com.atlas.campaign..",
                append(javaAndPersistence, "com.atlas.campaign..",
                        "com.atlas.catalog.service", "com.atlas.catalog.dto.response"));
        enforceAllowedDependencies(source, "com.atlas.admission..",
                append(javaAndPersistence, "com.atlas.admission..",
                        "com.atlas.campaign.service", "com.atlas.campaign.dto.response",
                        "com.atlas.campaign.persistence.state", "com.atlas.campaign.exception",
                        "com.atlas.catalog.service", "com.atlas.catalog.dto.response"));
        enforceAllowedDependencies(source, "com.atlas.inventory..",
                append(javaAndPersistence, "com.atlas.inventory.."));
        enforceAllowedDependencies(source, "com.atlas.catalog..",
                append(javaAndPersistence, "com.atlas.catalog..",
                        "com.atlas.inventory.service", "com.atlas.inventory.dto.request"));
        enforceAllowedDependencies(source, "com.atlas.cart..",
                append(javaAndPersistence, "com.atlas.cart..",
                        "com.atlas.catalog.service", "com.atlas.catalog.dto.response"));
        enforceAllowedDependencies(source, "com.atlas.order..",
                append(javaAndPersistence, "com.atlas.order..",
                        "com.atlas.admission.service", "com.atlas.admission.dto.request",
                        "com.atlas.admission.dto.response", "com.atlas.admission.persistence.state", "com.atlas.admission.exception",
                        "com.atlas.inventory.service", "com.atlas.inventory.dto.request",
                        "com.atlas.inventory.dto.response", "com.atlas.inventory.persistence.state", "com.atlas.inventory.exception",
                        "com.atlas.payment.service", "com.atlas.payment.dto.request",
                        "com.atlas.payment.dto.response", "com.atlas.payment.persistence.state", "com.atlas.payment.exception",
                        "com.atlas.catalog.service", "com.atlas.catalog.dto.request", "com.atlas.catalog.dto.response",
                        "com.atlas.cart.service", "com.atlas.cart.dto.request", "com.atlas.cart.dto.response"));
        enforceAllowedDependencies(source, "com.atlas.payment..",
                append(javaAndPersistence, "com.atlas.payment.."));
        noClasses().that().resideInAPackage("com.atlas..")
                .and().resideOutsideOfPackage("..persistence.entity..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.hibernate.annotations..", "org.hibernate.id.uuid..")
                .check(source);

    }

    private static void enforceAllowedDependencies(JavaClasses source, String packagePattern, String... allowedPackages) {
        ArchRule rule = classes().that().resideInAPackage(packagePattern)
                .should().onlyDependOnClassesThat().resideInAnyPackage(allowedPackages);
        rule.check(source);
    }

    private static String[] append(String[] first, String... rest) {
        String[] all = new String[first.length + rest.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(rest, 0, all, first.length, rest.length);
        return all;
    }
}
