#!/usr/bin/env bash
# Resets the local development database to a clean state and re-seeds the examples.
# Usage: ./scripts/reset-db.sh
set -euo pipefail

DB_NAME="${DB_NAME:-flowcanvas}"
PSQL="${PSQL:-psql}"

if ! command -v "$PSQL" >/dev/null 2>&1; then
  for candidate in /usr/lib/postgresql/*/bin/psql; do
    [ -x "$candidate" ] && PSQL="$candidate" && break
  done
fi

export PGPASSWORD="${DB_PASSWORD:-postgres}"

echo "Resetting database '$DB_NAME'…"
"$PSQL" -U "${DB_USERNAME:-postgres}" -h "${DB_HOST:-localhost}" -d postgres <<SQL
DROP DATABASE IF EXISTS $DB_NAME WITH (FORCE);
CREATE DATABASE $DB_NAME;
SQL

echo "Done. Restart the backend to re-apply the Flyway migrations and seed the examples:"
echo "  ./scripts/dev-backend.sh restart"
