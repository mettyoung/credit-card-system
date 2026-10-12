# syntax=docker/dockerfile:1
# ONE image for Cloudflare Containers: Spring Boot app + Postgres + MinIO + Flyway (+ the Onfido WireMock stand-in),
# all under supervisord. Build context is the REPOSITORY ROOT (wrangler.jsonc sets image_build_context "..").
#
#   docker build -f cf-container/Dockerfile -t creditcard-cf .        # from the repo root
#   docker run --rm -p 8080:8080 creditcard-cf
#
# Cloudflare Containers run linux/amd64 only. The repo-root .dockerignore applies (it re-includes mock/onfido).

# ---- 1. the application jar (same recipe as the root Dockerfile) -------------------------------------------
FROM --platform=linux/amd64 eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
# The architecture pages the jar serves under /architecture/; .dockerignore lets only those two through.
COPY docs ./docs
RUN ./gradlew --no-daemon bootJar -x test \
 && cp $(ls build/libs/*.jar | grep -v plain) /app.jar

# ---- 2. borrowed binaries ---------------------------------------------------------------------------------
# MinIO's own images are no longer pullable; Chainguard ships the same server (and mc) as static Go binaries.
FROM --platform=linux/amd64 cgr.dev/chainguard/minio:latest AS minio
FROM --platform=linux/amd64 flyway/flyway:12 AS flyway
FROM --platform=linux/amd64 wiremock/wiremock:3.13.2-alpine AS wiremock

# ---- 3. runtime ---------------------------------------------------------------------------------------------
FROM --platform=linux/amd64 eclipse-temurin:21-jre

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql postgresql-contrib supervisor curl ca-certificates openssl \
 && rm -rf /var/lib/apt/lists/* \
 # Debian/Ubuntu's packaged cluster is not used; ours lives in /data/postgres.
 && rm -rf /var/lib/postgresql/*/main /etc/postgresql/*/main \
 && ln -s "$(ls -d /usr/lib/postgresql/*/bin | sort -V | tail -1)" /usr/local/pgbin

COPY --from=minio    /usr/bin/minio /usr/local/bin/minio
COPY --from=minio    /usr/bin/mc    /usr/local/bin/mc
COPY --from=flyway   /flyway        /opt/flyway
COPY --from=wiremock /var/wiremock/lib/wiremock-standalone.jar /opt/wiremock/wiremock-standalone.jar
COPY mock/onfido /opt/onfido-mock
COPY src/main/resources/db/migration /opt/flyway/sql
COPY --from=build /app.jar /app/app.jar

COPY cf-container/supervisord.conf /etc/supervisor/supervisord.conf
COPY cf-container/entrypoint.sh cf-container/bootstrap.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/bootstrap.sh /opt/flyway/flyway \
 && minio --version >/dev/null && mc --version >/dev/null \
 && mkdir -p /data/postgres /data/minio /var/run/postgresql /var/log/supervisor \
 && chown -R postgres:postgres /data/postgres /var/run/postgresql

# NOTE: /data is NOT persistent on Cloudflare Containers. Every cold start (after sleepAfter, a redeploy, or a
# host move) begins with an empty /data. See cf-container/README.md.
# Bump to force a container rollout (new image digest => every instance restarts and picks up the Worker's current
# vars). Needed once when the running container predates the restart-on-var-change logic in src/index.ts.
LABEL com.mettyoung.creditcard.rollout="2026-10-11-timeline"
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
