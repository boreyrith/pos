#!/bin/sh
# Runs inside the "backup-offsite" container: copies the dumps in /backups to BACKUP_REMOTE with
# rclone (another machine, a NAS or cloud storage), then removes copies there older than
# BACKUP_REMOTE_KEEP_DAYS. Only finished dumps are copied (the backup job writes *.tmp first).
# The remote is set up once with "rclone config"; see "Copy backups off the server" in DEPLOY.md.
set -eu
: "${BACKUP_REMOTE:?Set BACKUP_REMOTE in deploy/.env, e.g. offsite:pos-backups}"
every=${BACKUP_REMOTE_EVERY_HOURS:-1}
keep=${BACKUP_REMOTE_KEEP_DAYS:-90}
while true; do
  if rclone copy /backups "$BACKUP_REMOTE" --include 'pos_next-*.sql.gz' --immutable --log-level NOTICE; then
    echo "$(date -u) off-site copy done: $BACKUP_REMOTE"
    rclone delete "$BACKUP_REMOTE" --include 'pos_next-*.sql.gz' --min-age "${keep}d" --log-level NOTICE \
      || echo "$(date -u) off-site clean-up FAILED" >&2
  else
    echo "$(date -u) off-site copy FAILED (is the remote reachable and set up?)" >&2
  fi
  sleep $((every * 3600))
done
