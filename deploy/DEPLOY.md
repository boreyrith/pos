# Deploying the POS

One server runs everything in Docker: the database, the API, the web app, Caddy for HTTPS, and a backup job. Only ports 80 and 443 are opened; the tills, tablets and phones open the site in a browser.

```
browser ──HTTPS──▶ caddy :443 ──▶ frontend :3000 ──/api──▶ backend :8081 ──▶ mysql :3306
                                                              backup ──────▶ mysql (daily dump to deploy/backups)
```

## What you need

- A Linux server (or a shop PC) with Docker Engine 24+ and the Compose plugin. 2 CPU, 4 GB RAM and 20 GB disk is plenty for a few branches.
- An address for it:
  - **On the internet:** a domain such as `pos.myshop.com` pointing at the server, with ports 80 and 443 reachable. Caddy gets a free Let's Encrypt certificate.
  - **Shop network only:** the server's fixed IP (e.g. `10.0.129.216`) or a local name. Caddy signs its own certificate; install its authority on each device once (below).
- `git` and `openssl` on the server.

The browser needs HTTPS for the camera scanner and the secure sign-in cookie, so don't skip it.

## First install

```bash
git clone http://10.0.129.216/rith/pos-next.git
cd pos-next/deploy
./init-env.sh
docker compose -f docker-compose.prod.yml up -d --build
```

`init-env.sh` asks for the address, an email (for Let's Encrypt; leave empty on a LAN), the store name and the admin username. It writes `deploy/.env` with random database passwords, signing key and secrets key, and prints the first admin's password. **Copy that `.env` somewhere safe** (a password manager): it is needed to restore a backup on a new server.

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

## Settings in `.env`

| Variable | Meaning |
| --- | --- |
| `SITE_ADDRESS` | Address people open: domain, IP or local name. |
| `TLS` | Your email for Let's Encrypt, or `internal`. |
| `HTTP_PORT`, `HTTPS_PORT` | Published ports, default 80 and 443. |
| `DB_PASSWORD`, `DB_ROOT_PASSWORD` | Database passwords. Only used inside Docker. Don't change after the first start (the database keeps the old one). |
| `JWT_SECRET` | Signs sign-in tokens. Changing it signs everyone out. |
| `SECRETS_KEY` | Encrypts the Bakong token stored in the app. If lost, enter the token again. |
| `STORE_NAME`, `STORE_TIMEZONE` | Initial company settings; edit later in the app. |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | First admin, created only when there are no users. Ignored afterwards; you can delete the password line once you've signed in. |
| `KHQR_MODE`, `BAKONG_*` | Defaults until saved in Settings > Taxes & payments. |
| `BACKUP_EVERY_HOURS`, `BACKUP_KEEP_DAYS` | Backup schedule, default every 24 hours, kept 14 days. |

More options (session lengths, sign-in lockout, held-sale timers) are listed in `../.env.example` and shown in **Settings > System**. After editing `.env`, apply it with `docker compose -f docker-compose.prod.yml up -d`.

## Backups

The `backup` service dumps the database when it starts and then every `BACKUP_EVERY_HOURS`, into `deploy/backups/pos_next-<date>.sql.gz`, and deletes dumps older than `BACKUP_KEEP_DAYS`. Product pictures and the logo are in the database, so one file holds everything.

**Copy the backups off the server.** A disk failure takes the backups with it. For example, a nightly `rsync` or `rclone` of `deploy/backups/` to another machine or cloud storage.

Take one by hand (before an update, say):

```bash
./backup-now.sh
```

See what the job has done: `docker compose -f docker-compose.prod.yml logs backup`.

### Restoring

```bash
./restore.sh backups/pos_next-20261024-020000.sql.gz
```

It asks you to type `RESTORE`, stops the app, replaces the whole database with the backup, and starts the app again. Anything recorded after that backup is lost.

On a new server: clone the repo, put your saved `.env` in `deploy/`, `docker compose -f docker-compose.prod.yml up -d --build`, then run `restore.sh` with your latest backup.

## Updating

```bash
cd pos-next/deploy
./backup-now.sh
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

Database changes are applied automatically when the API starts (Flyway migrations); the API reports healthy once they're done. Sales stop for the minute or two the API restarts, so update outside opening hours.

### Rolling back

If an update misbehaves: `git checkout <previous commit>` and `up -d --build` again. If the update changed the database (a new `V…sql` file in `backend/src/main/resources/db/migration`), the older version won't start on the newer database: restore the backup you took before updating as well.

## Going live with KHQR

KHQR starts in test mode, where a "Simulate customer payment" button confirms payments. To take real payments:

1. Get an API token from the Bakong API portal for your account.
2. **Settings > Taxes & payments > KHQR:** set the receiving account (`name@bank`), merchant name and city, paste the token, and **Test connection**.
3. Switch the mode to **Live (Bakong)** and save.
4. Pay one small sale with a real banking app and check it completes.

Bakong tokens last 90 days; the page warns two weeks before expiry.

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

## Checklist before opening

- [ ] `https://<address>` opens without a certificate warning on every till and phone.
- [ ] Admin password changed; each staff member has their own account and role.
- [ ] Company details, VAT, currencies and receipt look right on a test sale (then refund it).
- [ ] Receipt printer and barcode scanner work at each till.
- [ ] `deploy/.env` saved somewhere safe.
- [ ] A backup file exists in `deploy/backups/` and is copied off the server.
- [ ] KHQR in live mode, if you take KHQR payments, with one real test payment done.
