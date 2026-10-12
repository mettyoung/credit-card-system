#!/usr/bin/env bash
# One-shot, run by supervisord after postgres/minio start: role + database, bucket, flyway migrate, then the app.
set -euo pipefail
log() { echo "bootstrap: $*"; }

log "waiting for Postgres"
for i in $(seq 1 120); do /usr/local/pgbin/pg_isready -h /var/run/postgresql -U postgres -q && break; sleep 1; done
/usr/local/pgbin/pg_isready -h /var/run/postgresql -U postgres -q || { log "Postgres never became ready"; exit 1; }

# Idempotent: create or re-password the app role, create the database. Password passed as a psql variable.
psql_su() { /usr/local/pgbin/psql -h /var/run/postgresql -U postgres -v ON_ERROR_STOP=1 -qAt "$@"; }
psql_su -v u="$POSTGRES_USER" -v p="$POSTGRES_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'u') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'u') \gexec
ALTER ROLE :"u" WITH LOGIN PASSWORD :'p';
SQL
if [ -z "$(psql_su -c "SELECT 1 FROM pg_database WHERE datname = '${POSTGRES_DB}'")" ]; then
  psql_su -c "CREATE DATABASE \"${POSTGRES_DB}\" OWNER \"${POSTGRES_USER}\""
fi
log "database ${POSTGRES_DB} ready"

log "waiting for MinIO"
for i in $(seq 1 120); do curl -fs http://127.0.0.1:9000/minio/health/live >/dev/null && break; sleep 1; done
export MC_CONFIG_DIR=/tmp/.mc
mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc mb --ignore-existing "local/${MINIO_BUCKET}" >/dev/null
log "bucket ${MINIO_BUCKET} ready"

log "flyway migrate"
FLYWAY_PASSWORD="$POSTGRES_PASSWORD" /opt/flyway/flyway \
  -url="jdbc:postgresql://127.0.0.1:5432/${POSTGRES_DB}" -user="$POSTGRES_USER" \
  -locations=filesystem:/opt/flyway/sql -connectRetries=30 migrate

log "starting app"
supervisorctl -c /etc/supervisor/supervisord.conf start app
