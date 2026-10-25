#!/bin/sh
# Restores a backup into the running database, replacing what is there.
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
$compose stop backend frontend
gunzip -c "$1" | $compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot pos_next'
$compose start backend frontend
echo "Restored from $1."
