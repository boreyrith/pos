#!/bin/sh
# The five web apps on this server itself, with Node instead of Docker (for a small server; used with
# docker-compose.web-on-host.yml, see "Web apps outside Docker" in DEPLOY.md). Each app runs as a
# systemd service, pos-web-<app>, on 127.0.0.1 and the port in .env that the site config already uses
# (WEB_PORT for frontend-ctl, CAS_PORT, BUY_PORT, APP_PORT, NAV_PORT), and starts again by itself after
# a reboot or a crash.
#   ./web-host.sh build [app...]      build the apps (all, or those named) one at a time, then put each live
#   sudo ./web-host.sh install        once: the systemd services, started now and at every boot
#   ./web-host.sh status              is each app running and answering?
#   ./web-host.sh restart [app...]    restart the apps (all, or those named)
#   ./web-host.sh logs <app>          follow an app's log (Ctrl+C to stop)
# Builds go to the repository's frontend-*/.next; what runs is a copy in deploy/web/<app>, swapped in
# only when its build has finished, so a failed build leaves the running app as it was.
set -eu
cd "$(dirname "$0")"
deploy=$(pwd)
root=$(cd .. && pwd)
releases="$deploy/web"
ALL="frontend-ctl frontend-cas frontend-buy frontend-app frontend-nav"

val() { sed -n "s/^$1=//p" .env 2>/dev/null | tail -n 1; }
port_of() {
  case "$1" in
    frontend-ctl) p=$(val WEB_PORT); echo "${p:-3000}" ;;
    frontend-cas) p=$(val CAS_PORT); echo "${p:-3001}" ;;
    frontend-buy) p=$(val BUY_PORT); echo "${p:-3002}" ;;
    frontend-app) p=$(val APP_PORT); echo "${p:-3003}" ;;
    frontend-nav) p=$(val NAV_PORT); echo "${p:-3004}" ;;
    *) echo "Unknown app: $1 (one of: $ALL)" >&2; exit 1 ;;
  esac
}
apps() { if [ $# -gt 0 ]; then for a in "$@"; do port_of "$a" > /dev/null; done; echo "$@"; else echo "$ALL"; fi; }
installed() { systemctl cat "pos-web-$1" > /dev/null 2>&1; }

need_node() {
  command -v node > /dev/null || { echo "Node.js is not installed; see \"Web apps outside Docker\" in DEPLOY.md." >&2; exit 1; }
  major=$(node -p 'process.versions.node.split(".")[0]')
  [ "$major" -ge 20 ] || { echo "Node.js 20 or newer is needed (this is $(node -v))." >&2; exit 1; }
}

build() {
  need_node
  [ "$(id -u)" != 0 ] || { echo "Build as your own user, not with sudo (the files would belong to root)." >&2; exit 1; }
  cd "$root"
  # Packages: installed again only when package-lock.json changed since the last install.
  if [ ! -f node_modules/.package-lock.json ] || [ package-lock.json -nt node_modules/.package-lock.json ]; then
    echo "== Installing packages (takes a while the first time)"
    npm ci --no-audit --no-fund
  fi
  mkdir -p "$releases"
  for app in $(apps "$@"); do
    echo "== Building $app"
    mkdir -p "$app/public"
    NEXT_TELEMETRY_DISABLED=1 NODE_OPTIONS=--max-old-space-size=1024 npm run build -w "$app"
    new="$releases/$app.new"
    rm -rf "${new:?}"
    mkdir -p "$new"
    cp -a "$app/.next/standalone/." "$new/"
    cp -a "$app/.next/static" "$new/$app/.next/static"
    cp -a "$app/public" "$new/$app/public"
    rm -rf "${releases:?}/$app.old"
    if [ -d "$releases/$app" ]; then mv "$releases/$app" "$releases/$app.old"; fi
    mv "$new" "$releases/$app"
    if installed "$app"; then
      sudo systemctl restart "pos-web-$app"
      echo "== $app is live (restarted)"
    else
      echo "== $app is built; run 'sudo ./web-host.sh install' to start it"
    fi
  done
}

install() {
  [ "$(id -u)" = 0 ] || { echo "Run it with sudo: sudo ./web-host.sh install" >&2; exit 1; }
  need_node
  user=${SUDO_USER:-root}
  node=$(command -v node)
  for app in $ALL; do
    [ -f "$releases/$app/$app/server.js" ] || { echo "$app is not built yet: ./web-host.sh build $app" >&2; continue; }
    cat > "/etc/systemd/system/pos-web-$app.service" <<UNIT
[Unit]
Description=POS web app $app
After=network.target

[Service]
User=$user
WorkingDirectory=$releases/$app/$app
Environment=NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=127.0.0.1 PORT=$(port_of "$app") NODE_OPTIONS=--max-old-space-size=192
ExecStart=$node server.js
Restart=always
RestartSec=3
MemoryMax=320M

[Install]
WantedBy=multi-user.target
UNIT
    echo "Installed pos-web-$app (port $(port_of "$app"))"
  done
  systemctl daemon-reload
  for app in $ALL; do
    if installed "$app"; then systemctl enable --now "pos-web-$app" > /dev/null 2>&1 && systemctl restart "pos-web-$app"; fi
  done
  echo "Started. Check with: ./web-host.sh status"
}

status() {
  for app in $ALL; do
    port=$(port_of "$app")
    state=$(systemctl is-active "pos-web-$app" 2>/dev/null || true)
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$port/" || true)
    printf '%-14s port %-5s service %-9s answers %s\n' "$app" "$port" "${state:-missing}" "$code"
  done
  echo "(answers 200, 307 or 308 are fine: some apps redirect / to their sign-in page)"
}

case "${1:-}" in
  build) shift; build "$@" ;;
  install) install ;;
  status) status ;;
  restart) shift; for app in $(apps "$@"); do sudo systemctl restart "pos-web-$app"; done; status ;;
  logs) port_of "${2:-}" > /dev/null; journalctl -u "pos-web-$2" -n 100 -f ;;
  *) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
