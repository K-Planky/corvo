# syntax=docker/dockerfile:1
# Multi-stage build for the Othello server (spec §12/§13). The thin React client is built and
# bundled into the Spring jar's static resources, so a SINGLE image serves the API and the SPA
# same-origin (the reverse proxy is then just TLS termination — see Caddyfile/compose.prod.yaml).
# The Node + JDK build stages are discarded; only the slim Alpine JRE runtime ships, and that final
# image is what Trivy's image scan inspects.

# 1) Build the SPA.
FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build   # -> /web/dist

# 2) Build the bootable jar, bundling the SPA into the classpath static resources.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
COPY --from=web /web/dist/ src/main/resources/static/
RUN ./mvnw -B -ntp -DskipTests clean package

# 3) Runtime: slim, patched, non-root.
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
# Patch OS packages so the runtime carries no fixable CVEs the base image lags on (e.g. p11-kit).
RUN apk -U upgrade --no-cache \
 && addgroup -S app && adduser -S -G app app
COPY --from=build /app/target/othello-server-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
