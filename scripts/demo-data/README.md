# Demo data: kids fashion and baby store

Tools for **development and test databases only**. They fill the POS with a realistic store:

- **Catalog:** loaded by the dev seeder ([`DevDataSeeder.java`](../../backend/src/main/java/com/pos/seed/DevDataSeeder.java)) on an empty database:
  - 63 products: each garment size has its own SKU and EAN-13 barcode.
  - Packs, for example a carton of diapers.
  - Khmer names and low-stock levels.
  - Store supplies, used by services or sold.
  - 4 services, one priced per hour.
  - 2 gift sets.
  - 5 vendors.
  - Opening stock.
- **History:** [`seed-kids-store.py`](seed-kids-store.py) adds about six weeks of activity through the REST API:

| What | Details |
| --- | --- |
| Customers | 12, including a kindergarten and a daycare with VAT TINs, in Phnom Penh and Siem Reap |
| Purchasing | 9 purchase orders: received, part-received (back order), ordered, draft and cancelled. Deliveries by the carton. 6 vendor invoices: paid, part-paid (overdue), unpaid, cash on delivery |
| Stock | 8 transfers: back store to showroom, to Siem Reap (one still in transit). Damaged write-offs, found and internal use, a stock count with differences |
| Sales | ~250 orders over 30 days at both branches, busier at weekends and in the evening. Cash in USD and riel with change, KHQR in USD and KHR, order and item discounts, a marked-down display pair, gift wrapping, embroidery, alterations, photo sessions |
| After-sales | Returns and size exchanges, full refunds, KHQR orders cancelled |
| Held sales | Two waiting now, one resumed and paid, one voided, one left to expire |

Because everything goes through the API, the application itself works out every total, the tax, cost of goods, stock movement and running balance, document number and audit entry. Each step is then moved back to the date it belongs to. Steps run in date order, so the numbering and stock balances follow the dates.

## Rebuild a dev database from scratch

```bash
# 1. Back up (always)
docker compose exec -T mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction pos_next' > backup.sql
# 2. Stop the API, then clear the data (keeps users, roles, settings, taxes, currencies, units, branches, storages)
docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" pos_next' < scripts/demo-data/reset-dev-data.sql
# 3. Start the API (dev profile): the seeder loads the catalog
cd backend && mvn spring-boot:run
# 4. Add the history (about 30 seconds)
python3 scripts/demo-data/seed-kids-store.py
# 5. Product pictures for everything sellable
node scripts/demo-data/product-images.mjs
```

[`product-images.mjs`](product-images.mjs) draws a flat illustration for each sellable entry:
- coloured by kind of product;
- a size badge such as 0-3M, 4T or EU 26, a pack size, or a SERVICE tag.

It renders them to WebP with `sharp` from the frontend's dependencies and uploads them through the catalog's image API. Options:
- `--out DIR`: only write the files, to look at them first.
- `--missing`: skip entries that already have a picture.

`seed-kids-store.py --dry-run` prints the planned steps without changing anything. That script refuses to run when:
- the database already has orders;
- the kids catalog is missing;
- KHQR isn't in simulated mode;
- the API isn't the local one.

The random choices are seeded, so every run produces the same story, dated relative to today. Sales follow the store's tax mode (Settings > Taxes & payments). Switch to "VAT included" before step 4 if you want VAT on the receipts and in the tax report.

## Demo logins

| User | Password | Role | Branch |
| --- | --- | --- | --- |
| admin | admin1234 | Super admin | all |
| manager | manager1234 | Manager | Main Shop |
| cashier | cashier1234 | Cashier | Main Shop |
| srmanager | srmanager1234 | Manager | Siem Reap |
| stock | stock12345 | Inventory staff | Main Shop |
| accountant | accountant1234 | Accountant | all (read and vendor payments) |
