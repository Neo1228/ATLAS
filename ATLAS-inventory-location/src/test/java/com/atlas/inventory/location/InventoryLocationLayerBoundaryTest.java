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
