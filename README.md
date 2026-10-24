# POS (Spring Boot + Next.js)

A rebuild of the POS system: a Spring Boot API and a Next.js web app, backed by MySQL.

| Part | Stack | Folder |
|------|-------|--------|
| API | Spring Boot 4.1, Java 25, Spring Security (JWT), JPA, Flyway, MySQL 8.4 | `backend/` |
| Web | Next.js 16 (App Router), React 19, TanStack Query, Tailwind CSS 4 | `frontend/` |

## What it does (first release)

- **Settings** (one page for setting the system up): company details (trading and legal name, VAT TIN, address, phone, email, website, logo), receipt footer, time zone, KHQR receiving account; tax configuration (see Taxes); plus branches and storages, units, categories and currencies. Admins change everything; managers can view, set exchange rates and manage their own branch's storages.
- **Sign-in** with short-lived access tokens (15 min, kept in memory) and rotating refresh tokens in an HttpOnly cookie. Reusing an old refresh token signs that user out everywhere, except within 30 seconds of it being replaced (`REFRESH_REUSE_GRACE`): that is a lost response, e.g. a page reload during a refresh, so the browser simply gets a new token. Tokens revoked by signing out, a password change or an admin never get that grace. If the server is unreachable (restarting, network blip), the app retries instead of signing anyone out.
- **Roles and permissions** (Users > Roles & permissions): every screen and action is controlled by a permission, and a role is a set of them. See [Roles and permissions](#roles-and-permissions).
- **Users** ("Manage users"): add staff with a temporary password they must change at first sign-in; edit contact details, role, branch and access; reset passwords, unlock accounts, sign people out everywhere; search and filter by role, branch and status. Accounts lock for 15 minutes after 5 wrong passwords. Everyone can change their own password under My account (the avatar in the sidebar).
- **Audit log** (admins, Users > Audit log): who did what and when, with before/after values: sign-ins and failures, lockouts, user changes, company settings, taxes, currencies and exchange rates, catalog edits and price changes, stock changes, transfers, refunds and cancellations, branches, storages, units and categories. Entries are never changed or deleted by the app.
- **Every catalog entry** has a name in English and in Khmer (both required when saving; search matches either), an optional description, and an optional image (upload, preview, replace, remove; shrunk in the browser to 1024 px, PNG/JPEG/WebP up to 2 MB). Receipts and order pages show both names, as they were at the time of sale.
- **Catalog with three types:**
  - **Products:** finished goods you sell and keep in stock.
  - **Services:** billable work (repairs, installation, ...), priced per job or per hour, with an optional usual duration and a list of the parts each job uses. Selling a service takes those parts out of stock.
  - **Items:** parts, materials and accessories. Used by services, and sold on their own only if marked "sold at the till".
  - **Packages:** bundles of other catalog entries (products, items, services, even other packages), each with a quantity and unit (base unit or any pack size; jobs or hours for services). A package has its own price, unit (set, box, combo...), tax and barcode. Selling one takes the stock of everything inside it, including service parts and nested packages; refunds put it all back. The catalog shows what the contents cost separately and how many packages current stock can make. A package can't contain itself, and nesting is limited to 4 levels.
- **Units:** a managed list of units (pcs, box, carton, kg, ...). Every product and item keeps stock in a base unit and can have pack sizes (1 box = 20 pcs, 1 carton = 200 pcs). Stock is received, removed or counted in any mix of its units (2 box + 7 pcs = 47 pcs); selling is in the base unit.
- **Branches and storages:** each branch has storages (Shop floor, Back store, Warehouse, ...); one is its selling storage, which sales take stock from. Stock is kept per storage. Staff belong to a branch; admins switch branch from the sidebar.
- **Transfers:** move stock between storages in any unit. Within a branch it lands at once; to another branch it is "in transit" until that branch receives it, and can be cancelled (stock goes back) until then.
- **Stock adjustments** (Inventory > Adjustments, managers in their branch, admins anywhere): fix stock that differs from the shelf. An adjustment covers one storage and any number of items, each increased, decreased or set to a quantity (in any unit or pack size) with a reason: goods received, found, customer return, damaged, expired, lost, theft, internal use, correction or other (with a note). A **stock count** loads what the system has in a storage, takes the counted quantities and applies the differences; blank rows are left alone. Adjustments apply immediately and all-or-nothing, keep before / change / after and the cost value of each line, and are numbered (ADJ-/CNT-), listed with filters, recorded in stock history and the audit log. The Catalog's per-item Stock dialog uses the same system.
- **Stock history:** every change (sale, cancel, refund, receive, remove, count, transfer out/in/return) with storage, amount, balance after, who and when.
- **Taxes** (Settings > Taxes & payments; admins change, managers view): one store-wide **tax mode**, see [Tax configuration](#tax-configuration). Named tax rates (VAT 10%, Exempt 0%, ...); each catalog entry has one, and one is the default (its rate is the "VAT rate" and it is shown under the store's tax label). Tax is worked out per rate after the order discount; every order keeps a full snapshot of how it was taxed.
- **Sell screen:** **three carts** side by side (tabs above the sale, or Alt+1/2/3): each keeps its own items, quantities, hours, staff, discount, customer (name or phone, saved on the order and receipt) and note, so a cashier can switch between sales. Carts are kept per cashier and branch for the browser tab (switching and reloading lose nothing); units in one cart count against the stock available to the others. When carts load, prices and stock are refreshed from the catalog and the cashier is told what changed. Checkout sends the total the cashier saw; if prices moved in the meantime the server refuses the sale (`PRICES_CHANGED`), and the cart reloads today's prices to charge again. filter by type and category, search, barcode scanning with a USB/Bluetooth scanner (types the code and presses Enter) or the device camera (barcodes and QR codes; a code matches a barcode or a SKU), cart, % or $ discount, a summary that follows the tax mode (No VAT: subtotal, discount, total; VAT included: total plus "includes VAT"; VAT setup value: subtotal before VAT, taxable amount, VAT per rate, total), totals in USD with the equivalent in each active currency. Service lines record who did the work, and hourly services the hours worked.
- **Currencies:** prices, totals and reports are in the base currency (USD). Managers set exchange rates for the other currencies (KHR, THB, ...) on the Currency page, with a full history; admins add currencies and turn them on or off. Each order keeps the rates it was sold at, so receipts show the same converted totals later.
- **Payments:** cash in any mix of active currencies (e.g. $10 + 10,000 ៛), with change in the currency the cashier picks, rounded to that currency's cash step (100 ៛); and **KHQR** in USD or KHR: a dynamic KHQR code is generated per order and the screen polls until it is paid, cancelled or expires (5 minutes). Stock is reserved while waiting and returned if the payment doesn't arrive.
- **Orders:** recorded per branch; managers and cashiers see their own branch, admins any or all. List with filters, detail, 80mm receipt printing, cancel (unpaid), and **returns**: managers take back any number of units of any lines (partial) or refund everything left (full). Each return refunds what those units were actually paid, discount and tax included, puts their stock back (with service parts and package contents), is numbered (RET-) and audited. Receipts and order pages show refunds and the net paid.
- **Print labels** (Catalog > Print labels): shelf and price labels for anything for sale. Search or filter (type, category) and add entries, set copies per entry (or one per unit in stock), choose a size (thermal roll 50×30, 38×25 or 60×40 mm, one label per page; or A4 sheets of 24 or 40 labels) and what goes on it (name, Khmer name, description, price with or without VAT added, unit, the code (SKU, and the barcode number), store name, and a barcode or QR code). **Print preview** shows every page as it will print (each roll label, or each A4 sheet), numbered, with zoom, and prints from there. Barcodes are EAN-13 / EAN-8 / UPC-A when the entry's barcode is a valid one, otherwise Code 128; entries without a barcode use their SKU. The preview is true size; print with margins none and scale 100%. Layout choices are remembered on the device.
- **Catalog page:** create and edit products, services and items, categories and units, receive/remove/count stock in any unit, low-stock alerts, hide from the sell screen. An order keeps a copy of names, prices and the parts used, so a refund returns exactly those parts even after the catalog changes.
- **Sales** (sidebar > Sales) brings together **Invoices** (every order, with filters), **Returns & refunds** (every return, partial or full, with the invoice, customer, items, reason and amount and VAT refunded; filter by date, branch or search) and **Customers**. Returns are made from the invoice (Return items / Refund all).
- **Customers** (Sales > Customers): a built-in **Walk-in Customer** (code WALK-IN) is the default for every sale nobody is picked for, and can't be deactivated; existing orders were attached to it. Customers have a code (CUS-00001), name, phone (unique; matched however it's typed: "012 345 678" = "012-345-678"), email, address, VAT TIN and note. On the sell screen each cart has a customer picker (search by name, phone or code; "New customer" adds one on the spot); held carts keep their customer. Orders keep the customer and their name at the time of sale; receipts print the customer, phone and VAT TIN; the orders list searches and filters by customer; each customer's page shows their orders, total spent after refunds, refunds, first and last visit. Inactive customers can't be picked. Permissions: View customers, Manage customers (Admin, Manager and Cashier both; Accountant view). Changes are audited.
- **Purchasing** (sidebar > Purchasing): vendors (contact, VAT TIN, payment terms, what is owed, open orders); purchase orders drafted with any stocked product or item in its base unit or a pack size at the vendor's cost, then placed (draft → ordered → partly received → received, or cancelled; a partly received order can be closed short); deliveries received in one or more goes into the order's storage, adding stock in base units immediately (stock history "Purchase received", linked to the delivery GRN-number) and optionally updating each item's catalog cost to the purchase price; vendor invoices recorded against the vendor (and optionally the order) with input VAT and a due date from the vendor's terms; payments by cash, bank transfer, KHQR, cheque or other, partial or full, with unpaid / partly paid / paid / overdue tracking and totals owed. Permissions: View purchasing, Manage vendors, Purchase orders, Receive deliveries, Vendor invoices, Vendor payments (Admin all; Manager vendors, orders and receiving; Inventory Staff receiving; Accountant invoices and payments). Orders belong to a branch like everything else, and every step is in the audit log ("Purchasing").
- **Tax report** (managers and admins): for a date range and branch, VAT collected (added on top / included in prices), taxable and non-taxable sales, discounts, refunds and the VAT inside them, net VAT due, per rate and per tax mode, by day; CSV export with every sale and return.
- **Dashboard** (today / 7 / 30 / 90 days, per branch): sales after refunds, net sales without tax, gross profit and margin, orders and average order, refunds and discounts, each against the previous period; daily sales; top sellers by net sales and profit; money received by method with refunds paid out; the cash drawer by currency; low-stock items; top customers and new customers; and alerts (low stock, transfers to receive, unpaid KHQR orders, money owed to vendors). Its figures come from the same engine as Reports, so they always agree.
- **Reports** (sidebar > Reports; date range with quick ranges, branch, grouping and filters; sortable; print; CSV export that opens correctly in Excel): Sales summary by day/week/month (orders, units, gross, discounts, net sales, tax, total, refunds, net after returns, cost, gross profit, margin), Sales by item / category / hour or weekday / cashier / customer / branch, Services by staff (jobs, hours, revenue), Payments & cash drawer (by method and currency, change, refunds paid out), Discounts & price changes (every order discount, item discount and price override), Returns by item, Stock valuation (at cost and price), Stock movements (in and out by kind), Purchases by vendor (ordered, received, invoiced, input tax, paid, owed), Payables aging (not due, 1–30, 31–60, 61–90, 90+ days), plus the Tax report. Permissions: Sales reports, Inventory reports, Purchasing reports, Tax report (Admin, Manager, Accountant all; Inventory Staff inventory reports). Every sale line now keeps its **cost at the time of sale** (goods, a service's labour and parts, a package's contents), so profit stays right when costs change; older orders were estimated from catalog costs.

Not in this release (from the PRD): branch-specific pricing, customers and loyalty, employees and shifts, accounting, item-level discounts, split payments, editing paid orders, offline mode, notifications.

### Held sales

**Hold** on a cart parks the sale on the server, under a hold number (HOLD-00001). It keeps a name you give it (it defaults to the customer), a note, the customer, every line with any changed price or item discount, the order discount, who held it and when, and a total estimated at hold time. The cart is then free for the next customer.
- **Held** (the button beside the cart tabs, showing how many are waiting) lists the branch's held sales for **every till in the branch**. Each one shows its number, name, customer, items, estimated total, who held it, how long ago, and whether it is open at another till.
- **Resume** loads a held sale into a free cart with **today's prices and stock**, and says what changed since it was held. Changed prices on a line stay as they were. Items no longer for sale or out of stock are removed, and quantities are lowered to what is in stock.
- A resumed sale is **locked to that cashier**: another till can't open or pay it. Paying completes it and links it to the order. A KHQR payment keeps it locked until the payment arrives; if the payment is cancelled, the sale stays in the cart. **Hold** again saves the changes back. **Put back** returns it unchanged.
- Locks left behind (a closed browser, say) are released after `HOLD_LOCK_TIMEOUT` (30 min). Someone with **Manage held sales** (Admin, Manager) can take over a lock sooner.
- **Void** needs a reason. Cashiers can void their own holds, and Manage held sales covers anyone's.
- Held sales nobody resumes expire after `HOLD_EXPIRY` (24 h). Only Manage held sales can restore them. "Recently closed" lists paid, voided and expired ones.
- Holding, resuming, take-overs, voids and expiries are all in the audit log, with what changed since the hold.

### Languages: English and Khmer

The whole interface is available in **English** (the default for everyone and for new installations) and **Khmer** (ខ្មែរ).
- **Switching:** the language button in the side bar, the switch in *My account*, or the switch on the sign-in page.
- **Saved per person:** the choice is stored on the account (`users.language`), so it follows them to any till. Before sign-in, it's remembered on the device. A choice made on the sign-in page is saved to the account at sign-in.
- **Server text:** errors, validation messages, report names, columns and notes, permission names, stock reasons and the System tab are translated on screen. Messages that carry names and numbers ("Not enough stock of X in Y (2 left, 3 needed)") are matched by pattern, keeping the names.
- **Receipts:** printed in the language of whoever prints them, with Khmer month names. Each line shows the product's English and Khmer names as before.
- **What stays as entered:** catalog, customer, vendor, branch and category names, notes, the receipt footer, and audit summaries. These are your data, and products have their own Khmer name field. CSV exports and browser tab titles stay in English.
- **Font:** the system font is used, falling back to the operating system's Khmer font (Khmer UI, Khmer Sangam MN, Noto Sans Khmer), so it works offline. Khmer text gets extra line height.

How it works (frontend): the English text is the key. `t("Cash payment")` in components (from `useT()`), `translate()` in plain code, and `msg()` for labels kept in constants. Khmer lives in [`src/lib/i18n/km.ts`](frontend/src/lib/i18n/km.ts), and patterns for server messages in [`km-patterns.ts`](frontend/src/lib/i18n/km-patterns.ts).
- **Fallback:** missing text falls back to English, so nothing shows blank.
- **Placeholders:** `{n}`-style placeholders are filled in: `t("{n} items", { n })`.
- **Two meanings:** a word with two meanings takes a context, `t("Change||quantity")`, which shows "Change" in English but has its own Khmer.
- **Checks:** `npm run i18n:check` lists any text without Khmer, and the unit tests fail if one is missing or a placeholder doesn't match. When you add or change screen text, add its Khmer to `km.ts`.

## Run it for development

You need Docker, Java 25, Maven and Node 20+.

```bash
docker compose up -d mysql                     # MySQL on localhost:3307
cd backend && mvn spring-boot:run              # API on http://localhost:8081/api
cd frontend && npm install && npm run dev      # Web on http://localhost:3000
```

The `dev` profile (the default) fills an empty database with demo accounts and a **kids fashion and baby store**: 63 products (clothing by size, shoes, accessories, baby care, diapers, feeding, toys, gear), store supplies, 4 services (one priced per hour), 2 gift sets, 5 vendors and opening stock in the Main Shop showroom. The usernames and passwords are in [`DevDataSeeder.java`](backend/src/main/java/com/pos/seed/DevDataSeeder.java). These are for local development only.

For demos and end-to-end testing, [`scripts/demo-data`](scripts/demo-data/README.md) adds about six weeks of history: purchasing, transfers, ~250 sales, returns and held sales. It also has a reset script that clears a dev database first.

- API docs (Swagger UI): http://localhost:8081/api/docs
- The web app sends `/api/*` to the backend through a Next.js route ([`src/app/api/[...path]/route.ts`](frontend/src/app/api/[...path]/route.ts)), so the browser sees a single origin. Set `API_URL` if the backend isn't at `http://localhost:8081`.

## Run everything in Docker

```bash
cp .env.example .env    # then edit
docker compose --profile app up -d --build
```

Web on http://localhost:3000, API on http://localhost:8081/api.

### Camera scanning

The camera button on the sell screen (and next to the Barcode field when editing a product) scans barcodes and QR codes. It uses the browser's built-in BarcodeDetector where available (Chrome, Edge, Android) and ZXing elsewhere (Safari, iPhone/iPad).

Browsers only allow the camera on a secure page: `http://localhost` on the same machine works, but a tablet or phone opening `http://192.168.x.x:3000` will be refused. Serve the web app over HTTPS for other devices, e.g. behind your reverse proxy, or for testing with `npm run dev -- --experimental-https -H 0.0.0.0` (a self-signed certificate the device must trust).

## Configuration

Business settings (company details, VAT, time zone, branches, currencies, KHQR and Bakong) are edited in the app under **Settings**; units and categories are managed from the **Catalog**. **Settings > User manual** shows the staff manual to everyone signed in (cashiers see only that section). Its source is [`docs/user-manual.html`](docs/user-manual.html): edit that file, and `npm run dev` / `npm run build` regenerate the page the app serves (`frontend/public/manual/index.html`, kept in the repo for Docker builds). **Settings > System** shows how the server itself is set up (version, environment, database, session lengths, sign-in lockout, held-sale and KHQR timers, background jobs), names the environment variable behind each, and flags what needs changing before going live (development signing key, insecure cookie, missing secrets key). On the very first start the company settings are filled from the environment defaults (`STORE_NAME`, `STORE_TIMEZONE`, `BAKONG_ACCOUNT_ID`, `KHQR_MERCHANT_NAME`, `KHQR_MERCHANT_CITY`); after that the app's values win. `KHQR_MODE`, `BAKONG_API_URL` and `BAKONG_TOKEN` apply until an admin saves Settings > Taxes & payments.

Deployment settings are environment variables; see [`.env.example`](.env.example) and [`application.yml`](backend/src/main/resources/application.yml). Settings are cached in memory, so run one backend instance (or restart the others after a change).

Before going to production:

1. Set `JWT_SECRET` (`openssl rand -base64 48`), `SECRETS_KEY` (`openssl rand -base64 32`, encrypts the Bakong token saved in the app; keep it, or saved secrets must be entered again) and `REFRESH_COOKIE_SECURE=true` (needs HTTPS).
2. Run with `SPRING_PROFILES_ACTIVE=prod` (any profile other than `dev`) so no demo data is created, then create the first admin account (see below).
3. Use real database credentials (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`).
4. Turn off Swagger if you don't want it public: `SPRINGDOC_API_DOCS_ENABLED=false`, `SPRINGDOC_SWAGGER_UI_ENABLED=false`.

There is no sign-up page. Outside the `dev` profile, create the first admin by inserting a row into `users` with a BCrypt hash, then manage staff from the Staff page.

### KHQR

Set up under **Settings > Taxes & payments** (admins change, managers view):

- **Mode:** *Test (simulated)*: real-format KHQR codes, but payments are confirmed by a "Simulate customer payment" button; for development, training and demos. *Live (Bakong)*: payments are confirmed by asking the Bakong Open API (`/v1/check_transaction_by_md5`) on each poll and before a code expires. A payment only counts if Bakong reports the exact amount sent to your account. Live mode can't be saved without a token.
- **Receiving account:** Bakong account ID (`name@bank`), merchant name (25 characters) and city (15) shown in the customer's banking app, and how long a code stays valid (1-60 minutes; unpaid codes expire and release the stock).
- **Bakong API:** the API address (production `https://api-bakong.nbc.gov.kh` or sandbox `https://sit-api-bakong.nbc.gov.kh`; https only) and the **API token** from the Bakong API portal. The token is write-only: it is stored AES-GCM encrypted with `SECRETS_KEY`, only its last 4 characters are ever shown, and it never appears in the audit log. The page shows when the token expires (Bakong tokens last 90 days; a warning appears 14 days before). **Test connection** makes one lookup with the token being typed (or the saved one) and says whether Bakong accepted it; no payment is involved.
- Fields never saved in the app follow the server defaults (`KHQR_MODE`, `BAKONG_API_URL`, `BAKONG_TOKEN`, 5 minutes), so existing setups keep working. Every change is audited and applies to the next payment.

Test the generated QR codes with a real banking app in Bakong's sandbox before taking real payments.

## Roles and permissions

Each user has one role; a role is a set of permissions. The permission list is fixed in the code (`access/Permission.java`) and every API endpoint checks one, so the server enforces access whatever the screens show. Menus, pages and buttons follow the same permissions.

| Module | Permissions |
| --- | --- |
| Sales | Sell · View orders · Cancel unpaid orders · Refunds and returns · Discount items · Override prices · Sell below cost |
| Customers | View customers · Manage customers |
| Catalog | Manage catalog (entries, prices, images, categories, units) |
| Inventory | View inventory · Adjust stock · Count stock · Transfer stock |
| Purchasing | View purchasing · Manage vendors · Purchase orders · Receive deliveries · Vendor invoices · Vendor payments |
| Reports | Sales dashboard · Tax report · Sales reports · Inventory reports · Purchasing reports |
| Settings | View settings · Company · Tax · KHQR and Bakong · Currencies · Exchange rates · Branches · Storages |
| Users and security | View users · Manage users · Manage roles · Audit log |
| Branch access | All branches (switch branch, see every branch); without it, people work in their own branch only |

Built-in roles (permissions can be adjusted, except Super Admin; built-in roles can't be renamed or deleted):

| Role | Can |
| --- | --- |
| **Super Admin** | Everything, including permissions added later. Can't be edited. |
| **Admin** | Everything except managing roles: daily operations, users and business settings, all branches. |
| **Manager** | Sell, orders, refunds, catalog, stock (adjust, count, transfer), dashboard and tax report, exchange rates, storages, view settings and their branch's staff. |
| **Cashier** | Sell, view orders, cancel unpaid orders. |
| **Inventory Staff** | View inventory, adjust, count and transfer stock. |
| **Accountant** | Orders, dashboard, tax report and settings, read-only, across all branches. |

**Item discounts:** "Discount items" lets a role take a % or an amount off a single cart line (the tag button under the item). Each role can have a maximum item discount % (a % discount is held to the % entered, an amount to the % of the line it takes off); a role manager can't allow more than their own limit. Built-in: Admin no limit, Manager up to 20%, Cashier none. Item discounts come off first, then any order discount (worked out on what is left), then VAT, included or added; the Java calculator and `lib/pricing.ts` are checked against the same shared cases. Receipts and order pages show each line's discount and an "Item discounts" total, the tax report counts them in discounts given, returns refund what the discounted line was paid, and each sale with item discounts is in the audit log ("Item discounted").

**Price overrides:** by default every sale uses the catalog price. "Override prices" lets a role change a line's price on the sell screen (tap the price), always with a reason; "Sell below cost" also allows a price under the item's cost. Built-in: Admin has both, Manager can override but not below cost, Cashier neither. The server enforces both rules and the reason on every checkout. Each order line keeps the catalog price, the price charged, the reason and who changed it; VAT (included or added) and order discounts work on the new price; receipts show "Price changed (was ...)", the order page shows who and why, and each sale with changed prices is recorded in the audit log ("Price overridden").

**Custom roles:** "New role" (or "Copy as new role") under Users > Roles & permissions, pick permissions by module. Permissions that another needs are added automatically (refunds need View orders). A role in use can't be deleted.

**Safety rules** (enforced by the server, `access/AccessRules.java`):
- Nobody can grant a permission they don't have, assign a role with more than they have, or manage an account whose role has more than they have.
- Only a Super Admin can assign the Super Admin role or change a Super Admin's account; there is always at least one active Super Admin.
- Nobody can change their own role or disable their own account, or edit their own role's permissions.
- Without "All branches", people see and manage only their own branch's staff.
- Role, permission and account changes take effect on the person's next request (the sign-in token only identifies them; their role is looked up per request, cached, and refreshed on change). Disabling an account cuts it off immediately.
- Creating, changing and deleting roles is recorded in the audit log, with permissions added and removed.

**Upgrading:** migration `V14__roles_permissions.sql` creates the built-in roles and moves users over: existing **admins become Super Admins**, managers and cashiers keep their roles.

## Tax configuration

Settings > Taxes & payments holds one store-wide configuration. Only admins can change it; every change is audited.

| Mode | Listed price | Tax | Customer pays |
| --- | --- | --- | --- |
| **No VAT** | as listed | none | the listed price |
| **VAT included** | includes VAT | `price × rate ÷ (100 + rate)`, worked out of the price | the listed price |
| **VAT setup value** | excludes VAT | `taxable subtotal × rate ÷ 100`, added on top | listed price + VAT |

Example at 10%: a $110.00 item under *VAT included* holds $10.00 VAT; a $100.00 item under *VAT setup value* costs $110.00.

- **Fields:** mode, VAT rate (the default tax rate's rate), display name (e.g. "VAT", used on screens, receipts and reports), maximum rate (no rate can be set above it; 30% by default), VAT TIN / registration number, and which types are taxed (products, services, items, packages).
- **Validation:** rates are numbers, 0 or more and at most the maximum rate (and below 100%). The server rejects anything else.
- **Not taxed:** lines are charged no tax under *No VAT*, when their type is outside the scope ("Not taxed"), or when their rate is 0% (e.g. "Exempt"). They count as non-taxable sales.
- **Packages** are taxed once, on the package price at the package's own rate; what is inside is never taxed again.
- **Discounts** (order level, % or amount) are shared over the tax groups by value before tax, then over each group's lines; the last line takes any leftover cent, so line amounts always add up to the order.
- **Rounding:** half-up to the cent, per tax group.
- **One source of truth:** the server (`tax/TaxService`, `sales/PricingCalculator`) decides every amount. The sell screen mirrors it (`lib/pricing.ts`) for the live total, and both are tested against the same cases (`backend/src/test/resources/pricing-cases.json`), so the cart shows what the server charges.
- **Snapshots:** each order stores its tax mode and label, each line's rate, whether its price included tax, and its discount, net, tax and paid amounts; each return stores the amount and tax refunded per line. Changing the mode or rates only affects new sales: past receipts, refunds and reports never change.
- **Refunds:** returning *q* of a line's *n* units refunds `round(paid × q ÷ n)` with the same share of its tax (counted cumulatively, so 1 + 1 + 1 refunds the same as 3); the last units refund exactly what is left. An order can never be refunded more than was paid, or tax refunded that was not charged.
- **Switching modes** takes effect for the next sale. A sale reads the settings once, so all its lines use the same mode even while an admin is changing it.

**Upgrading:** migration `V12__tax_modes_returns.sql` turns the old "prices include tax" setting into the matching mode (on → VAT included, off → VAT setup value) and removes the per-entry override (the store mode now applies to every entry; check entries that used to override it). Existing orders get their per-line split from their saved tax breakdown, without changing any total, and orders refunded earlier get a matching full return dated when they were sold. Back up the database before upgrading, as with any migration.

## Tests

```bash
cd backend && mvn test        # unit tests: pricing, tax rules, refunds, stock movement, cash settlement, KHQR payload
cd backend && mvn verify      # + integration tests against MySQL in Docker (Testcontainers): tax modes,
                              #   mode switching, historical orders, validation, partial/full returns,
                              #   packages, concurrent checkouts, tax report and CSV
cd frontend && npm test       # pricing.ts against the same shared cases as the backend
cd frontend && npm run lint && npx tsc --noEmit
```

## Layout

```
backend/src/main/java/com/pos/
  auth/      login, JWT, refresh tokens        catalog/  products, services, items, categories, units, stock
  sales/     checkout, orders, returns, pricing        payment/  cash, KHQR, Bakong client, expiry job
  inventory/ branches, storages, stock levels, transfers, stock history
  currency/  currencies, exchange rates, multi-currency cash settlement
  tax/       tax mode and settings, TaxService (how each entry is taxed), tax rates
  audit/     audit log (who did what, before/after values)
  report/    dashboard, tax report + CSV       user/     staff accounts, staff list
  config/    security, app properties          seed/     demo data (dev profile)
  settings/  company information and system-wide settings, logo
frontend/src/
  app/(app)/ pos, orders, catalog, inventory, dashboard, reports/tax, settings, users pages
  app/api/   proxy to the backend
  lib/       API client, auth, pricing and tax (mirrors PricingCalculator.java)
  components/ UI kit, app shell, receipt, payment dialogs, settings panels
```
