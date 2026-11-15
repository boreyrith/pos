#!/bin/sh
# Takes a backup immediately (e.g. before an update): writes deploy/backups/pos_next-<time>-manual.sql.gz
# (and the mobile app's APK, once per version; see dump.sh). Needs the database running.
set -eu
cd "$(dirname "$0")"
# The compose files set up in .env (behind nginx/Apache: two files), else the standard one.
COMPOSE_FILE=$(sed -n 's/^COMPOSE_FILE=//p' .env 2>/dev/null | tail -n 1)
export COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
mkdir -p backups
docker compose run --rm --no-deps -T --entrypoint /bin/sh backup /dump.sh manual \
  || { echo "Backup FAILED (is the stack running?)" >&2; exit 1; }
