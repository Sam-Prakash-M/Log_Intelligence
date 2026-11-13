# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
# Optional: build through a Maven Central mirror (corporate proxy, blocked network).
ARG MAVEN_CENTRAL_MIRROR=""
RUN if [ -n "$MAVEN_CENTRAL_MIRROR" ]; then \
      mkdir -p /root/.m2 && \
      printf '<settings><mirrors><mirror><id>central</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' \
        "$MAVEN_CENTRAL_MIRROR" > /root/.m2/settings.xml; \
    fi
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q -DskipTests package && \
    cp target/*.jar app.jar && \
    java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 1001 --no-create-home spring
# One image layer per Spring Boot layer: dependency layers stay cached across code changes.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
