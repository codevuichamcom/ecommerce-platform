#!/bin/bash
# ============================================================
#  Postgres init script — tạo nhiều DB từ env POSTGRES_MULTIPLE_DATABASES
#  Chỉ chạy 1 lần khi container init (volume rỗng).
#  Reset bằng: docker compose down -v
# ============================================================
set -e
set -u

if [ -n "${POSTGRES_MULTIPLE_DATABASES:-}" ]; then
  echo "[init] Creating multiple databases: $POSTGRES_MULTIPLE_DATABASES"
  for db in $(echo "$POSTGRES_MULTIPLE_DATABASES" | tr ',' ' '); do
    echo "[init]  -> $db"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
      SELECT 'CREATE DATABASE $db'
      WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db')\gexec
EOSQL
  done
  echo "[init] Done."
fi
