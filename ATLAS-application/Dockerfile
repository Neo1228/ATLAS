FROM eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0

WORKDIR /app
COPY build/libs/application-module.jar /app/atlas.jar
ENTRYPOINT ["java", "-jar", "/app/atlas.jar"]
