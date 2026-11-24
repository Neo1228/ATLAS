package com.atlas.support;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.testcontainers.junit.jupiter.Container;

/**
 * 테스트 클래스가 {@code static @Container}를 가지면 클래스가 끝날 때 그 Spring 컨텍스트를 바로 닫는다.
 *
 * <p>컨텍스트 캐시는 컨텍스트를 클래스 뒤에도 살려 둔다. 그러나 이 컨텍스트의 DataSource URL은
 * {@code @DynamicPropertySource}로 그 클래스의 컨테이너를 가리키므로 다른 클래스가 재사용할 수 없고, 컨테이너는
 * 클래스가 끝나면 사라진다. 죽은 DB를 가리키는 컨텍스트가 남으면 Hikari 풀이 계속 재연결을 시도하고
 * {@code @Scheduled} 작업이 {@code getConnection}에서 최대 30초 막힌다. 그 컨텍스트를 일시 정지하거나 캐시에서 쫓아낼 때와
 * JVM이 끝날 때 테스트 워커 스레드가 그만큼 기다린다(EXP-074).
 *
 * <p>JUnit은 확장의 {@code afterAll}을 등록 역순으로 부르므로, 클래스 어노테이션이 {@code @Testcontainers},
 * {@code @SpringBootTest} 순서일 때 이 리스너는 컨테이너가 아직 살아 있을 때 컨텍스트를 닫는다.
 * 이 파일은 {@code META-INF/spring.factories}로 등록된다.
 */
public class ContainerBoundContextCloser extends AbstractTestExecutionListener {

    /** {@code DirtiesContextTestExecutionListener}와 같은 위치라서 {@code @DirtiesContext} 클래스 종료와 같은 순서로 닫힌다. */
    @Override
    public int getOrder() {
        return 3000;
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        if (testContext.hasApplicationContext() && hasStaticContainer(testContext.getTestClass())) {
            testContext.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
        }
    }

    static boolean hasStaticContainer(Class<?> testClass) {
        for (Class<?> type = testClass; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.isAnnotationPresent(Container.class)) {
                    return true;
                }
            }
        }
        return false;
    }
}
