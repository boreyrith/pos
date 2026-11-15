#!/bin/sh
# Prints the nginx or Apache site config for the POS, from SITE_ADDRESS, API_ADDRESS, WEB_PORT, CAS_PORT,
# BUY_PORT, APP_PORT, NAV_PORT and API_PORT in deploy/.env. Plain HTTP; certbot then adds HTTPS and the
# redirect. On SITE_ADDRESS: /api goes to the backend, /ctl to administration (frontend-ctl), /cas to the
# till (frontend-cas), /buy to the shop (frontend-buy), /app to the mobile app page (frontend-app), the
# rest to the portal (frontend-nav). Uploads are limited to 5 MB, except the mobile app's APK (100 MB).
#   ./proxy-config.sh nginx  > /etc/nginx/sites-available/pos
#   ./proxy-config.sh apache > /etc/apache2/sites-available/pos.conf
set -eu
cd "$(dirname "$0")"
[ -f .env ] || { echo "Run ./init-env.sh first." >&2; exit 1; }
val() { sed -n "s/^$1=//p" .env | tail -n 1; }
site=$(val SITE_ADDRESS); api=$(val API_ADDRESS); web_port=$(val WEB_PORT); api_port=$(val API_PORT)
cas_port=$(val CAS_PORT); buy_port=$(val BUY_PORT); app_port=$(val APP_PORT); nav_port=$(val NAV_PORT)
web_port=${web_port:-3000}; cas_port=${cas_port:-3001}; buy_port=${buy_port:-3002}; app_port=${app_port:-3003}
nav_port=${nav_port:-3004}; api_port=${api_port:-8081}
[ -n "$site" ] || { echo "Set SITE_ADDRESS in .env." >&2; exit 1; }

case "${1:-}" in
nginx)
  cat <<CONF
# POS web apps: $site -> /api 127.0.0.1:$api_port, /ctl :$web_port, /cas :$cas_port, /buy :$buy_port,
# /app :$app_port, the rest (the portal) :$nav_port
server {
    listen 80;
    listen [::]:80;
    server_name $site;
    # Product pictures are up to 2 MB, the logo 512 KB.
    client_max_body_size 5m;
    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options SAMEORIGIN always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;
    # The camera is for scanning barcodes; nothing else the browser offers is used.
    add_header Permissions-Policy "camera=(self), microphone=(), geolocation=(), payment=(), usb=()" always;

    # The mobile app's APK, up to 100 MB: the one upload bigger than client_max_body_size above.
    location = /api/settings/mobile-app/apk {
        client_max_body_size 101m;
        proxy_pass http://127.0.0.1:$api_port;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 300s;
    }
    location /api/ {
        proxy_pass http://127.0.0.1:$api_port;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 120s;
    }
    # /cas itself too: the app redirects /cas/ to /cas, so redirecting the other way would loop.
    location ~ ^/cas(/|\$) {
        proxy_pass http://127.0.0.1:$cas_port;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 120s;
    }
    location ~ ^/ctl(/|\$) {
        proxy_pass http://127.0.0.1:$web_port;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 120s;
    }
    location ~ ^/buy(/|\$) {
        proxy_pass http://127.0.0.1:$buy_port;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 120s;
    }
    location ~ ^/app(/|\$) {
        proxy_pass http://127.0.0.1:$app_port;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 120s;
    }
    location / {
        proxy_pass http://127.0.0.1:$nav_port;
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
# POS web apps: $site -> /api :$api_port, /ctl :$web_port, /cas :$cas_port, /buy :$buy_port, /app :$app_port,
# the rest (the portal) :$nav_port. Needs: a2enmod proxy proxy_http headers
<VirtualHost *:80>
    ServerName $site
    ProxyPreserveHost On
    ProxyTimeout 120
    # Product pictures are up to 2 MB, the logo 512 KB.
    LimitRequestBody 5242880
    Header always set X-Content-Type-Options nosniff
    Header always set X-Frame-Options SAMEORIGIN
    Header always set Referrer-Policy strict-origin-when-cross-origin
    # The camera is for scanning barcodes; nothing else the browser offers is used.
    Header always set Permissions-Policy "camera=(self), microphone=(), geolocation=(), payment=(), usb=()"
    # The client's address, overwriting any X-Real-IP the client sent (sign-in limits rely on it).
    RequestHeader set X-Real-IP "%{REMOTE_ADDR}s"
    # Most specific first: Apache uses the first ProxyPass that matches. The apps' paths are matched
    # whole (/cas and /cas/..., not /cashier), as the other proxies do.
    ProxyPass /api/ http://127.0.0.1:$api_port/api/
    ProxyPassReverse /api/ http://127.0.0.1:$api_port/api/
    ProxyPassMatch "^/cas(/.*)?\$" "http://127.0.0.1:$cas_port/cas\$1"
    ProxyPassReverse /cas http://127.0.0.1:$cas_port/cas
    ProxyPassMatch "^/ctl(/.*)?\$" "http://127.0.0.1:$web_port/ctl\$1"
    ProxyPassReverse /ctl http://127.0.0.1:$web_port/ctl
    ProxyPassMatch "^/buy(/.*)?\$" "http://127.0.0.1:$buy_port/buy\$1"
    ProxyPassReverse /buy http://127.0.0.1:$buy_port/buy
    ProxyPassMatch "^/app(/.*)?\$" "http://127.0.0.1:$app_port/app\$1"
    ProxyPassReverse /app http://127.0.0.1:$app_port/app
    ProxyPass / http://127.0.0.1:$nav_port/
    ProxyPassReverse / http://127.0.0.1:$nav_port/
    # The mobile app's APK, up to 100 MB: the one upload bigger than LimitRequestBody above.
    <Location "/api/settings/mobile-app/apk">
        LimitRequestBody 105906176
    </Location>
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
    RequestHeader set X-Real-IP "%{REMOTE_ADDR}s"
    RedirectMatch 302 ^/$ /api/actuator/health
    ProxyPass /api/ http://127.0.0.1:$api_port/api/
    ProxyPassReverse /api/ http://127.0.0.1:$api_port/api/
</VirtualHost>
CONF
  ;;
*)
  echo "usage: ./proxy-config.sh nginx|apache" >&2; exit 1 ;;
esac
