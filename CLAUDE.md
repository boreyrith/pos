# POS (pos-next): guide for working on this codebase

A point-of-sale system for a retail store in Cambodia: a Spring Boot API, a Next.js web app and a
Flutter cashier app, on MySQL. It handles real money, tax and stock, in English and Khmer. The
README describes what it does; this file describes how to change it safely.

## The rule: understand before you change

This is an existing, carefully built system. Most of its rules live in several places at once, and
a change that looks local often is not. Before editing anything:

1. **Read the code you will touch and its callers.** Find every place a type, endpoint, DTO field or
   table is used, across `backend/`, `frontend/` **and** `mobile/` (the Flutter app is a second API
   client with its own models and pricing code).
2. **Find the existing pattern and follow it.** There is almost always one: an earlier migration,
   service, controller, dialog or test doing the same kind of thing. Match its structure, naming,
   comment density and wording. Don't introduce a new library, layer or style when the codebase has
   one.
3. **Check the invariants below** that the change touches (money, permissions, audit, i18n, mirrors).
4. **State the plan** (what changes where, and what you checked) before large or cross-cutting work.
   Ask when a business rule is unclear; don't invent one.
5. **Verify** with the commands under Testing, for every part you touched, and say what you could not
   verify.

## Repository map

| Path | What |
|---|---|
| `backend/` | Spring Boot 4.1, Java 25, JPA + Flyway, MySQL 8.4. Package per domain under `com.pos` |
| `backend/src/main/resources/db/migration/` | Flyway migrations `V1`…`V32` (the schema's history) |
| `backend/src/test/` | Unit tests; `it/*IT.java` integration tests (Testcontainers MySQL, needs Docker) |
| `frontend/` | Next.js 16 (App Router), React 19, TanStack Query, Tailwind 4. See `frontend/AGENTS.md` |
| `frontend/src/app/(app)/` | Signed-in pages, one folder per area; `login/` is public |
| `frontend/src/components/ui.tsx` | Shared UI primitives (Button, Input, Field, Modal, Card, Badge…) |
| `frontend/src/lib/` | `api.ts` (fetch + auth), `types.ts` (API types), `pricing.ts`, `format.ts`, `i18n/` |
| `mobile/` | Flutter cashier app (iOS/Android) using the same API; see `mobile/README.md` |
| `deploy/` | Production setup (Docker Compose, nginx/Apache/Caddy configs, backups); `DEPLOY.md` |
| `scripts/demo-data/` | Demo history for development databases only |
| `docs/user-manual.html` | Staff manual, served in the app (Help, for everyone) |

Backend domains (JPA loads lazy collections in batches, `default_batch_fetch_size`): `branch`, `vendor`, `auth` (sign-in, tokens, sessions), `access` (roles, permissions), `user`, `audit`,
`settings`, `tax`, `currency`, `catalog`, `inventory`, `purchasing`, `sales` (checkout, orders,
returns, pricing), `payment` (cash, KHQR/Bakong), `hold` (held sales), `customer`, `marketing`
(promotions, coupons, segments, campaigns), `loyalty` (points, tiers), `accounting` (chart of accounts, journal, other income and
expenses), `report`, `seed` (dev data).

## Architecture and invariants

**Modules.** The backend is one application made of modules, each one or more packages under
`com.pos`, in layers from the bottom up: common, branch, vendor, audit, identity (auth, access,
user), settings (settings, tax), currency, catalog (catalog, inventory), customer, sales (sales,
payment, hold), marketing, loyalty, accounting, purchasing, report, config, seed. A module uses only
the modules below it; `ModuleBoundariesTest` lists them and fails on a dependency that goes up.
When a lower module needs something done by a higher one, it owns a small interface and the higher
module implements it, called synchronously in the same transaction: `sales.SaleBookkeeping` and
`inventory.StockBookkeeping` (accounting), `sales.SalePromotions` (marketing), `sales.CustomerRewards`
(loyalty), `inventory.DeliveryLookup` and `accounting.OpenPurchases` (purchasing),
`catalog.SalesRanking` and `customer.PurchaseHistory` (sales), and in `common` `SignedInUser` and
`StoreTimeZone`. The same holds for SQL: a module queries only its own tables and those of the
modules below (the test checks this too). Don't call a higher module directly or hide money and stock work behind
asynchronous events.

**Layers.** Controllers are thin: validation (`jakarta.validation`), `@PreAuthorize`, mapping.
Business rules and transactions live in services. Entities use Lombok; DTOs are records (often
nested in a `*Dtos` class or the service). Reporting and aggregates use `JdbcClient` SQL;
everything else JPA. `open-in-view` is off, so load what a response needs inside the transaction.

**Money.**
- Server: `BigDecimal`, rounded half-up to cents with `common.Money.round`. Never `double` for money.
- The price of a sale is worked out in **three mirrored implementations**: `sales/PricingCalculator.java`,
  `frontend/src/lib/pricing.ts` and `mobile/lib/core/pricing.dart`. All three run the same cases in
  `backend/src/test/resources/pricing-cases.json`. A pricing change must change all three and add
  cases whose expected values come from an independent calculation, not from one of the engines.
- Discount order: line promotion → the cashier's item discount → order promotion/coupon → the
  cashier's order discount → loyalty points → tax per rate. Shares are split by value; the last
  line or group takes the leftover cent so lines always add up to the order.
- The server is the authority. Clients send the total they showed (`expectedTotal`); if it differs
  the sale is refused with code `PRICES_CHANGED` and the client reloads.

**Orders are records.** An order keeps snapshots of names, prices, tax, costs, promotions and
rates as they were at the sale. Never recompute history from today's catalog or settings. Returns
refund from what each line was actually paid (`RefundSplit`, mirrored by `refundShare` in
`pricing.ts`), so a line is never refunded more than it was paid.

**Accounting.** Double-entry books in `accounting/`. Every entry goes through `Ledger.post`, which
refuses one whose debits and credits differ. Business events post automatically through `Postings`
(sales and stock) and `purchasing.PurchasePostings` (purchasing's documents), in the same transaction as the event: sales (cash or KHQR, when paid), returns, deliveries, vendor
invoices and voids, vendor payments, stock adjustments, opening stock and catalog cost changes.
Nothing posts until the books are started (Accounting > Setup); a posting dated before the start or
in a closed period lands on the first open day. Stock is valued at catalog cost, so the Inventory
account must equal the stock valuation (the overview and `AccountingIT` check it): anything that
changes stock or an item's cost needs a posting. Automatic entries are never edited or reversed by
hand; they are corrected through their document (a return, a void, an adjustment). A new automatic
posting needs a `JournalSource`, a unique `source_key`, and a case in `AccountingIT`. Two modes
(`AccountingMode`): NORMAL and FULL post the same; NORMAL only refuses manual journal entries and
reversals and hides the balance sheet and trial balance (`ReportsController.FULL_ONLY`).

**Stock** changes only through `inventory.StockService`, with a `Ref` (who, which document), so
every movement is in stock history.

**Audit.** Every change to money, stock, prices, permissions, settings, users or marketing is
logged with `AuditService.log(...)`, with before/after via `Changes` where useful. New
`AuditAction`s also need a label in `frontend/src/app/(app)/users/AuditLogTab.tsx`.

**Permissions.**
- The list is fixed in code: `access/Permission.java` (with module and "implies"). Roles pick from it.
- Every endpoint checks one with `@PreAuthorize("hasAuthority('X')")`. `PermissionTest` fails if an
  endpoint checks something that is not a `Permission`. The super admin is checked in code
  (`role().superAdmin()`), not as an authority.
- Adding one means: the enum entry, a migration granting it to the right built-in roles
  (`INSERT INTO role_permissions … WHERE code IN (…)`), the frontend `Permission` type, and Khmer for
  its label and description.
- Menus and buttons follow permissions (`useCan()`, `hasPermission`), but the server is what enforces.

**Branches.** Staff work in their branch; `inventory.BranchContext` decides scope (`requireBranch`,
`viewScope`, `sellingStorage`). Lists and reports respect it.

**Single backend instance.** Settings, permissions and ended sessions are cached in memory. Run one
API instance (or restart the others after changes).

**Errors.** Throw `common.ApiException` (`badRequest`, `conflict`, `notFound`, or with a status and a
machine-readable `code`). Responses are RFC 7807 problem details: the message is in `detail`.
Messages are plain English sentences a cashier can act on; the frontend translates them (below).

## Database

- Schema changes only through a **new** Flyway migration with the next number. Never edit a migration
  that has been applied anywhere. Hibernate runs with `ddl-auto: validate`, so entities must match.
- Start each migration with a comment saying what it adds and why. Use real constraints: foreign keys,
  `CHECK`s for enums and ranges, `UNIQUE`s, and indexes for the queries that will run.
- Store enums as strings, money as `DECIMAL(12, 2)`, times as `TIMESTAMP(6)` (UTC instants; the
  store's time zone is in settings and used for dates on screen and in reports).
- Backfill existing rows in the same migration when a new column needs a value.
- Counters and balances that two tills can change at once (promotion uses, coupon uses, loyalty
  points) are updated under `PESSIMISTIC_WRITE` locks and re-checked under the lock.
- The dev database is real data to the user: don't reset or bulk-change it without asking.

## Security

- Sign-in: short-lived JWT access token (15 min, kept in memory by the client) plus a rotating
  refresh token in an HttpOnly cookie. Each sign-in is a session (`auth/UserSession`); the access
  token carries its id, and an ended session is refused at once (`EndedSessions`). Reusing a rotated
  refresh token of a live session signs the user out everywhere.
- Never trust the client for prices, totals, permissions, branch or customer ownership: recheck on
  the server.
- Validate every request body. Keep secrets out of logs, audit entries and responses (the Bakong
  token is encrypted with `SECRETS_KEY`; only its last 4 characters are ever shown).
- CSV exports prefix spreadsheet formulas (`= + - @`) and start with a UTF-8 BOM (see
  `ReportsController.esc`). Exporting customers' contact details requires a permission and is audited.
- Client IPs come from `X-Real-IP`, which the proxy in front must overwrite with the connection's address
  (Caddyfile `header_up`, nginx `proxy_set_header`, Apache `RequestHeader set`), else the last
  `X-Forwarded-For` hop. The per-address sign-in limit relies on it.
- Web pages carry a Content-Security-Policy with a fresh nonce per request (`frontend/src/proxy.ts`),
  so pages are rendered per request. No inline scripts, `eval` or third-party origins; anything new
  the browser must load or connect to is added there. Metrics (`/actuator/prometheus`) need
  `METRICS_TOKEN` (`config.MetricsAccessFilter`).
- Unexpected exceptions return only a reference (`GlobalExceptionHandler.handleUnexpected`); the details
  are in the server log. Never put exception messages in responses.
- Sign-in is limited per account (`LoginAttempts`, lockout) and per address (`SignInThrottle`, 429
  `TOO_MANY_ATTEMPTS`); unknown usernames take as long as wrong passwords. Expired refresh tokens
  and old sessions are removed hourly (`AuthHousekeepingJob`).
- Production settings (`JWT_SECRET`, `SECRETS_KEY`, `REFRESH_COOKIE_SECURE`, the `prod` profile) are
  described in the README and `deploy/DEPLOY.md`.

## Frontend

- **Next.js 16 differs from older versions**: read `frontend/AGENTS.md` and the docs in
  `node_modules/next/dist/docs/` before using framework APIs. `next dev` rewrites `AGENTS.md`;
  that diff is expected.
- Use `components/ui.tsx` and the design tokens in `app/globals.css` (`bg-surface`, `text-muted`,
  `text-primary`…). They handle light and dark themes; avoid hard-coded colours.
- Data through TanStack Query with stable keys (`["promotions"]`, `["loyalty", …]`); invalidate the
  keys a mutation affects. API types live in `lib/types.ts` and must match the backend DTOs.
- Use the store's currency from `useStoreSettings()`. Never hard-code `"USD"`.
- **Every visible string is translated.** Use `t("…")` in components (`useT()`), `msg()` for constants
  and `translate()` in plain code. Add the Khmer to `lib/i18n/km.ts` (alphabetical). Server messages
  with names or numbers need a pattern in `lib/i18n/km-patterns.ts`. `npm run i18n:check` and the unit
  tests fail on anything missing. Khmer wording should be checked by a native speaker.

## Mobile

The Flutter app is a separate client of the same API with its own models (`lib/api/models.dart`),
pricing mirror and Khmer strings (`lib/core/km_*.dart`). Any API change (DTO fields, endpoints,
checkout rules, error codes) must be checked against it: run its tests and say what it needs.

## Testing and verification

| Area | Command (from the folder) |
|---|---|
| Backend unit + integration | `backend/`: `mvn verify` (integration tests need Docker running) |
| One integration test | `mvn verify -Dit.test=PromotionsIT -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false` |
| Frontend | `frontend/`: `npx tsc --noEmit`, `npm run lint`, `npm run i18n:check`, `npm test` |
| Mobile | `mobile/`: `flutter analyze`, `flutter test` |

- New features get an integration test (`backend/src/test/java/com/pos/it/`, following the existing
  `*IT` style: real HTTP calls as real roles, including what each role must *not* be able to do).
  Pure logic gets a unit test.
- Don't edit backend sources while `mvn verify` runs: it mixes old and new classes and resources.
- For UI changes, look at the result (light and dark, phone and desktop width) rather than assuming.
- Run the app locally: see "Run it for development" in the README (`docker compose up -d mysql`,
  `mvn spring-boot:run`, `npm run dev`). Demo accounts are in `seed/DevDataSeeder.java`.

## Known gaps

- The mobile app applies promotions and coupons (`promotionsProvider` in `lib/state/cart.dart`), but
  can't use loyalty points yet (customers still earn them on mobile sales).
- Held sales don't keep a coupon code or points to use; promotions are worked out again on resume.
- Campaigns are exported as CSV; no SMS, Telegram or email provider is connected.
- Accounting has no customer receivables (every sale is paid at the till), no fixed-asset
  depreciation schedule and no bank reconciliation; transfers between branches don't post.
