#!/bin/sh
# Replaces ALL data with the sample store (Munita kids fashion and baby shop: catalog with pictures,
# customers, six weeks of sales, purchasing, stock moves, held sales), for a test or demo server.
# Takes a backup first. Every sample account gets one new random password, printed at the end,
# so the well-known development passwords never work here.
#   ./load-sample-data.sh                      # the sample in sample-data/
#   ./load-sample-data.sh other-sample.sql.gz
set -eu
cd "$(dirname "$0")"
# The compose files set up in .env (behind nginx/Apache: two files), else the standard one.
COMPOSE_FILE=$(sed -n 's/^COMPOSE_FILE=//p' .env 2>/dev/null | tail -n 1)
export COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
file=${1:-sample-data/munita-kids-store.sql.gz}
[ -f "$file" ] || { echo "Not found: $file" >&2; exit 1; }

echo "This REPLACES ALL DATA in this POS (sales, stock, customers, staff accounts, settings) with the"
printf 'sample store from %s. A backup is taken first. Type LOAD to continue: ' "$file"
read -r answer
[ "$answer" = "LOAD" ] || { echo "Cancelled."; exit 1; }

./backup-now.sh

# One password for all sample accounts, hashed the way the app stores passwords (BCrypt).
password=$(openssl rand -base64 18 | tr -d '/+=\n' | cut -c1-16)
hash=$(docker run --rm httpd:2.4-alpine htpasswd -nbB -C 10 x "$password" | cut -d: -f2 | sed 's/^\$2y\$/$2a$/')
case "$hash" in
  '$2a$10$'*) ;;
  *) echo "Could not make the password hash; nothing was changed." >&2; exit 1 ;;
esac

docker compose stop backend frontend
gunzip -c "$file" | docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot pos_next'
printf "UPDATE users SET password_hash = '%s', must_change_password = 0; DELETE FROM refresh_tokens;\n" "$hash" \
  | docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot pos_next'
docker compose start backend frontend

echo
echo "Sample store loaded. All accounts use the password: $password"
echo "  admin (super admin), manager, cashier, tech (cashier), srmanager (Siem Reap manager),"
echo "  stock (inventory), accountant. Change them under Staff if others will use this server."
echo "The API takes about a minute to start; 'docker compose ps' shows backend healthy when ready."
