#!/bin/sh
# Restores a backup into the running database, replacing what is there, with the mobile app's APK
# that backup names (backups/mobile-app-<sha256>.sql.gz, see dump.sh) if it is kept.
#   ./restore.sh backups/pos_next-20261024-020000.sql.gz
set -eu
cd "$(dirname "$0")"
# The compose files set up in .env (behind nginx/Apache: two files), else the standard one.
COMPOSE_FILE=$(sed -n 's/^COMPOSE_FILE=//p' .env 2>/dev/null | tail -n 1)
export COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
[ -f "${1:-}" ] || { echo "usage: ./restore.sh backups/<file>.sql.gz" >&2; exit 1; }
printf 'This replaces ALL data in the POS database with %s. Type RESTORE to continue: ' "$1"
read -r answer
[ "$answer" = "RESTORE" ] || { echo "Cancelled."; exit 1; }
compose="docker compose"
apps="backend frontend-ctl frontend-cas frontend-buy frontend-app frontend-nav"
sql() { $compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --max-allowed-packet=256M -N -B pos_next'; }
$compose stop $apps
gunzip -c "$1" | sql
sha=$(echo "SELECT apk_sha256 FROM mobile_app WHERE id = 1 AND apk_sha256 IS NOT NULL;" | sql 2>/dev/null || true)
if [ -n "$sha" ]; then
  if [ -f "backups/mobile-app-$sha.sql.gz" ]; then
    gunzip -c "backups/mobile-app-$sha.sql.gz" | sql
  else
    # Older backups hold the APK themselves; otherwise it has to be uploaded again.
    count=$(echo "SELECT COUNT(*) FROM mobile_app_apk;" | sql)
    [ "$count" = "1" ] || echo "Note: backups/mobile-app-$sha.sql.gz is missing; upload the APK again under Settings > Mobile app."
  fi
fi
$compose start $apps
echo "Restored from $1."
