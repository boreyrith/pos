#!/bin/sh
# Prints the nginx or Apache site config for the POS, from SITE_ADDRESS, API_ADDRESS, WEB_PORT and
# API_PORT in deploy/.env. Plain HTTP; certbot then adds HTTPS and the redirect.
#   ./proxy-config.sh nginx  > /etc/nginx/sites-available/pos
#   ./proxy-config.sh apache > /etc/apache2/sites-available/pos.conf
set -eu
cd "$(dirname "$0")"
[ -f .env ] || { echo "Run ./init-env.sh first." >&2; exit 1; }
val() { sed -n "s/^$1=//p" .env | tail -n 1; }
site=$(val SITE_ADDRESS); api=$(val API_ADDRESS); web_port=$(val WEB_PORT); api_port=$(val API_PORT)
web_port=${web_port:-3000}; api_port=${api_port:-8081}
[ -n "$site" ] || { echo "Set SITE_ADDRESS in .env." >&2; exit 1; }

case "${1:-}" in
nginx)
  cat <<CONF
# POS web app: $site -> 127.0.0.1:$web_port
server {
    listen 80;
    listen [::]:80;
    server_name $site;
    # Product pictures are up to 2 MB, the logo 512 KB.
    client_max_body_size 5m;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;

    location / {
        proxy_pass http://127.0.0.1:$web_port;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 120s;
    }
}
CONF
  [ -z "$api" ] || cat <<CONF

# POS API: $api -> 127.0.0.1:$api_port (paths start with /api)
server {
    listen 80;
    listen [::]:80;
    server_name $api;
    client_max_body_size 5m;
    add_header X-Content-Type-Options nosniff always;

    location = / {
        return 302 /api/actuator/health;
    }
    location / {
        proxy_pass http://127.0.0.1:$api_port;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 120s;
    }
}
CONF
  ;;
apache)
  cat <<CONF
# POS web app: $site -> 127.0.0.1:$web_port
# Needs: a2enmod proxy proxy_http headers
<VirtualHost *:80>
    ServerName $site
    ProxyPreserveHost On
    ProxyTimeout 120
    # Product pictures are up to 2 MB, the logo 512 KB.
    LimitRequestBody 5242880
    Header always set X-Content-Type-Options nosniff
    Header always set Referrer-Policy strict-origin-when-cross-origin
    ProxyPass / http://127.0.0.1:$web_port/
    ProxyPassReverse / http://127.0.0.1:$web_port/
</VirtualHost>
CONF
  [ -z "$api" ] || cat <<CONF

# POS API: $api -> 127.0.0.1:$api_port (paths start with /api)
<VirtualHost *:80>
    ServerName $api
    ProxyPreserveHost On
    ProxyTimeout 120
    LimitRequestBody 5242880
    Header always set X-Content-Type-Options nosniff
    RedirectMatch 302 ^/$ /api/actuator/health
    ProxyPass /api/ http://127.0.0.1:$api_port/api/
    ProxyPassReverse /api/ http://127.0.0.1:$api_port/api/
</VirtualHost>
CONF
  ;;
*)
  echo "usage: ./proxy-config.sh nginx|apache" >&2; exit 1 ;;
esac
