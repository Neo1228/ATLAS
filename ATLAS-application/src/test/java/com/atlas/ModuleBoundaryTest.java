package com.atlas;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;

class ModuleBoundaryTest {

    @Test
    void dependenciesRespectLayeredModulePackages() {
        JavaClasses source = productionClasses();

        String[] javaAndFramework = {"java..", "javax..", "jakarta..", "org.springframework..", "org.slf4j.."};
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
                "com.atlas.payment.provider",
                "com.atlas.payment.provider.exchange",
                "com.atlas.payment.provider.paypal",
                "com.atlas.payment.provider.paypal.payouts",
                "com.atlas.settlement.service", "com.atlas.settlement.dto.request",
                "com.atlas.settlement.dto.response", "com.atlas.settlement.exception",
                "com.atlas.catalog.service", "com.atlas.catalog.persistence.state", "com.atlas.catalog.dto.request",
                "com.atlas.catalog.dto.response", "com.atlas.catalog.exception",
                "com.atlas.cart.service", "com.atlas.cart.dto.request",
                "com.atlas.cart.dto.response", "com.atlas.cart.exception",
                "com.atlas.account.service", "com.atlas.account.dto.request",
                "com.atlas.account.dto.response", "com.atlas.account.domain",
                "net.ttddyy.dsproxy..",
                "com.nimbusds..",
                "org.apache.commons.csv..",
                "io.micrometer..",
            // Redis admission gate의 client. 이 모듈 밖으로 퍼지지 않도록 application 패키지에서만 허용한다.
            "io.lettuce..",
            // 예약 이벤트 relay의 Kafka producer와 payload JSON. 이 모듈 밖으로 퍼지지 않도록 application 패키지에서만 허용한다.
            "org.apache.kafka..", "tools.jackson..",
                "java..", "javax..", "jakarta..", "org.springframework..", "org.slf4j.."
        };
        enforceAllowedDependencies(source, "com.atlas.application..", applicationDependencies);
        enforceAllowedDependencies(source, "com.atlas", applicationDependencies);

        enforceAllowedDependencies(source, "com.atlas.campaign..",
                append(javaAndPersistence, "com.atlas.campaign..",
                        "com.atlas.catalog.service", "com.atlas.catalog.persistence.state",
                        "com.atlas.catalog.dto.response"));
        enforceAllowedDependencies(source, "com.atlas.admission..",
                append(javaAndPersistence, "com.atlas.admission..",
                        "com.atlas.campaign.service", "com.atlas.campaign.dto.response",
                        "com.atlas.campaign.persistence.state", "com.atlas.campaign.exception",
                        "com.atlas.catalog.service", "com.atlas.catalog.persistence.state",
                        "com.atlas.catalog.dto.response"));
        enforceAllowedDependencies(source, "com.atlas.inventory..",
                append(javaAndPersistence, "com.atlas.inventory.."));
        enforceAllowedDependencies(source, "com.atlas.catalog..",
                append(javaAndPersistence, "com.atlas.catalog..",
                        "com.atlas.inventory.service", "com.atlas.inventory.dto.request"));
        enforceAllowedDependencies(source, "com.atlas.cart..",
                append(javaAndPersistence, "com.atlas.cart..",
                        "com.atlas.catalog.service", "com.atlas.catalog.persistence.state",
                        "com.atlas.catalog.dto.response"));
        enforceAllowedDependencies(source, "com.atlas.order..",
                append(javaAndPersistence, "com.atlas.order..",
                        "com.atlas.admission.service", "com.atlas.admission.dto.request",
                        "com.atlas.admission.dto.response", "com.atlas.admission.persistence.state", "com.atlas.admission.exception",
                        "com.atlas.inventory.service", "com.atlas.inventory.dto.request",
                        "com.atlas.inventory.dto.response", "com.atlas.inventory.persistence.state", "com.atlas.inventory.exception",
                        "com.atlas.payment.service", "com.atlas.payment.dto.request",
                        "com.atlas.payment.dto.response", "com.atlas.payment.persistence.state", "com.atlas.payment.exception",
                        "com.atlas.settlement.service", "com.atlas.settlement.dto.request",
                        "com.atlas.settlement.dto.response", "com.atlas.settlement.exception",
                        "com.atlas.catalog.service", "com.atlas.catalog.persistence.state",
                        "com.atlas.catalog.dto.request", "com.atlas.catalog.dto.response",
                        "com.atlas.catalog.exception",
                        "com.atlas.cart.service", "com.atlas.cart.dto.request", "com.atlas.cart.dto.response"));
        enforceAllowedDependencies(source, "com.atlas.payment..",
                append(javaAndPersistence, "com.atlas.payment..", "tools.jackson.."));
        enforceAllowedDependencies(source, "com.atlas.settlement..",
                append(javaAndPersistence, "com.atlas.settlement.."));
        enforceAllowedDependencies(source, "com.atlas.account..",
                append(javaAndPersistence, "com.atlas.account..", "com.bastiaanjansen.otp.."));
        noClasses().that().resideInAPackage("com.atlas..")
                .and().resideOutsideOfPackage("..persistence.entity..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.hibernate.annotations..", "org.hibernate.id.uuid..")
                .check(source);

    }

    @Test
    void applicationControllersDoNotDependOnPersistenceOrServiceImplementations() {
        JavaClasses source = productionClasses();
        noClasses()
                .that().resideInAPackage("com.atlas.application.controller..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.atlas..repository..", "com.atlas..persistence.entity..",
                        "com.atlas..persistence.projection..", "com.atlas..service.impl..",
                        "com.atlas..exception..")
                .check(source);
    }

    @Test
    void repositoriesDoNotDependOnPublicDtosOrBusinessExceptions() {
        JavaClasses source = productionClasses();
        noClasses()
                .that().resideInAPackage("com.atlas..repository..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.atlas..dto.request..", "com.atlas..dto.response..",
                        "com.atlas..exception..", "com.atlas.application.controller..")
                .check(source);
    }

    @Test
    void repositoriesResideInRepositoryPackages() {
        // 위의 의존 규칙은 repository 패키지만 검사한다. 저장소가 service 같은 다른 패키지에 있으면 검사를 받지 못한다.
        JavaClasses source = productionClasses();
        classes()
                .that().areAnnotatedWith(Repository.class)
                .or().haveSimpleNameEndingWith("Repository")
                .should().resideInAPackage("com.atlas..repository..")
                .check(source);
    }

    @Test
    void publicServiceContractsDoNotDependOnPersistenceAdaptersOrEntities() {
        JavaClasses source = productionClasses();
        noClasses()
                .that().resideInAPackage("..service")
                .and().areInterfaces()
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.atlas..repository..", "com.atlas..persistence.entity..",
                        "com.atlas..persistence.projection..")
                .check(source);
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.atlas");
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
