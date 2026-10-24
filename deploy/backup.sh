#!/bin/sh
# Runs inside the "backup" container: dumps the database on a schedule and removes old dumps.
set -eu
mkdir -p /backups
while true; do
  file="/backups/pos_next-$(date -u +%Y%m%d-%H%M%S).sql.gz"
  # A dump is only kept if mysqldump got to the end (it writes "Dump completed" last).
  if mysqldump -h mysql -uroot --single-transaction --routines --no-tablespaces pos_next > /tmp/dump.sql \
      && tail -n 1 /tmp/dump.sql | grep -q "Dump completed"; then
    gzip -c /tmp/dump.sql > "$file.tmp" && mv "$file.tmp" "$file"
    echo "$(date -u) backup written: $file"
  else
    echo "$(date -u) backup FAILED" >&2
  fi
  rm -f /tmp/dump.sql "$file.tmp"
  find /backups -name 'pos_next-*.sql.gz' -mtime "+${BACKUP_KEEP_DAYS}" -delete
  sleep $((BACKUP_EVERY_HOURS * 3600))
done
