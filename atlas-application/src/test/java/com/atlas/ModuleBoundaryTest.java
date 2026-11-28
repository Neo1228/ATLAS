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
        JavaClasses source = productionClasses();

        String[] javaAndFramework = {"java..", "javax..", "jakarta..", "org.springframework..", "org.slf4j.."};
        String[] javaAndPersistence = append(javaAndFramework, "org.hibernate.annotations..", "org.hibernate.id.uuid..");
        String[] applicationDependencies = {
                "com.atlas.application..", "com.atlas",
                "com.atlas.admission.service", "com.atlas.admission.dto.request",
                "com.atlas.admission.dto.response", "com.atlas.admission.model", "com.atlas.admission.exception",
                "com.atlas.admission.service", "com.atlas.admission.dto.request",
                "com.atlas.admission.dto.response", "com.atlas.admission.model", "com.atlas.admission.exception",
                "com.atlas.inventory.service", "com.atlas.inventory.dto.request",
                "com.atlas.inventory.dto.response", "com.atlas.inventory.model", "com.atlas.inventory.exception",
                "com.atlas.order.service", "com.atlas.order.dto.request",
                "com.atlas.order.dto.response", "com.atlas.order.model", "com.atlas.order.exception",
                "com.atlas.payment.service", "com.atlas.payment.dto.request",
                "com.atlas.payment.dto.response", "com.atlas.payment.model", "com.atlas.payment.exception",
                "com.atlas.payment.provider",
                "com.atlas.payment.provider.exchange",
                "com.atlas.payment.provider.paypal",
                "com.atlas.payment.provider.paypal.payouts",
                "com.atlas.settlement.service", "com.atlas.settlement.model", "com.atlas.settlement.dto.request",
                "com.atlas.settlement.dto.response", "com.atlas.settlement.exception",
                "com.atlas.catalog.service", "com.atlas.catalog.model", "com.atlas.catalog.dto.request",
                "com.atlas.catalog.dto.response", "com.atlas.catalog.exception",
                "com.atlas.order.service", "com.atlas.order.dto.request",
                "com.atlas.order.dto.response", "com.atlas.order.exception",
                "com.atlas.account.service", "com.atlas.account.dto.request",
                "com.atlas.account.dto.response", "com.atlas.account.exception",
                "com.atlas.agent.service", "com.atlas.agent.dto.request",
                "com.atlas.agent.dto.response", "com.atlas.agent.exception",
                "net.ttddyy.dsproxy..",
                "com.nimbusds..",
                "io.micrometer..",
            // Redis admission gate의 client. 이 모듈 밖으로 퍼지지 않도록 application 패키지에서만 허용한다.
            "io.lettuce..",
            // 예약 이벤트 relay의 Kafka producer와 payload JSON. 이 모듈 밖으로 퍼지지 않도록 application 패키지에서만 허용한다.
            "org.apache.kafka..", "tools.jackson..",
                "java..", "javax..", "jakarta..", "org.springframework..", "org.slf4j.."
        };
        enforceAllowedDependencies(source, "com.atlas.application..", applicationDependencies);
        enforceAllowedDependencies(source, "com.atlas", applicationDependencies);

        enforceAllowedDependencies(source, "com.atlas.admission..",
                append(javaAndPersistence, "com.atlas.admission..",
                        "com.atlas.admission.service", "com.atlas.admission.dto.response",
                        "com.atlas.admission.model", "com.atlas.admission.exception",
                        "com.atlas.catalog.service", "com.atlas.catalog.model",
                        "com.atlas.catalog.dto.response"));
        enforceAllowedDependencies(source, "com.atlas.inventory..",
                append(javaAndPersistence, "com.atlas.inventory.."));
        enforceAllowedDependencies(source, "com.atlas.catalog..",
                append(javaAndPersistence, "com.atlas.catalog..",
                        "com.atlas.inventory.service", "com.atlas.inventory.dto.request"));
        enforceAllowedDependencies(source, "com.atlas.order..",
                append(javaAndPersistence, "com.atlas.order..",
                        "com.atlas.admission.service", "com.atlas.admission.dto.request",
                        "com.atlas.admission.dto.response", "com.atlas.admission.model", "com.atlas.admission.exception",
                        "com.atlas.inventory.service", "com.atlas.inventory.dto.request",
                        "com.atlas.inventory.dto.response", "com.atlas.inventory.model", "com.atlas.inventory.exception",
                        "com.atlas.payment.service", "com.atlas.payment.dto.request",
                        "com.atlas.payment.dto.response", "com.atlas.payment.model", "com.atlas.payment.exception",
                        "com.atlas.settlement.service", "com.atlas.settlement.model", "com.atlas.settlement.dto.request",
                        "com.atlas.settlement.dto.response", "com.atlas.settlement.exception",
                        "com.atlas.catalog.service", "com.atlas.catalog.model",
                        "com.atlas.catalog.dto.request", "com.atlas.catalog.dto.response",
                        "com.atlas.catalog.exception",
                        // 반품 권고의 LLM 검토 층(ADR-0110)은 LLM 클라이언트의 공개 계약만 쓴다.
                        "com.atlas.ai.service", "com.atlas.ai.dto.request", "com.atlas.ai.dto.response",
                        "com.atlas.ai.model", "com.atlas.ai.exception",
                        "com.atlas.order.service", "com.atlas.order.dto.request", "com.atlas.order.dto.response"));
        enforceAllowedDependencies(source, "com.atlas.payment..",
                append(javaAndPersistence, "com.atlas.payment..", "tools.jackson.."));
        enforceAllowedDependencies(source, "com.atlas.settlement..",
                append(javaAndPersistence, "com.atlas.settlement..", "org.apache.commons.csv.."));
        enforceAllowedDependencies(source, "com.atlas.account..",
                append(javaAndPersistence, "com.atlas.account..", "com.bastiaanjansen.otp.."));
        // LLM 클라이언트는 어떤 도메인 모듈에도 의존하지 않는다(ADR-0109). 그래야 여러 도메인 모듈이 순환 없이 쓴다.
        enforceAllowedDependencies(source, "com.atlas.ai..",
                append(javaAndFramework, "com.atlas.ai..", "tools.jackson.."));
        // 쇼핑 비서는 다른 모듈의 공개 계약(service, dto, model, exception)만 쓴다. 저장소·엔티티·구현에는 닿지 않는다.
        enforceAllowedDependencies(source, "com.atlas.agent..",
                append(javaAndFramework, "com.atlas.agent..", "tools.jackson..",
                        "com.atlas.ai.service", "com.atlas.ai.dto.request", "com.atlas.ai.dto.response",
                        "com.atlas.ai.model", "com.atlas.ai.exception",
                        "com.atlas.catalog.service", "com.atlas.catalog.dto.response", "com.atlas.catalog.model",
                        "com.atlas.catalog.exception",
                        "com.atlas.order.service", "com.atlas.order.dto.response", "com.atlas.order.exception",
                        "com.atlas.payment.exception"));
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
    void applicationServicesDoNotDependOnControllers() {
        // 서비스가 controller 패키지의 타입을 쓰면 계층이 거꾸로 의존한다. 서비스와 controller가 함께 쓰는 값은 application.dto에 둔다.
        JavaClasses source = productionClasses();
        noClasses()
                .that().resideInAPackage("com.atlas.application.service..")
                .should().dependOnClassesThat().resideInAPackage("com.atlas.application.controller..")
                .allowEmptyShould(true)
                .check(source);
    }

    @Test
    void controllersDeclareNoDataTypes() {
        // controller는 HTTP 매핑만 한다. 요청·응답 본문은 application.dto의 묶음 클래스(중첩 record)에 둔다.
        JavaClasses source = productionClasses();
        noClasses()
                .that().resideInAPackage("com.atlas.application.controller..")
                .should().beRecords()
                .orShould().beEnums()
                .orShould().beNestedClasses()
                .allowEmptyShould(true)
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

    @Test
    void domainModulesExposeServicesOnlyAsContracts() {
        // 다른 모듈이 소비하는 서비스는 interface + *Impl이다(구체 클래스를 노출하면 소비자가 구현에 묶이고 mock·교체가 어렵다).
        // 공개 service 패키지에 공개 구체 클래스가 있으면 이 규칙에 걸린다. 한 모듈 안에서만 쓰는 구체 클래스는 package-private으로 둔다.
        JavaClasses source = productionClasses();
        noClasses()
                .that().resideInAPackage("com.atlas..service")
                .and().resideOutsideOfPackage("com.atlas.application..")
                .and().arePublic()
                .and().areTopLevelClasses()
                .and().areNotInterfaces()
                .and().areNotRecords()
                .and().areNotEnums()
                .should().bePublic()
                .allowEmptyShould(true)
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
