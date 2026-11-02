package com.atlas;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundaryTest {

    @Test
    void modulesHaveNoCyclesAndUseOnlyPublishedApis() {
        ApplicationModules.of(AtlasApplication.class).verify();
    }
}
