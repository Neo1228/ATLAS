package com.atlas;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

/**
 * 모든 모듈이 같은 패키지 어휘와 명명 규칙을 따르는지 검사한다.
 *
 * <p>이 파일은 {@code config/archunit}에 한 벌만 두고, ArchUnit을 쓰는 모듈(application,
 * inventory-location)의 test source set이 함께 컴파일한다. 규칙을 모듈마다 복사하면 어긋나므로
 * 규칙을 바꿀 때는 이 파일 하나만 고친다. 규칙의 근거는 {@code AGENTS.md}의 "패키지 구조와 명명".
 * 모듈 사이 의존 방향은 모듈 구성이 다르므로 각 모듈의 경계 테스트가 따로 검사한다.
 *
 * <p>그 종류의 클래스가 아예 없는 모듈도 있다(예: 설정 클래스가 없는 모듈). 그것은 위반이 아니므로 대상이
 * 비어도 통과하도록 {@code allowEmptyShould(true)}를 쓴다. 기본값은 대상이 비면 실패한다.
 */
class PackageLayoutRulesTest {

    @Test
    void moduleRootPackagesContainOnlyApplicationEntryPoints() {
        // 모듈 루트에 설정, 보안, 헬스 같은 클래스가 혼자 놓이면 어느 계층인지 알 수 없다.
        JavaClasses source = productionClasses();
        classes()
                .that().resideInAnyPackage("com.atlas", "com.atlas.*", "com.atlas.inventory.location")
                .should().haveSimpleNameEndingWith("Application")
                .allowEmptyShould(true)
                .check(source);
    }

    @Test
    void configurationsResideInConfigPackages() {
        JavaClasses source = productionClasses();
        classes()
                .that().areAnnotatedWith(Configuration.class)
                .or().haveSimpleNameEndingWith("Configuration")
                .should().resideInAPackage("..config..")
                .allowEmptyShould(true)
                .check(source);
    }

    @Test
    void persistenceApisAreUsedOnlyByRepositoriesAndPersistenceTypes() {
        // SQL과 영속 API는 저장소 계층이 소유한다. 서비스가 직접 쓰면 위치 규칙과 DTO 의존 규칙을 피한다.
        // 영속 API가 없는 모듈에서도 컴파일되도록 클래스 리터럴 대신 단순 이름으로 건다.
        JavaClasses source = productionClasses();
        noClasses()
                .that().resideOutsideOfPackages("..repository..", "..persistence..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("JdbcTemplate")
                .orShould().dependOnClassesThat().haveSimpleName("JdbcClient")
                .orShould().dependOnClassesThat().haveSimpleName("EntityManager")
                .check(source);
    }

    @Test
    void dtoTypesAreNamedAsRequestsResponsesOrPages() {
        JavaClasses source = productionClasses();
        classes()
                .that().resideInAnyPackage("..dto.request..", "..dto.response..")
                .and().areTopLevelClasses()
                .and().areNotEnums()
                .should().haveSimpleNameEndingWith("Request")
                .orShould().haveSimpleNameEndingWith("Response")
                .orShould().haveSimpleNameEndingWith("Page")
                .orShould().haveSimpleNameEndingWith("Summary")
                .allowEmptyShould(true)
                .check(source);
    }

    @Test
    void serviceImplementationsFollowTheImplNamingConvention() {
        JavaClasses source = productionClasses();
        classes()
                .that().haveSimpleNameEndingWith("ServiceImpl")
                .should().resideInAPackage("..service.impl")
                .allowEmptyShould(true)
                .check(source);
        classes()
                .that().resideInAPackage("..service.impl")
                .and().areAnnotatedWith(Service.class)
                .should().haveSimpleNameEndingWith("Impl")
                .allowEmptyShould(true)
                .check(source);
    }

    @Test
    void classNamesDoNotUseCatchAllSuffixes() {
        JavaClasses source = productionClasses();
        noClasses()
                .should().haveSimpleNameEndingWith("Helper")
                .orShould().haveSimpleNameEndingWith("Util")
                .orShould().haveSimpleNameEndingWith("Utils")
                .orShould().haveSimpleNameEndingWith("Manager")
                .orShould().haveSimpleNameEndingWith("Support")
                .orShould().haveSimpleNameEndingWith("Common")
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
                .allowEmptyShould(true)
                .check(source);
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.atlas");
    }
}
