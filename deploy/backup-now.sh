#!/bin/sh
# Takes a backup immediately (e.g. before an update): writes deploy/backups/pos_next-<time>-manual.sql.gz
set -eu
cd "$(dirname "$0")"
mkdir -p backups
file="backups/pos_next-$(date -u +%Y%m%d-%H%M%S)-manual.sql.gz"
docker compose -f docker-compose.prod.yml exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --no-tablespaces pos_next' > "$file.sql"
if ! tail -n 1 "$file.sql" | grep -q "Dump completed"; then
  rm -f "$file.sql"; echo "Backup FAILED (is the stack running?)" >&2; exit 1
fi
gzip -c "$file.sql" > "$file" && rm -f "$file.sql"
echo "Backup written: $file"
