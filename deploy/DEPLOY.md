# Deploying the POS

One server runs everything in Docker: the database, the API, the web app, Caddy for HTTPS, and a backup job. Only ports 80 and 443 are opened; the tills, tablets and phones open the site in a browser.

```
browser ──HTTPS──▶ caddy :443 ─ SITE_ADDRESS ─▶ frontend :3000 ──/api──▶ backend :8081 ──▶ mysql :3306
other programs ──▶ caddy :443 ─ API_ADDRESS ──────────────────────────▶ backend :8081
                                                               backup ──────▶ mysql (daily dump to deploy/backups)
```

The web app always reaches the API through its own `/api`, so sign-in stays on one site. `API_ADDRESS` is optional: it publishes the same API on its own address for integrations or a mobile app (paths start with `/api`, e.g. `https://api.pos.myshop.com/api/actuator/health`).

## What you need

- A Linux server (or a shop PC) with Docker Engine 24+ and the Compose plugin. 2 CPU, 4 GB RAM and 20 GB disk is plenty for a few branches.
  Install Docker with `curl -fsSL https://get.docker.com | sh`, **not** the Ubuntu snap (`snap install docker`): the snap can't read folders outside your home directory, and fails with `open /var/lib/snapd/void/docker-compose.prod.yml: no such file or directory`. If you have it, `snap remove --purge docker` first; `which docker` should then show `/usr/bin/docker`.
- An address for it:
  - **On the internet:** a domain such as `pos.myshop.com` pointing at the server, with ports 80 and 443 reachable. Caddy gets a free Let's Encrypt certificate. For a separate API address, point that name (e.g. `api.pos.myshop.com`) at the same server too.
  - **Shop network only:** the server's fixed IP (e.g. `10.0.129.216`) or a local name. Caddy signs its own certificate; install its authority on each device once (below).
- `git` and `openssl` on the server.

The browser needs HTTPS for the camera scanner and the secure sign-in cookie, so don't skip it.

## First install

```bash
git clone https://github.com/boreyrith/pos.git
cd pos/deploy
./init-env.sh
docker compose -f docker-compose.prod.yml up -d --build
```

If the repository is private, the server needs read access: a [deploy key](https://docs.github.com/en/authentication/connecting-to-github-with-ssh/managing-deploy-keys) (clone with `git@github.com:boreyrith/pos.git`) or a fine-grained token with read-only access to this repository.

`init-env.sh` asks for the address, an optional separate API address, an email (for Let's Encrypt; leave empty on a LAN), the store name and the admin username. It writes `deploy/.env` with random database passwords, signing key and secrets key, and prints the first admin's password. **Copy that `.env` somewhere safe** (a password manager): it is needed to restore a backup on a new server.

The first build takes 5-10 minutes. Check that everything is up:

```bash
docker compose -f docker-compose.prod.yml ps
```

All services should show `healthy` or `running`. Then open `https://<your address>`, sign in with the admin username and the printed password, and choose a new password.

Next, in the app:

1. **Settings > General:** company name, address, VAT number, receipt footer, logo.
2. **Settings > Taxes & payments:** VAT mode and rates, currencies and exchange rate, KHQR.
3. **Settings > Branches:** your branches and their stock locations.
4. **Staff:** a user for each person, with the right role. Don't share the admin account.
5. **Catalog:** categories, units and products (or import them), then receive opening stock.

The database starts empty: no demo products or demo users are created outside development.

### Trusting the certificate on a LAN

With `TLS=internal`, browsers warn until they trust Caddy's own authority. Export it once:

```bash
docker compose -f docker-compose.prod.yml cp caddy:/data/caddy/pki/authorities/local/root.crt ./pos-root.crt
```

Install `pos-root.crt` as a trusted root on each device: Windows, double-click > Install > Local Machine > Trusted Root Certification Authorities; macOS, Keychain Access > System > Always Trust; iPhone/iPad, AirDrop or email it, install the profile, then Settings > General > About > Certificate Trust Settings; Android, Settings > Security > Install certificate > CA.

## Behind an existing nginx or Apache

If the server already runs nginx or Apache on ports 80/443 (other sites), the POS runs without Caddy: the web app and API listen on this machine only (`127.0.0.1:3000` and `127.0.0.1:8081`), and your web server proxies the domains to them and handles HTTPS with certbot.

`init-env.sh` asks about this and sets it up. For an existing `.env`, add these lines to it:

```
COMPOSE_FILE=docker-compose.prod.yml:docker-compose.behind-proxy.yml
WEB_PORT=3000
API_PORT=8081
```

`COMPOSE_FILE` makes plain `docker compose ...` use both files, so in this mode **leave out `-f docker-compose.prod.yml`** from the commands in this guide. If 3000 or 8081 is already taken on the server (`ss -ltnp | grep -E ':3000|:8081'`), pick free ports.

### Example: munita.virtual-assistant.space

Web app on `https://munita.virtual-assistant.space`, API on `https://api-munita.virtual-assistant.space`.

1. **DNS:** an A record for `munita` and one for `api-munita` under `virtual-assistant.space`, both pointing at the server's public IP. On Cloudflare, "DNS only" (grey cloud) until the certificates are issued.
2. **`deploy/.env`:**
   ```
   SITE_ADDRESS=munita.virtual-assistant.space
   API_ADDRESS=api-munita.virtual-assistant.space
   COMPOSE_FILE=docker-compose.prod.yml:docker-compose.behind-proxy.yml
   WEB_PORT=3000
   API_PORT=8081
   ```
3. **Start the POS:**
   ```bash
   docker compose up -d --build
   docker compose ps          # backend and frontend "healthy"; there is no caddy
   curl -s http://127.0.0.1:8081/api/actuator/health
   ```
4. **Web server site**, generated from `.env`:
   - nginx:
     ```bash
     ./proxy-config.sh nginx > /etc/nginx/sites-available/pos
     ln -s /etc/nginx/sites-available/pos /etc/nginx/sites-enabled/pos
     nginx -t && systemctl reload nginx
     ```
   - Apache:
     ```bash
     a2enmod proxy proxy_http headers
     ./proxy-config.sh apache > /etc/apache2/sites-available/pos.conf
     a2ensite pos && apache2ctl configtest && systemctl reload apache2
     ```
5. **HTTPS** with certbot (it adds the certificates and the HTTP-to-HTTPS redirect to that site):
   ```bash
   certbot --nginx -d munita.virtual-assistant.space -d api-munita.virtual-assistant.space
   ```
   (`--apache` for Apache. Install certbot with `apt install certbot python3-certbot-nginx`, or `python3-certbot-apache`.)
6. **Check:** `https://munita.virtual-assistant.space` shows the sign-in page; `https://api-munita.virtual-assistant.space` answers `{"status":"UP"...}`.

The web app's sign-in uses a secure cookie, so it works only over HTTPS: sign in after step 5.

## Settings in `.env`

| Variable | Meaning |
| --- | --- |
| `SITE_ADDRESS` | Address people open: domain, IP or local name. |
| `API_ADDRESS` | Optional separate address for the API, e.g. `api.pos.myshop.com`. Empty: the API is only reached through the web app. |
| `TLS` | Your email for Let's Encrypt, or `internal`. |
| `HTTP_PORT`, `HTTPS_PORT` | Ports Caddy publishes, default 80 and 443 (not used behind nginx/Apache). |
| `COMPOSE_FILE`, `WEB_PORT`, `API_PORT` | Only behind your own nginx/Apache; see that section. |
| `DB_PASSWORD`, `DB_ROOT_PASSWORD` | Database passwords. Only used inside Docker. Don't change after the first start (the database keeps the old one). |
| `JWT_SECRET` | Signs sign-in tokens. Changing it signs everyone out. |
| `SECRETS_KEY` | Encrypts the Bakong token stored in the app. If lost, enter the token again. |
| `STORE_NAME`, `STORE_TIMEZONE` | Initial company settings; edit later in the app. |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | First admin, created only when there are no users. Ignored afterwards; you can delete the password line once you've signed in. |
| `KHQR_MODE`, `BAKONG_*` | Defaults until saved in Settings > Taxes & payments. |
| `BACKUP_EVERY_HOURS`, `BACKUP_KEEP_DAYS` | Backup schedule, default every 24 hours, kept 14 days. |
| `METRICS_TOKEN`, `LOGGING_STRUCTURED_FORMAT_CONSOLE` | Prometheus metrics and JSON logs (see Monitoring). Metrics are off without a token. |
| `COMPOSE_PROFILES=offsite`, `BACKUP_REMOTE`, `BACKUP_REMOTE_KEEP_DAYS` | Copy backups off the server (see Backups); copies kept 90 days. |

More options (session lengths, sign-in lockout, held-sale timers) are listed in `../.env.example` and shown in **Settings > System**. After editing `.env`, apply it with `docker compose -f docker-compose.prod.yml up -d`.

## Sample data for testing

For a test or demo server, `load-sample-data.sh` fills the POS with a ready-made store: **Munita Shop**, a kids fashion and baby shop with
- 74 catalog entries with pictures (sizes, packs, services, gift sets), barcodes and Khmer names;
- two branches (Main Shop in Phnom Penh, Siem Reap), 12 customers, 5 vendors;
- six weeks of history: about 260 sales (cash in USD and riel, KHQR, discounts, returns, refunds), purchase orders and deliveries, vendor invoices and payments, transfers, a stock count, and held sales;
- KHQR in test mode.

```bash
./load-sample-data.sh
```

It asks you to type `LOAD`, takes a backup, then **replaces all data**, including staff accounts and settings. All sample accounts (`admin`, `manager`, `cashier`, `tech`, `srmanager`, `stock`, `accountant`) get one new random password, printed at the end; the development passwords never work. The sample's dates end on the day it was made (see `git log deploy/sample-data`).

To go live afterwards, start from an empty database: restore a backup taken before loading, or remove the data with `docker compose down -v` (deletes everything) and start again, which creates the first admin from `.env` once more.

## Backups

The `backup` service dumps the database when it starts and then every `BACKUP_EVERY_HOURS`, into `deploy/backups/pos_next-<date>.sql.gz`, and deletes dumps older than `BACKUP_KEEP_DAYS`. Product pictures and the logo are in the database, so one file holds everything.

**Copy the backups off the server** (below): a disk failure takes the backups with it.

Take one by hand (before an update, say):

```bash
./backup-now.sh
```

See what the job has done: `docker compose -f docker-compose.prod.yml logs backup`.

### Copy backups off the server

The optional `backup-offsite` service copies every finished dump in `deploy/backups/` to another
place with [rclone](https://rclone.org): a NAS or another machine (SFTP, SMB), or cloud storage
(Google Drive, S3, Backblaze B2, Cloudflare R2 and others). It checks every hour and removes copies
older than 90 days there. The dumps contain customers' names and phone numbers: use storage only
the owner can open, and ideally an rclone `crypt` remote, which encrypts the files before they leave.

1. Set up the remote once (answers are saved in `deploy/rclone/rclone.conf`, which is not in git;
   keep a copy with `.env`):

   ```bash
   mkdir -p rclone
   docker run --rm -it -v "$PWD/rclone:/config/rclone" rclone/rclone:1.75.2 config
   ```

   Name it, for example, `offsite`. For encryption, add a second remote of type `crypt` that wraps
   the first (e.g. `offsite-crypt` over `offsite:pos-backups`) and use that name below. **Save the
   crypt passwords**: without them the copies can't be read.

2. Add to `deploy/.env` and start it:

   ```bash
   COMPOSE_PROFILES=offsite
   BACKUP_REMOTE=offsite:pos-backups      # or offsite-crypt:
   ```

   ```bash
   docker compose -f docker-compose.prod.yml up -d
   docker compose -f docker-compose.prod.yml logs backup-offsite   # "off-site copy done" each hour
   ```

3. Check now and then that the files are there (`docker run --rm -v "$PWD/rclone:/config/rclone"
   rclone/rclone:1.75.2 ls offsite:pos-backups`), and once restore one on a spare machine.

### Restoring

```bash
./restore.sh backups/pos_next-20261024-020000.sql.gz
```

It asks you to type `RESTORE`, stops the app, replaces the whole database with the backup, and starts the app again. Anything recorded after that backup is lost.

On a new server: clone the repo, put your saved `.env` in `deploy/`, `docker compose -f docker-compose.prod.yml up -d --build`, then run `restore.sh` with your latest backup.

## Updating

```bash
cd pos/deploy
./backup-now.sh
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

With sample data loaded, updating keeps it (the data is only replaced when you run `load-sample-data.sh` again, e.g. to refresh the dates).

Database changes are applied automatically when the API starts (Flyway migrations); the API reports healthy once they're done. Sales stop for the minute or two the API restarts, so update outside opening hours.

If the server sits behind your own nginx or Apache (see above), regenerate its config after an update
that changes `proxy-config.sh`, and reload the web server: `./proxy-config.sh nginx` (or `apache`).
Configs made before November 2026 let a client choose the address the sign-in limit counts against
(Apache) and lack `X-Frame-Options`; configs made before 10 November 2026 lack `Permissions-Policy`.

### Rolling back

If an update misbehaves: `git checkout <previous commit>` and `up -d --build` again. If the update changed the database (a new `V…sql` file in `backend/src/main/resources/db/migration`), the older version won't start on the newer database: restore the backup you took before updating as well.

## Going live with KHQR

KHQR starts in test mode, where a "Simulate customer payment" button confirms payments. To take real payments:

1. Get an API token from the Bakong API portal for your account.
2. **Settings > Taxes & payments > KHQR:** set the receiving account (`name@bank`), merchant name and city, paste the token, and **Test connection**.
3. Switch the mode to **Live (Bakong)** and save.
4. Pay one small sale with a real banking app and check it completes.

Bakong tokens last 90 days; the page warns two weeks before expiry.

## If the build fails

- **`ECONNRESET`, `ETIMEDOUT`, `network aborted` or a Maven download error:** the server's internet connection dropped while downloading packages. Run the same `up -d --build` again; downloads already finished are kept, so each attempt gets further.
- **`open /var/lib/snapd/void/...: no such file or directory`:** Docker is the snap version; see *What you need*.
- **Browser shows a certificate error, or `logs caddy` shows `challenge failed` / `NXDOMAIN`:** the name doesn't point at this server yet, port 80 is blocked, or `TLS` is still `internal`. Fix it and run `docker compose -f docker-compose.prod.yml restart caddy`; Caddy retries by itself.
- **`bind: address already in use` on port 80 or 443:** another web server (nginx, Apache) already uses them; see *Behind an existing nginx or Apache*.
- **`.env already exists`:** setup was already done in this folder; the existing `.env` is kept.

Keep one copy of the repository on the server (e.g. `/opt/pos`) and always run the commands from its `deploy` folder: each copy has its own `.env`, and so its own passwords.

## Day to day

| Task | Command (in `deploy/`) |
| --- | --- |
| Status | `docker compose -f docker-compose.prod.yml ps` |
| Logs | `docker compose -f docker-compose.prod.yml logs -f backend` (or `frontend`, `caddy`, `backup`) |
| Restart | `docker compose -f docker-compose.prod.yml restart` |
| Stop / start | `docker compose -f docker-compose.prod.yml down` / `up -d` (data is kept in Docker volumes) |
| Disk use | `docker system df`; `docker image prune` removes old images after updates |

Containers restart by themselves after a crash or a server reboot (make sure Docker starts at boot: `sudo systemctl enable docker`).

Never run `docker compose down -v`: `-v` deletes the database volume.

## Monitoring

**Health**: `https://<API_ADDRESS>/api/actuator/health` (or `docker compose -f docker-compose.prod.yml ps`) says
`UP` while the API, its database connection and disk space are fine. Point an uptime checker
(UptimeRobot, Uptime Kuma, Healthchecks…) at it to be told when the shop's server goes down.

**Metrics for Prometheus** (requests, errors and response times per endpoint, the database pool, memory,
disk space): set a token in `.env` and apply it with `docker compose -f docker-compose.prod.yml up -d`:

```bash
METRICS_TOKEN=<output of: openssl rand -hex 32>
```

The metrics are then at `/api/actuator/prometheus` for requests carrying `Authorization: Bearer <token>`;
without the token (or with a staff sign-in) the answer is 401, and with no `METRICS_TOKEN` set they're
off (404). A Prometheus scrape job (from inside Docker use `backend:8081`; from another machine, the
`API_ADDRESS` over HTTPS):

```yaml
scrape_configs:
  - job_name: pos-api
    metrics_path: /api/actuator/prometheus
    scheme: https
    authorization:
      credentials: <METRICS_TOKEN>
    static_configs:
      - targets: ["api.pos.example.com"]
```

Alerts worth having:

| Alert | PromQL |
|---|---|
| API down | `up{job="pos-api"} == 0` for 2m |
| Server errors | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) > 0.1` |
| Slow requests (95th percentile over 2 s) | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m]))) > 2` |
| Database pool exhausted | `hikaricp_connections_pending > 0` for 1m |
| Disk filling up (under 10% free) | `disk_free_bytes / disk_total_bytes < 0.1` |

**JSON logs** for a log collector (Loki, Elasticsearch, CloudWatch…): add
`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (or `logstash`) to `.env`. Without it the logs stay plain
text, easier to read with `docker compose logs`. Container logs are capped at 5 × 10 MB per service.

## Checklist before opening

- [ ] `https://<address>` opens without a certificate warning on every till and phone.
- [ ] Admin password changed; each staff member has their own account and role.
- [ ] Company details, VAT, currencies and receipt look right on a test sale (then refund it).
- [ ] Receipt printer and barcode scanner work at each till.
- [ ] `deploy/.env` saved somewhere safe.
- [ ] A backup file exists in `deploy/backups/` and is copied off the server (`backup-offsite`).
- [ ] KHQR in live mode, if you take KHQR payments, with one real test payment done.
