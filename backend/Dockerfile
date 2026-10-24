# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
# The cache mount keeps downloaded dependencies between builds, so a retry after a dropped
# download doesn't start from scratch.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests \
        -Daether.transport.http.retryHandler.count=5 \
        -Daether.connector.http.retryHandler.count=5 \
        package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 1001 pos
COPY --from=build /src/target/pos-api-*.jar app.jar
USER pos
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
