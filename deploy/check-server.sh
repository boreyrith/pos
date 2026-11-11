#!/bin/sh
# Checks this server before an update: Docker, memory and disk, which code it will build, and how fast
# it downloads from the places the build fetches from (Maven Central, npm, Docker Hub), both from the
# server and from inside Docker, where the build actually downloads. Changes nothing.
#   ./check-server.sh
set -u
cd "$(dirname "$0")"

ok() { printf '  [ ok ]  %s\n' "$1"; }
warn() { printf '  [SLOW]  %s\n' "$1"; slow=$((slow + 1)); }
bad() { printf '  [FAIL]  %s\n' "$1"; failed=$((failed + 1)); }
note() { printf '  [NOTE]  %s\n' "$1"; }
slow=0
failed=0

echo "Docker"
if ! docker info >/dev/null 2>&1; then
  bad "Docker isn't running, or this user can't use it (try: sudo ./check-server.sh)"
  exit 1
fi
ok "Docker $(docker version -f '{{.Server.Version}}' 2>/dev/null), Compose $(docker compose version --short 2>/dev/null), $(uname -m)"

echo "Memory and disk"
if [ -r /proc/meminfo ]; then
  avail=$(awk '/^MemAvailable:/ {print int($2 / 1024)}' /proc/meminfo)
  # The two builds (Maven, then Next.js) want about 2 GB free; less makes them crawl or fail.
  if [ "$avail" -ge 2048 ]; then ok "${avail} MB memory available"; else warn "only ${avail} MB memory available (the build wants about 2 GB)"; fi
fi
root=$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || echo /var/lib/docker)
for dir in . "$root"; do
  [ -d "$dir" ] || continue
  free=$(df -Pk "$dir" | awk 'NR == 2 {print int($4 / 1024 / 1024)}')
  # New images plus the build cache take a few GB; old images are removed with: docker image prune
  if [ "$free" -ge 5 ]; then ok "${free} GB free for $dir"; else bad "only ${free} GB free for $dir (want 5 GB; try: docker image prune)"; fi
done

echo "Code"
# Read-only (ls-remote, no fetch), so running this with sudo leaves no root-owned files in .git.
g() { git -c safe.directory='*' "$@"; }
if command -v git >/dev/null 2>&1 && g rev-parse --git-dir >/dev/null 2>&1; then
  branch=$(g rev-parse --abbrev-ref HEAD)
  here=$(g rev-parse HEAD)
  remote=$(g ls-remote origin "refs/heads/$branch" 2>/dev/null | cut -c1-40)
  main=$(g ls-remote origin refs/heads/main 2>/dev/null | cut -c1-40)
  if [ -z "$remote" ]; then
    bad "can't reach GitHub, or there is no origin/$branch"
  elif [ "$remote" = "$here" ]; then
    ok "on $branch at $(echo "$here" | cut -c1-7), the same as GitHub (nothing to pull)"
  else
    ok "on $branch at $(echo "$here" | cut -c1-7); GitHub has $(echo "$remote" | cut -c1-7) (git pull brings it)"
  fi
  if [ -n "$main" ] && [ "$branch" != main ] && [ "$remote" != "$main" ]; then
    note "$branch on GitHub differs from main ($(echo "$main" | cut -c1-7)): is $branch the branch you mean to deploy?"
  fi
  [ -z "$(g status --porcelain --untracked-files=no)" ] || note "files here were edited by hand; git pull may refuse (see: git status)"
fi

# Downloads a file and reports its speed: on the server with curl, in Docker with the web image's Node.
MAVEN=https://repo.maven.apache.org/maven2/org/apache/tomcat/embed/tomcat-embed-core/11.0.27/tomcat-embed-core-11.0.27.jar
NPM=https://registry.npmjs.org/next/-/next-16.4.5.tgz
HUB=https://registry-1.docker.io/v2/
judge() { # name, bytes, milliseconds, where
  case "$2$3" in *[!0-9]* | "") bad "$1 ($4): no answer within 60 s"; return ;; esac
  if [ "$2" -le 0 ]; then bad "$1 ($4): no answer within 60 s"; return; fi
  kbs=$(( $2 / ($3 + 1) ))
  # Bytes per millisecond is KB/s. Under 200 KB/s an update's downloads take many minutes.
  if [ "$kbs" -ge 200 ]; then ok "$1 ($4): ${kbs} KB/s"; else warn "$1 ($4): ${kbs} KB/s"; fi
}
on_host() {
  r=$(curl -s -o /dev/null --max-time 60 -w '%{size_download} %{time_total}' "$2" 2>/dev/null || echo "0 0")
  judge "$1" "${r%% *}" "$(echo "${r#* }" | awk '{print int($1 * 1000)}')" server
}
in_docker() {
  r=$(docker run --rm node:24-alpine node -e "
    const t = Date.now();
    fetch(process.argv[1], { signal: AbortSignal.timeout(60000) })
      .then((res) => res.arrayBuffer()).then((b) => console.log(b.byteLength, Date.now() - t))
      .catch(() => console.log(0, 0));" "$2" 2>/dev/null || echo "0 0")
  judge "$1" "${r%% *}" "${r#* }" "in Docker"
}

echo "Downloads (the build fetches what changed since the last one)"
on_host "Maven Central" "$MAVEN"
on_host "npm" "$NPM"
code=$(curl -s -o /dev/null --max-time 30 -w '%{http_code}' "$HUB" 2>/dev/null || echo 000)
case "$code" in 200 | 401) ok "Docker Hub (server): reachable" ;; *) bad "Docker Hub (server): no answer" ;; esac
if docker image inspect node:24-alpine >/dev/null 2>&1; then
  in_docker "Maven Central" "$MAVEN"
  in_docker "npm" "$NPM"
else
  echo "  (skipped inside Docker: the node:24-alpine image isn't here yet; the first build pulls it)"
fi

# The compose files set up in .env (behind nginx/Apache: two files, plain "docker compose"), else the standard one.
if grep -q '^COMPOSE_FILE=' .env 2>/dev/null; then dc="docker compose"; else dc="docker compose -f docker-compose.prod.yml"; fi

echo
if [ "$failed" -gt 0 ]; then
  echo "Fix the [FAIL] items above before updating."
elif [ "$slow" -gt 0 ]; then
  echo "[SLOW] only makes the build take longer, not the shop: build now, switch over at closing time."
  echo "Fast on the server but slow in Docker usually means Docker's DNS or IPv6 (DEPLOY.md, Updating)."
else
  echo "All good. Build now (the shop keeps running), then switch over at closing time:"
fi
echo "  git pull && $dc build      # any time; the old version keeps serving"
echo "  ./backup-now.sh && $dc up -d      # at closing: about a minute without sales"
[ "$failed" -eq 0 ]
