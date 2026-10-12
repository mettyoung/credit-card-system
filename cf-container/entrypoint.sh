#!/usr/bin/env bash
# Container entrypoint: settle configuration, initialise empty data dirs, then hand over to supervisord.
# Secrets come from the Worker (wrangler secrets -> Container envVars). If absent, random per-boot values are
# generated: every process lives in this container and /data is ephemeral, so nothing outside needs them.
# Secret values are never echoed.
set -euo pipefail

# Cloudflare Containers hand PID 1 a stdout/stderr that cannot be re-opened by path (open("/dev/stdout") -> ENXIO).
# supervisord opens /dev/stdout for every program's log, so without this every program dies at spawn
# ("unknown error making dispatchers ... ENXIO") and nothing ever listens on :8080. Route our output through a
# pipe (which CAN be re-opened) to the original stdout.
exec > >(exec cat) 2>&1

export POSTGRES_DB="${POSTGRES_DB:-creditcard}"
export POSTGRES_USER="${POSTGRES_USER:-creditcard}"
export MINIO_ROOT_USER="${MINIO_ROOT_USER:-creditcard}"
export MINIO_BUCKET="${MINIO_BUCKET:-credit-card-documents}"
if [ -z "${POSTGRES_PASSWORD:-}" ]; then
  export POSTGRES_PASSWORD="$(openssl rand -hex 24)"; echo "entrypoint: POSTGRES_PASSWORD not supplied, generated one for this boot"
fi
if [ -z "${MINIO_ROOT_PASSWORD:-}" ]; then
  export MINIO_ROOT_PASSWORD="$(openssl rand -hex 24)"; echo "entrypoint: MINIO_ROOT_PASSWORD not supplied, generated one for this boot"
fi

# --- Spring Boot -------------------------------------------------------------------------------------------
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:5432/${POSTGRES_DB}"
export SPRING_DATASOURCE_USERNAME="${POSTGRES_USER}"
export SPRING_DATASOURCE_PASSWORD="${POSTGRES_PASSWORD}"
export SPRING_FLYWAY_ENABLED=false            # bootstrap.sh owns migrations; Hibernate still validates
# Object store. Presigned upload URLs are built from this endpoint, so for browser uploads it must be the public
# origin (PUBLIC_URL, e.g. https://creditcardapplication.mettyoung.com); the Worker routes S3-signed requests on
# that origin to MinIO. Without PUBLIC_URL everything works except browser uploads.
export APP_STORAGE_ENDPOINT="${PUBLIC_URL:-http://127.0.0.1:9000}"
export APP_STORAGE_BUCKET="${MINIO_BUCKET}"
export APP_STORAGE_ACCESS_KEY="${MINIO_ROOT_USER}"
export APP_STORAGE_SECRET_KEY="${MINIO_ROOT_PASSWORD}"
export APP_STORAGE_CORS_ALLOWED_ORIGINS="${PUBLIC_URL:-http://localhost:8080}"
export APP_ONFIDO_BASE_URL="http://127.0.0.1:8081"
export APP_UI_TIMELINE_ENABLED="${APP_UI_TIMELINE_ENABLED:-false}"   # shows vendor outcomes; off when public
# standard-1 = 4 GiB shared with Postgres, MinIO and WireMock; cap the app heap explicitly.
export APP_JAVA_OPTS="${APP_JAVA_OPTS:--Xmx1536m -XX:+UseSerialGC}"   # applied to the app only (supervisord.conf)

# --- data dirs (fresh on every Cloudflare cold start) ------------------------------------------------------
mkdir -p /data/postgres /data/minio /var/run/postgresql
chown -R postgres:postgres /data/postgres /var/run/postgresql
chmod 700 /data/postgres
if [ ! -s /data/postgres/PG_VERSION ]; then
  echo "entrypoint: initialising Postgres cluster in /data/postgres"
  # Local socket = trust (only root/postgres in this container can reach it); TCP = password.
  su postgres -s /bin/sh -c "/usr/local/pgbin/initdb -D /data/postgres -U postgres --auth-local=trust --auth-host=scram-sha-256 -E UTF8 >/dev/null"
fi

exec /usr/bin/supervisord -c /etc/supervisor/supervisord.conf
