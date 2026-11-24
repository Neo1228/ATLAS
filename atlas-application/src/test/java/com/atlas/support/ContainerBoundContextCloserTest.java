package com.atlas.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;

class ContainerBoundContextCloserTest {

    @Test
    void detectsStaticContainerInClassAndSuperclass() {
        assertThat(ContainerBoundContextCloser.hasStaticContainer(WithStaticContainer.class)).isTrue();
        assertThat(ContainerBoundContextCloser.hasStaticContainer(InheritsStaticContainer.class)).isTrue();
    }

    @Test
    void ignoresInstanceContainerAndPlainClass() {
        assertThat(ContainerBoundContextCloser.hasStaticContainer(WithInstanceContainer.class)).isFalse();
        assertThat(ContainerBoundContextCloser.hasStaticContainer(Plain.class)).isFalse();
    }

    static class WithStaticContainer {
        @Container
        static final Object POSTGRES = new Object();
    }

    static class InheritsStaticContainer extends WithStaticContainer {
    }

    static class WithInstanceContainer {
        @Container
        final Object postgres = new Object();
    }

    static class Plain {
    }
}
