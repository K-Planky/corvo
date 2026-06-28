# syntax=docker/dockerfile:1
# Multi-stage build for the Othello Spring Boot server (spec §12/§13).
# The JDK build stage compiles the bootable jar and is then discarded; only the slim
# Alpine JRE runtime ships, and that final image is what Trivy's image scan inspects.
# (M5: backend image only. If the deploy slice serves the SPA same-origin from the jar,
# the frontend build is added here in M5.4/M5.5 and the image is re-scanned.)

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# Resolve dependencies against the wrapper + pom first so this layer caches across
# source-only changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests clean package

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
# Patch OS packages so the runtime carries no fixable CVEs the base image lags on
# (e.g. p11-kit) — this is what keeps the Trivy image-scan baseline green without
# allow-listing fixable HIGHs. Then run unprivileged.
RUN apk -U upgrade --no-cache \
 && addgroup -S app && adduser -S -G app app
COPY --from=build /app/target/othello-server-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
