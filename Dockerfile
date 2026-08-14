# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies change far less often than source, so resolve them in their own
# layer. Editing a .java file then rebuilds in seconds instead of re-downloading
# the whole dependency tree.
COPY pom.xml .
RUN mvn -B dependency:go-offline -DskipTests

COPY src ./src
# Tests are skipped here on purpose: they need a Docker daemon for Testcontainers,
# which is not available inside this build. Run ./mvnw test before deploying.
RUN mvn -B clean package -DskipTests

# ---- run ------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Never run the bot as root; it only needs to read its own jar.
RUN addgroup -S bot && adduser -S bot -G bot
COPY --from=build /build/target/*.jar app.jar
USER bot

# Without this the JVM sizes its heap from the host's total RAM and gets OOM-killed
# on a small VPS. MaxRAMPercentage makes it respect the container limit instead.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
