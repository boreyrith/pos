#!/bin/sh
# Runs inside the "backup" container: a backup (dump.sh) on a schedule, and removes old ones.
set -eu
mkdir -p /backups
while true; do
  /bin/sh /dump.sh || true
  find /backups -name 'pos_next-*.sql.gz' -mtime "+${BACKUP_KEEP_DAYS}" -delete
  # An APK dump is kept as long as the dumps that may name it (dump.sh refreshes the current one).
  find /backups -name 'mobile-app-*.sql.gz' -mtime "+${BACKUP_KEEP_DAYS}" -delete
  sleep $((BACKUP_EVERY_HOURS * 3600))
done
