#!/bin/sh
# One backup, run inside the "backup" container (by backup.sh on its schedule, and by backup-now.sh):
#   /backups/pos_next-<time>[-<suffix>].sql.gz   everything except the mobile app's install file
#   /backups/mobile-app-<sha256>.sql.gz           that file (the APK, up to 100 MB), once per version
# The APK is the same in every backup until a new one is uploaded, so it is dumped once, not into
# each daily dump. restore.sh loads the APK dump whose checksum the restored database names.
# Exits non-zero if anything failed; a dump is only kept if mysqldump got to the end.
set -eu
suffix=${1:+-$1}
db=pos_next
conn="-h mysql -uroot --max-allowed-packet=256M"
opts="$conn --single-transaction --no-tablespaces"
file="/backups/$db-$(date -u +%Y%m%d-%H%M%S)$suffix.sql.gz"
tmp=/tmp/dump-$$.sql
trap 'rm -f "$tmp" "$file.tmp" /backups/mobile-app-*.tmp' EXIT

finished() { tail -n 1 "$1" | grep -q "Dump completed"; }

# Databases from before the mobile app page (V41) have no APK table yet: then everything is dumped.
has_apk=$(mysql $conn -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$db' AND table_name = 'mobile_app_apk'")

# The database, with the APK table's definition but not its data (so a restore leaves it empty).
if [ "$has_apk" = 1 ]; then
  mysqldump $opts --routines --ignore-table=$db.mobile_app_apk $db > "$tmp" && finished "$tmp" \
    && mysqldump $opts --no-data $db mobile_app_apk >> "$tmp" && finished "$tmp" \
    || { echo "$(date -u) backup FAILED" >&2; exit 1; }
else
  mysqldump $opts --routines $db > "$tmp" && finished "$tmp" \
    || { echo "$(date -u) backup FAILED" >&2; exit 1; }
fi
gzip -c "$tmp" > "$file.tmp" && mv "$file.tmp" "$file"
echo "$(date -u) backup written: $file"

# The APK uploaded now, if it isn't kept yet. Its file's date is refreshed while it is the current one,
# so it is removed only when every dump that names it has been.
[ "$has_apk" = 1 ] || exit 0
sha=$(mysql $conn -N -B -e "SELECT apk_sha256 FROM $db.mobile_app WHERE id = 1 AND apk_sha256 IS NOT NULL")
if [ -n "$sha" ]; then
  apk="/backups/mobile-app-$sha.sql.gz"
  if [ -f "$apk" ]; then
    touch "$apk"
  else
    mysqldump $opts --no-create-info --skip-triggers --hex-blob $db mobile_app_apk > "$tmp" && finished "$tmp" \
      || { echo "$(date -u) backup of the mobile app's APK FAILED" >&2; exit 1; }
    gzip -c "$tmp" > "$apk.tmp" && mv "$apk.tmp" "$apk"
    echo "$(date -u) mobile app APK written: $apk"
  fi
fi
