# syntax=docker/dockerfile:1
#
# Standard JVM image: build with the Maven wrapper, run the Spring Boot jar on a JRE.
#
#   docker build -t resourceapi .
#   docker run --rm -p 8080:8080 resourceapi
#
# Tests are not run here (CI runs them); see docs/docker.md.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Dependencies first, so they stay cached while only the sources change
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Build the jar and split it into layers (dependencies change less often than the application)
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && cp target/resourceapi-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 1001 --no-create-home spring

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
