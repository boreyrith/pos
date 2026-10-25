#!/usr/bin/env python3
"""
Fills a DEVELOPMENT POS with about six weeks of history for the kids fashion and baby store that the
dev seeder (DevDataSeeder) creates: customers, purchase orders and deliveries, vendor invoices and
payments, transfers to Siem Reap, stock adjustments and a count, ~250 sales (cash in USD and riel,
KHQR, discounts, services, gift sets), returns, exchanges, refunds, cancelled orders and held sales.

Everything goes through the REST API, so totals, VAT, costs, stock movements, document numbers and
the audit log are worked out by the application itself. Each step is then moved back to the date
it is meant to have happened (rows written during a step get that step's time), in date order, so
running balances and numbering stay consistent.

Needs: the API on localhost (dev profile, KHQR in simulated mode), the demo logins, the MySQL
container from docker-compose, and a database with the seeded catalog and no sales yet.

    python3 scripts/demo-data/seed-kids-store.py            # add the history
    python3 scripts/demo-data/seed-kids-store.py --dry-run  # print the plan only
"""

import argparse
import datetime as dt
import json
import math
import random
import subprocess
import sys
import urllib.error
import urllib.request

import os

# Overridable to build the sample data for deployments on a throwaway stack (see deploy/sample-data).
API = os.environ.get("POS_API_URL", "http://localhost:8081/api")
CONTAINER = os.environ.get("POS_MYSQL_CONTAINER", "pos-next-mysql-1")
TZ = dt.timezone(dt.timedelta(hours=7))  # Asia/Phnom_Penh, no daylight saving
DAYS = 30  # days of sales history; purchasing starts ~6 weeks back

LOGINS = {
    "admin": "admin1234", "manager": "manager1234", "cashier": "cashier1234", "srmanager": "srmanager1234",
    "stock": "stock12345", "accountant": "accountant1234",
}

CUSTOMERS = [
    # name, phone, email, address, tax id, note, how often they come (weight)
    ("Sokunthea Chea", "012 456 781", "sokunthea.chea@gmail.com", "St. 271, Toul Tompoung, Phnom Penh", None, "Twins, 8 months", 5),
    ("Dara Sok", "092 334 120", None, "Chbar Ampov, Phnom Penh", None, None, 3),
    ("Sreyneang Lim", "077 889 451", "sreyneang.lim@yahoo.com", "BKK3, Phnom Penh", None, "Prefers organic cotton", 4),
    ("Vannak Heng", "015 220 984", None, "Sen Sok, Phnom Penh", None, None, 2),
    ("Bopha Keo", "011 765 300", "bopha.keo@gmail.com", "Russey Keo, Phnom Penh", None, "Expecting in December", 3),
    ("Rithy Chan", "096 512 778", None, None, None, None, 2),
    ("Channary Ouk", "070 431 662", "channary.ouk@outlook.com", "Daun Penh, Phnom Penh", None, "Daughter 4T, son 6Y", 4),
    ("Piseth Nhem", "088 902 115", None, "Mean Chey, Phnom Penh", None, None, 1),
    ("Malis Tep", "017 348 290", "malis.tep@gmail.com", "Svay Dangkum, Siem Reap", None, "Siem Reap regular", 3),
    ("Kosal Prak", "093 671 004", None, "Sala Kamreuk, Siem Reap", None, None, 2),
    ("Sunshine Kindergarten", "023 998 120", "admin@sunshine-kg.edu.kh", "St. 63, BKK1, Phnom Penh", "K007-901234567",
     "School uniforms and gifts; invoice with VAT TIN", 2),
    ("Little Stars Daycare Co., Ltd.", "023 881 455", "office@littlestars.com.kh", "Wat Bo, Siem Reap", "K008-902345678",
     "Bulk diapers and wipes", 1),
]

RETURN_REASONS = ["Wrong size, exchanged", "Too small, exchanged for the next size", "Gift duplicate", "Changed mind",
                  "Defect: loose stitching"]
NOTES = ["Gift for a baby shower", "Birthday gift, please wrap", "Customer will pick up the second size next week",
         "Paid by grandmother", "School order", None]


def die(msg):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


class Api:
    def __init__(self):
        self.tokens = {}

    def login(self, user):
        res = self.call("POST", "/auth/login", {"username": user, "password": LOGINS[user]}, auth=False)
        self.tokens[user] = res["accessToken"]

    def call(self, method, path, body=None, user="admin", branch=None, auth=True):
        req = urllib.request.Request(API + path, method=method)
        req.add_header("Accept", "application/json")
        if body is not None:
            req.data = json.dumps(body).encode()
            req.add_header("Content-Type", "application/json")
        if auth:
            if user not in self.tokens:
                self.login(user)
            req.add_header("Authorization", "Bearer " + self.tokens[user])
        if branch:
            req.add_header("X-Branch-Id", str(branch))
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            if e.code == 401 and auth:  # access token expired: sign in again once
                self.login(user)
                return self.call(method, path, body, user, branch, auth)
            detail = e.read().decode(errors="replace")
            raise ApiError(e.code, f"{method} {path} -> {e.code}: {detail[:300]}") from None


class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def sql(statement):
    """Runs SQL in the dev MySQL container and returns rows as lists of strings."""
    out = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "sh", "-c", 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -N -B'],
        input=statement, capture_output=True, text=True)
    if out.returncode != 0:
        die(f"SQL failed: {out.stderr.strip()}")
    return [line.split("\t") for line in out.stdout.splitlines() if line]


def utc(local):
    return local.astimezone(dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S")


# Rows written during a step carry the real time (after `start`); this moves them to the step's time.
BACKDATE = [
    ("orders", "created_at", 0), ("orders", "completed_at", 70),
    ("payments", "created_at", 0), ("payments", "paid_at", 70), ("payments", "expires_at", 900),
    ("order_returns", "created_at", 0), ("stock_movements", "created_at", 0), ("audit_logs", "created_at", 0),
    ("goods_receipts", "received_at", 0), ("purchase_orders", "created_at", 0), ("purchase_orders", "ordered_at", 0),
    ("purchase_orders", "closed_at", 0), ("stock_transfers", "created_at", 0), ("stock_transfers", "closed_at", 0),
    ("stock_adjustments", "created_at", 0), ("vendor_invoices", "created_at", 0), ("vendor_payments", "created_at", 0),
    ("customers", "created_at", 0), ("held_sales", "created_at", 0), ("held_sales", "updated_at", 0),
    ("held_sales", "locked_at", 0), ("held_sales", "closed_at", 0),
]


class Seeder:
    def __init__(self, dry_run):
        self.api = Api()
        self.dry = dry_run
        self.rng = random.Random(2026)
        self.now = dt.datetime.now(TZ)
        self.today = self.now.date()
        self.events = []  # (local time, label, fn)
        self.sold = []  # completed orders for returns: dict(id, at, branch, lines)
        self.stats = {}

    # ------------------------------------------------------------------ setup

    def check(self):
        try:
            health = urllib.request.urlopen(API + "/actuator/health", timeout=5).read()
        except OSError:
            die(f"the API isn't running at {API}")
        if b"UP" not in health:
            die("the API isn't healthy")
        if sql("SELECT DATABASE()")[0][0] != "pos_next":
            die("not the pos_next dev database")
        if sql("SELECT COUNT(*) FROM orders")[0][0] != "0":
            die("there are orders already; this adds history to a freshly reset database only")
        if sql("SELECT COUNT(*) FROM offerings WHERE sku = 'BC-BOD-03'")[0][0] != "1":
            die("the kids store catalog isn't there; start the API with the dev profile on a reset database")
        if sql("SELECT khqr_mode FROM company_settings")[0][0] != "SIMULATED":
            die("KHQR must be in simulated mode")
        self.audit_start = int(sql("SELECT COALESCE(MAX(id), 0) FROM audit_logs")[0][0])
        for user in LOGINS:
            self.api.login(user)

        rows = sql("SELECT s.id, b.code, s.name, s.selling, b.id FROM storages s JOIN branches b ON b.id = s.branch_id WHERE s.active")
        self.branch = {code: int(bid) for _, code, _, _, bid in rows}
        self.showroom = next(int(i) for i, c, n, sel, _ in rows if c == "MAIN" and sel == "1")
        self.backstore = next(int(i) for i, c, n, sel, _ in rows if c == "MAIN" and n.lower() == "back store")
        self.srp_floor = next(int(i) for i, c, n, sel, _ in rows if c == "SRP" and sel == "1")
        items = self.api.call("GET", "/offerings?size=200&type=PRODUCT,ITEM,SERVICE,PACKAGE")["items"]
        self.cat = {o["sku"]: o for o in items}
        self.vendors = {v["code"]: v for v in self.api.call("GET", "/vendors")}
        store = self.api.call("GET", "/store")
        self.inclusive = store.get("taxMode") != "EXCLUSIVE"
        rate = sql("SELECT rate FROM exchange_rates WHERE currency_code = 'KHR' ORDER BY id DESC LIMIT 1")
        self.khr = float(rate[0][0]) if rate else 4100.0
        self.start = sql("SELECT UTC_TIMESTAMP(6)")[0][0]

    def at(self, days_ago, hour, minute=0):
        d = self.today - dt.timedelta(days=days_ago)
        return dt.datetime(d.year, d.month, d.day, hour, minute, tzinfo=TZ)

    def plan(self, when, label, fn):
        if when <= self.now:
            self.events.append((when, label, fn))

    def run(self):
        self.events.sort(key=lambda e: e[0])
        print(f"{len(self.events)} steps from {self.events[0][0]:%d %b} to {self.events[-1][0]:%d %b %H:%M}")
        if self.dry:
            for when, label, _ in self.events:
                print(f"  {when:%a %d %b %H:%M}  {label}")
            return
        # The opening stock and catalog belong before everything else.
        opening = self.at(DAYS + 16, 8)
        sql(f"UPDATE stock_movements SET created_at = '{utc(opening)}' WHERE type = 'OPENING';"
            f"UPDATE offerings SET created_at = '{utc(opening)}', updated_at = '{utc(opening)}';"
            f"UPDATE vendors SET created_at = '{utc(opening)}';")
        for i, (when, label, fn) in enumerate(self.events, 1):
            try:
                fn()
            except ApiError as e:
                print(f"  ! {when:%d %b %H:%M} {label}: {e}")
            self.backdate(when)
            if i % 25 == 0:
                print(f"  {i}/{len(self.events)} {when:%d %b}")
        self.finish()

    def backdate(self, when):
        stmts = [f"UPDATE {t} SET {c} = TIMESTAMPADD(SECOND, {off}, '{utc(when)}') WHERE {c} >= '{self.start}';"
                 for t, c, off in BACKDATE]
        sql("\n".join(stmts))

    def finish(self):
        # Order numbers carry the (local) sale date; the IDs already follow the dates.
        sql("UPDATE orders SET order_number = CONCAT(DATE_FORMAT(CONVERT_TZ(created_at, '+00:00', '+07:00'), '%Y%m%d'), '-', "
            "LPAD(id, 5, '0'));"
            f"DELETE FROM audit_logs WHERE id > {self.audit_start} AND action LIKE 'LOGIN%';")
        print("Done:", ", ".join(f"{v} {k}" for k, v in sorted(self.stats.items())))

    def count(self, key, n=1):
        self.stats[key] = self.stats.get(key, 0) + n

    # --------------------------------------------------------------- helpers

    def o(self, sku):
        return self.cat[sku]

    def qty(self, sku, n):
        return [{"unitId": self.o(sku)["unitId"], "quantity": n}]

    def pack_unit(self, sku):
        """The entry's first pack unit (carton, box...); the list endpoint leaves packs out."""
        return self.api.call("GET", f"/offerings/{self.o(sku)['id']}")["packs"][0]["unitId"]

    def stock_in(self, storage):
        items = self.api.call("GET", f"/offerings?size=200&forSale=true&storageId={storage}")["items"]
        out = {}
        for i in items:
            if i["stock"] is not None:
                out[i["sku"]] = i["stock"]
            elif i.get("packageInfo") and i["packageInfo"].get("available") is not None:
                out[i["sku"]] = i["packageInfo"]["available"]
        return out

    # ------------------------------------------------------------- customers

    def plan_customers(self):
        self.customers = []
        for idx, (name, phone, email, address, tax, note, weight) in enumerate(CUSTOMERS):
            when = self.at(DAYS + 14 - idx * 2, 10 + idx % 7, 15)
            record = {"weight": weight, "id": None, "srp": "Siem Reap" in (address or "") or "Siem Reap" in (note or "")}
            self.customers.append(record)

            def create(record=record, body={"name": name, "phone": phone, "email": email, "address": address, "taxId": tax,
                                            "note": note}):
                try:
                    record["id"] = self.api.call("POST", "/customers", body, user="manager")["id"]
                    self.count("customers")
                except ApiError as e:
                    if e.status != 409:
                        raise
                    # Already there from an earlier run that stopped: use it.
                    found = self.api.call("GET", f"/customers?q={body['phone'].replace(' ', '')}", user="manager")["items"]
                    record["id"] = found[0]["id"]

            self.plan(when, f"Customer {name}", create)

    def pick_customer(self, srp):
        if self.rng.random() < 0.62:
            return None
        pool = [c for c in self.customers if c["id"] and c["srp"] == srp]
        if not pool:
            return None
        return self.rng.choices(pool, weights=[c["weight"] for c in pool])[0]["id"]

    # ------------------------------------------------------------ purchasing

    def po(self, vendor, storage, lines, note, ref=None, expected=None, user="manager"):
        body = {"vendorId": self.vendors[vendor]["id"], "storageId": storage, "note": note, "vendorRef": ref,
                "expectedDate": expected.isoformat() if expected else None,
                "lines": [{"offeringId": self.o(sku)["id"], "unitId": unit, "quantity": n, "unitCost": cost}
                          for sku, unit, n, cost in lines]}
        p = self.api.call("POST", "/purchase-orders", body, user=user)
        self.count("purchase orders")
        return p

    def receive(self, po_id, take, ref, user="stock"):
        """take: fraction of each line to receive (1 = everything left)."""
        p = self.api.call("GET", f"/purchase-orders/{po_id}", user=user)
        lines = [{"lineId": l["id"], "quantity": max(1, math.floor(l["remaining"] * take))} for l in p["lines"] if l["remaining"] > 0]
        body = {"lines": lines, "deliveryRef": ref, "note": None, "updateCost": False}
        self.count("goods receipts")
        return self.api.call("POST", f"/purchase-orders/{po_id}/receive", body, user=user)

    def invoice(self, po, number, date, terms):
        p = self.api.call("GET", f"/purchase-orders/{po}", user="accountant")
        subtotal = round(sum(l["receivedQty"] * float(l["unitCost"]) for l in p["lines"]), 2)
        body = {"vendorId": p["vendorId"], "purchaseOrderId": po, "invoiceNumber": number, "invoiceDate": date.isoformat(),
                "dueDate": (date + dt.timedelta(days=terms)).isoformat(), "subtotal": subtotal, "tax": round(subtotal * 0.10, 2),
                "note": None}
        self.count("vendor invoices")
        return self.api.call("POST", "/vendor-invoices", body, user="accountant")

    def pay(self, invoice_id, amount, method, date, ref):
        self.count("vendor payments")
        return self.api.call("POST", f"/vendor-invoices/{invoice_id}/payments",
                             {"amount": round(amount, 2), "method": method, "paidOn": date.isoformat(), "reference": ref},
                             user="accountant")

    def plan_purchasing(self):
        s = {}  # ids shared between the steps of one purchase
        d = lambda days: self.today - dt.timedelta(days=days)
        u = lambda sku: self.o(sku)["unitId"]

        # 1. New season clothing from Little Threads into the back store, received, invoiced, paid.
        def po1():
            s["po1"] = self.po("V-LTG", self.backstore, [
                ("BC-BOD-03", u("BC-BOD-03"), 24, "6.20"), ("BC-BOD-36", u("BC-BOD-36"), 24, "6.20"),
                ("BC-BOD-12", u("BC-BOD-12"), 18, "6.70"), ("GC-DRS-3T", u("GC-DRS-3T"), 10, "6.90"),
                ("GC-DRS-4T", u("GC-DRS-4T"), 10, "6.90"), ("BY-TEE-4T", u("BY-TEE-4T"), 12, "3.10"),
                ("BY-TEE-6Y", u("BY-TEE-6Y"), 12, "3.10"), ("SW-PJS-4T", u("SW-PJS-4T"), 8, "6.00"),
                ("GC-SKT-46", u("GC-SKT-46"), 6, "5.20")], "Rainy season collection", "LT-SO-2611",
                d(DAYS + 6))["id"]
            self.api.call("POST", f"/purchase-orders/{s['po1']}/place", user="manager")
        self.plan(self.at(DAYS + 10, 10), "PO Little Threads (clothing)", po1)
        self.plan(self.at(DAYS + 6, 14), "Receive Little Threads delivery",
                  lambda: s.update(grn1=self.receive(s["po1"], 1, "DN-LT-7731")))
        self.plan(self.at(DAYS + 6, 16), "Invoice Little Threads",
                  lambda: s.update(inv1=self.invoice(s["po1"], "LT-INV-2611-045", d(DAYS + 6), 30)))
        self.plan(self.at(12, 10), "Pay Little Threads in full",
                  lambda: self.pay(s["inv1"]["id"], float(s["inv1"]["total"]), "BANK_TRANSFER", d(12), "ABA TRX 88213049"))

        # 2. Diapers, wipes and formula by the carton from BabyCare; half paid so far.
        def po2():
            s["po2"] = self.po("V-BCD", self.backstore, [
                ("DP-TAP-S", self.pack_unit("DP-TAP-S"), 6, "36.00"), ("DP-TAP-M", self.pack_unit("DP-TAP-M"), 8, "36.00"),
                ("DP-TAP-L", self.pack_unit("DP-TAP-L"), 6, "36.00"), ("DP-PNT-XL", self.pack_unit("DP-PNT-XL"), 5, "38.80"),
                ("CA-WIP-80", self.pack_unit("CA-WIP-80"), 4, "30.00"), ("FD-FRM-800", self.pack_unit("FD-FRM-800"), 3, "156.00"),
                ("CA-SHP-300", u("CA-SHP-300"), 24, "3.70"), ("CA-LOT-200", u("CA-LOT-200"), 24, "3.40")],
                "Monthly baby care order", None, d(DAYS + 7))["id"]
            self.api.call("POST", f"/purchase-orders/{s['po2']}/place", user="manager")
        self.plan(self.at(DAYS + 8, 9, 30), "PO BabyCare (diapers, wipes, formula)", po2)
        self.plan(self.at(DAYS + 7, 11), "Receive BabyCare delivery", lambda: self.receive(s["po2"], 1, "BCD-DO-55102"))
        self.plan(self.at(DAYS + 7, 15), "Invoice BabyCare",
                  lambda: s.update(inv2=self.invoice(s["po2"], "BCD/2026/10/0198", d(DAYS + 7), 15)))
        self.plan(self.at(DAYS - 9, 11), "Pay half of BabyCare",
                  lambda: self.pay(s["inv2"]["id"], float(s["inv2"]["total"]) / 2, "BANK_TRANSFER", d(DAYS - 9), "ACLEDA 4471902"))

        # 3. Shoes from Tiny Toes: delivered short (back-ordered sizes), invoice for what came, not paid yet.
        def po3():
            s["po3"] = self.po("V-TTF", self.showroom, [
                ("SH-SND-24", u("SH-SND-24"), 6, "6.50"), ("SH-SND-26", u("SH-SND-26"), 6, "6.50"),
                ("SH-SNK-28", u("SH-SNK-28"), 6, "11.50"), ("SH-SNK-30", u("SH-SNK-30"), 6, "11.50"),
                ("SH-PRW-S", u("SH-PRW-S"), 10, "3.40")], "Shoes restock", "TT-Q-0912", d(DAYS - 9))["id"]
            self.api.call("POST", f"/purchase-orders/{s['po3']}/place", user="manager")
        self.plan(self.at(DAYS - 5, 10), "PO Tiny Toes (shoes)", po3)
        self.plan(self.at(DAYS - 9, 13), "Receive part of Tiny Toes delivery", lambda: self.receive(s["po3"], 0.5, "TT-DN-3390"))
        self.plan(self.at(DAYS - 9, 16), "Invoice Tiny Toes (part delivery)",
                  lambda: self.invoice(s["po3"], "TTF-5521", d(DAYS - 9), 30))

        # Mid-month restock of the fast movers.
        def po_restock_care():
            s["poR"] = self.po("V-BCD", self.backstore, [
                ("DP-TAP-S", self.pack_unit("DP-TAP-S"), 4, "36.00"), ("DP-TAP-M", self.pack_unit("DP-TAP-M"), 8, "36.00"),
                ("DP-TAP-L", self.pack_unit("DP-TAP-L"), 6, "36.00"), ("DP-PNT-XL", self.pack_unit("DP-PNT-XL"), 3, "38.80"),
                ("CA-WIP-80", self.pack_unit("CA-WIP-80"), 3, "30.00")], "Restock: diapers selling fast", None, d(15))["id"]
            self.api.call("POST", f"/purchase-orders/{s['poR']}/place", user="manager")
        self.plan(self.at(16, 9, 15), "PO BabyCare restock", po_restock_care)
        self.plan(self.at(15, 10, 30), "Receive BabyCare restock", lambda: self.receive(s["poR"], 1, "BCD-DO-55377"))
        self.plan(self.at(15, 14), "Invoice BabyCare restock",
                  lambda: s.update(invR=self.invoice(s["poR"], "BCD/2026/10/0241", d(15), 15)))
        self.plan(self.at(15, 15), "Fill the showroom from the restock", lambda: self.transfer(
            self.backstore, self.showroom, [("DP-TAP-S", 8), ("DP-TAP-M", 16), ("DP-TAP-L", 12), ("DP-PNT-XL", 8),
                                            ("CA-WIP-80", 36)], "Restock to the floor", "stock"))

        def po_restock_clothes():
            s["poC"] = self.po("V-LTG", self.showroom, [
                ("BC-SWD-01", u("BC-SWD-01"), 12, "6.80"), ("BC-SLP-03", u("BC-SLP-03"), 10, "4.40"),
                ("BC-SLP-36", u("BC-SLP-36"), 10, "4.40"), ("BY-POL-4Y", u("BY-POL-4Y"), 8, "4.60"),
                ("BY-POL-6Y", u("BY-POL-6Y"), 8, "4.60"), ("BY-JOG-4Y", u("BY-JOG-4Y"), 8, "4.20"),
                ("GC-DRS-2T", u("GC-DRS-2T"), 6, "6.90"), ("SW-PJS-6Y", u("SW-PJS-6Y"), 6, "6.40")],
                "Reorder best sellers", "LT-SO-2624", d(10))["id"]
            self.api.call("POST", f"/purchase-orders/{s['poC']}/place", user="manager")
        self.plan(self.at(13, 11), "PO Little Threads reorder", po_restock_clothes)
        self.plan(self.at(10, 13, 30), "Receive Little Threads reorder", lambda: self.receive(s["poC"], 1, "DN-LT-7802"))
        self.plan(self.at(10, 16), "Invoice Little Threads reorder",
                  lambda: self.invoice(s["poC"], "LT-INV-2611-061", d(10), 30))

        # 4. Toys from PlayWorld, cash on delivery.
        def po4():
            s["po4"] = self.po("V-PWT", self.showroom, [
                ("TY-PLH-EL", u("TY-PLH-EL"), 10, "5.10"), ("TY-BLK-12", u("TY-BLK-12"), 8, "6.80"),
                ("TY-DCK-03", u("TY-DCK-03"), 24, "1.60"), ("TY-RTL-WD", u("TY-RTL-WD"), 12, "2.00")],
                "Toys for the holiday display", None, d(17))["id"]
            self.api.call("POST", f"/purchase-orders/{s['po4']}/place", user="manager")
        self.plan(self.at(18, 9, 45), "PO PlayWorld (toys)", po4)
        self.plan(self.at(17, 15), "Receive PlayWorld delivery", lambda: self.receive(s["po4"], 1, "PW-2290", user="manager"))

        def inv4():
            inv = self.invoice(s["po4"], "PW-2290", d(17), 0)
            self.pay(inv["id"], float(inv["total"]), "CASH", d(17), "Paid to driver")
        self.plan(self.at(17, 15, 20), "Invoice and pay PlayWorld (COD)", inv4)

        # 5. A toy order cancelled before it was placed with the vendor.
        def po5():
            p = self.po("V-PWT", self.showroom, [("TY-STK-RG", u("TY-STK-RG"), 12, "3.20")], "Stacking rings", None, d(20))
            self.api.call("POST", f"/purchase-orders/{p['id']}/cancel", user="manager")
        self.plan(self.at(22, 11), "PO PlayWorld cancelled", po5)

        # 6. Packaging and embroidery thread: ordered, on its way.
        def po6():
            p = self.po("V-APS", self.showroom, [
                ("SP-THR-01", u("SP-THR-01"), 20, "0.60"), ("SP-GBX-M", self.pack_unit("SP-GBX-M"), 2, "19.00"),
                ("SP-BAG-PP", u("SP-BAG-PP"), 300, "0.15")], "Thread is running low", "APS-Q-118", self.today + dt.timedelta(days=2))
            self.api.call("POST", f"/purchase-orders/{p['id']}/place", user="manager")
        self.plan(self.at(4, 10), "PO Angkor Packaging (ordered)", po6)

        # 7. Next season draft, not sent yet.
        self.plan(self.at(1, 17), "PO Little Threads draft", lambda: self.po("V-LTG", self.backstore, [
            ("GC-DRS-2T", u("GC-DRS-2T"), 10, "6.90"), ("GC-DRS-5Y", u("GC-DRS-5Y"), 8, "7.30"),
            ("BY-SHT-4Y", u("BY-SHT-4Y"), 12, "5.10"), ("BY-SHT-6Y", u("BY-SHT-6Y"), 12, "5.10")], "Draft: cool season", None,
            self.today + dt.timedelta(days=14)))

    # -------------------------------------------------------- stock movement

    def transfer(self, frm, to, lines, note, receive_as):
        t = self.api.call("POST", "/transfers", {"fromStorageId": frm, "toStorageId": to, "note": note,
                                                  "lines": [{"offeringId": self.o(sku)["id"], "quantities": self.qty(sku, n)}
                                                            for sku, n in lines]}, user="stock")
        self.count("transfers")
        if receive_as and t["status"] != "RECEIVED":
            self.api.call("POST", f"/transfers/{t['id']}/receive", user=receive_as)
        return t

    def plan_stock(self):
        # Back store to showroom after each delivery.
        self.plan(self.at(DAYS + 6, 17), "Transfer new clothing to the showroom", lambda: self.transfer(
            self.backstore, self.showroom, [("BC-BOD-03", 12), ("BC-BOD-36", 12), ("BC-BOD-12", 10), ("GC-DRS-3T", 6),
                                            ("GC-DRS-4T", 6), ("BY-TEE-4T", 8), ("BY-TEE-6Y", 8), ("SW-PJS-4T", 6),
                                            ("GC-SKT-46", 4)], "Fill the shelves", "stock"))

        def baby_care_to_floor():
            self.transfer(self.backstore, self.showroom, [("DP-TAP-S", 8), ("DP-TAP-M", 12), ("DP-TAP-L", 8), ("DP-PNT-XL", 8),
                                                           ("CA-WIP-80", 48), ("FD-FRM-800", 6), ("CA-SHP-300", 12),
                                                           ("CA-LOT-200", 12)], "Baby care to the floor", "stock")
        self.plan(self.at(DAYS + 7, 16), "Transfer baby care to the showroom", baby_care_to_floor)
        self.plan(self.at(12, 9, 30), "Top up diapers from the back store", lambda: self.transfer(
            self.backstore, self.showroom, [("DP-TAP-M", 8), ("DP-TAP-L", 6), ("CA-WIP-80", 24)], "Weekend top-up", "stock"))

        # Stock for the Siem Reap branch.
        srp = [("BC-BOD-03", 10), ("BC-BOD-36", 10), ("BC-BOD-12", 6), ("BC-HAT-NB", 12), ("BC-MIT-NB", 12), ("BC-SWD-01", 6),
               ("GC-DRS-3T", 4), ("GC-TEE-4Y", 5), ("BY-TEE-4T", 6), ("SW-PJS-2T", 3), ("SH-PRW-S", 5), ("AC-SOC-05", 12),
               ("AC-HAT-UV", 5), ("CA-WIP-80", 36), ("CA-LOT-200", 8), ("CA-SHP-300", 8), ("DP-TAP-S", 10), ("DP-TAP-M", 14),
               ("DP-TAP-L", 12), ("FD-BTL-150", 8), ("FD-PAC-01", 10), ("FD-CER-200", 8), ("TY-DCK-03", 10), ("TY-RTL-WD", 6),
               ("SP-BAG-PP", 80)]
        self.plan(self.at(DAYS + 5, 9), "Transfer opening stock to Siem Reap",
                  lambda: s_update(self, "tr_srp", self.transfer(self.showroom, self.srp_floor, srp, "Siem Reap opening stock", None)))
        self.plan(self.at(DAYS + 4, 15), "Siem Reap receives its stock",
                  lambda: self.api.call("POST", f"/transfers/{self.shared['tr_srp']['id']}/receive", user="srmanager"))
        self.plan(self.at(14, 8, 30), "Replenish Siem Reap", lambda: self.transfer(
            self.backstore, self.srp_floor, [("DP-TAP-M", 10), ("DP-TAP-L", 8), ("DP-TAP-S", 6), ("CA-WIP-80", 24),
                                             ("BC-BOD-36", 6), ("BC-BOD-12", 6)],
            "Mid-month replenishment", "srmanager"))
        self.plan(self.at(7, 8, 30), "Replenish Siem Reap from the showroom", lambda: self.transfer(
            self.showroom, self.srp_floor, [("BC-HAT-NB", 8), ("BC-MIT-NB", 8), ("BC-SWD-01", 4), ("SH-PRW-M", 4), ("AC-SOC-05", 8),
                                            ("FD-BTL-150", 6), ("FD-PAC-01", 6), ("FD-CER-200", 6), ("TY-DCK-03", 6),
                                            ("TY-RTL-WD", 4), ("CA-SHP-300", 6), ("CA-LOT-200", 6), ("GC-DRS-4T", 3),
                                            ("BY-TEE-6Y", 4)], "Weekly replenishment", "srmanager"))
        self.plan(self.at(2, 16), "Transfer to Siem Reap (in transit)", lambda: self.transfer(
            self.showroom, self.srp_floor, [("DP-TAP-M", 6), ("CA-WIP-80", 12), ("BC-BOD-36", 4), ("FD-PAC-01", 6)],
            "Weekly replenishment, on the 3 pm bus", None))

        # Adjustments and a count.
        def damaged():
            self.api.call("POST", "/stock-adjustments", {"storageId": self.showroom, "kind": "ADJUSTMENT", "note": "Found during unpacking",
                                                         "lines": [
                {"offeringId": self.o("CA-LOT-200")["id"], "operation": "DECREASE", "quantities": self.qty("CA-LOT-200", 1),
                 "reason": "DAMAGED", "note": "Bottle leaked"},
                {"offeringId": self.o("GC-DRS-4T")["id"], "operation": "DECREASE", "quantities": self.qty("GC-DRS-4T", 1),
                 "reason": "DAMAGED", "note": "Stain on the front"}]}, user="stock")
            self.count("adjustments")
        self.plan(self.at(DAYS - 2, 18), "Write off damaged stock", damaged)

        def count_accessories():
            counts = self.stock_in(self.showroom)
            lines = []
            for sku, diff in [("AC-SOC-05", -1), ("AC-HCL-10", -2), ("AC-HAT-UV", 0), ("AC-BAG-BR", 0)]:
                have = counts.get(sku, 0)
                lines.append({"offeringId": self.o(sku)["id"], "operation": "SET", "quantities": self.qty(sku, max(0, have + diff)),
                              "expected": have})
            self.api.call("POST", "/stock-adjustments", {"storageId": self.showroom, "kind": "COUNT",
                                                         "note": "Monthly count: accessories wall", "lines": lines}, user="stock")
            self.count("stock counts")
        self.plan(self.at(15, 19), "Count the accessories wall", count_accessories)

        def found_and_used():
            self.api.call("POST", "/stock-adjustments", {"storageId": self.showroom, "kind": "ADJUSTMENT", "note": None, "lines": [
                {"offeringId": self.o("AC-HCL-10")["id"], "operation": "INCREASE", "quantities": self.qty("AC-HCL-10", 1),
                 "reason": "FOUND", "note": "Behind the counter"},
                {"offeringId": self.o("SP-BAG-PP")["id"], "operation": "DECREASE", "quantities": self.qty("SP-BAG-PP", 10),
                 "reason": "INTERNAL_USE", "note": "Window display"}]}, user="manager")
            self.count("adjustments")
        self.plan(self.at(8, 18, 30), "Found clips, bags used for display", found_and_used)

    # ----------------------------------------------------------------- sales

    # What sells, and how often (weights), per kind of basket.
    WEIGHTS = {"DP-": 9, "CA-WIP": 9, "CA-": 4, "FD-": 4, "BC-": 6, "GC-": 4, "BY-": 4, "SW-": 2, "SH-": 2, "AC-": 3, "TY-": 3,
               "GR-STR": 0.2, "GR-": 0.6, "GS-": 1.2, "SP-GBX": 0.6}

    def weight(self, sku):
        for prefix, w in self.WEIGHTS.items():
            if sku.startswith(prefix):
                return w
        return 0

    def sale(self, when, srp):
        user = "srmanager" if srp else self.rng.choice(["cashier", "cashier", "cashier", "manager"])
        storage = self.srp_floor if srp else self.showroom
        stock = self.stock_in(storage)
        choices = [sku for sku, n in stock.items() if n > 0 and self.weight(sku) > 0]
        if not choices:
            return
        n_lines = self.rng.choices([1, 2, 3, 4, 5], weights=[30, 33, 20, 11, 6])[0]
        picked = []
        while len(picked) < n_lines and len(picked) < len(choices):
            sku = self.rng.choices(choices, weights=[self.weight(c) for c in choices])[0]
            if sku not in picked:
                picked.append(sku)
        lines = []
        for sku in picked:
            o = self.o(sku)
            most = stock[sku]
            n = 1
            if sku.startswith(("DP-", "CA-WIP")):
                n = self.rng.choice([1, 1, 2, 2, 3, 4])
            elif self.rng.random() < 0.2:
                n = 2
            line = {"offeringId": o["id"], "quantity": min(n, most)}
            # Managers give an item discount now and then, and mark down a display piece.
            if user == "manager" and float(o["price"]) >= 12 and self.rng.random() < 0.18:
                line.update(discountType="PERCENT", discountValue=self.rng.choice([5, 10, 15]))
            elif user == "manager" and sku == "SH-SNK-30" and self.rng.random() < 0.5:
                line.update(unitPrice="19.90", priceReason="Display pair, light scuff")
            lines.append(line)
        services = []
        if any(sku.startswith("GS-") or sku == "SP-GBX-M" for sku in picked) and self.rng.random() < 0.6:
            services.append({"offeringId": self.o("SV-GFT-01")["id"], "quantity": 1})
        if not srp and any(sku.startswith(("BY-TEE", "GC-TEE", "BY-POL")) for sku in picked) and self.rng.random() < 0.15 \
                and stock.get("SP-THR-01", 1) > 0:
            services.append({"offeringId": self.o("SV-EMB-01")["id"], "quantity": 1})
        if not srp and self.rng.random() < 0.03:
            services.append({"offeringId": self.o("SV-PHO-HR")["id"], "quantity": 1, "hours": self.rng.choice([1, 1.5])})
        if not srp and any(sku.startswith(("BY-JOG", "GC-LEG", "BY-SHT")) for sku in picked) and self.rng.random() < 0.1:
            services.append({"offeringId": self.o("SV-ALT-01")["id"], "quantity": 1})

        customer = self.pick_customer(srp)
        body = {"items": lines + services, "customerId": customer, "paymentMethod": "CASH",
                "note": self.rng.choice(NOTES) if self.rng.random() < 0.12 else None}
        if self.rng.random() < 0.12 and not any("discountType" in l for l in lines):
            body.update(discountType="PERCENT", discountValue=self.rng.choice([5, 10]))
        # Rough total (prices include VAT here) to hand over enough cash.
        estimate = sum(float(self.o(next(k for k, v in self.cat.items() if v["id"] == l["offeringId"]))["price"])
                       * l["quantity"] * float(l.get("hours", 1)) for l in lines + services)
        if not self.inclusive:
            estimate *= 1.1
        how = self.rng.random()
        if how < 0.27:
            body["paymentMethod"] = "KHQR"
            body["khqrCurrency"] = self.rng.choice(["USD", "USD", "KHR"])
        elif how < 0.45:
            riel = math.ceil(estimate * self.khr / 5000) * 5000
            body.update(tenders=[{"currency": "KHR", "amount": riel}], changeCurrency="KHR")
        elif how < 0.75:
            note = next(n for n in [5, 10, 20, 50, 100, 200] if n >= estimate) if estimate <= 200 else math.ceil(estimate)
            body.update(tenders=[{"currency": "USD", "amount": note}], changeCurrency="USD")
        try:
            order = self.api.call("POST", "/orders", body, user=user)
        except ApiError as e:
            if e.status in (400, 409) and services:  # a part for a service ran out: sell the goods alone
                body["items"] = lines
                body.pop("tenders", None)
                body.pop("changeCurrency", None)
                order = self.api.call("POST", "/orders", body, user=user)
            else:
                raise
        if order["status"] == "PENDING_PAYMENT":
            if self.rng.random() < 0.04:  # customer's banking app failed; they left
                self.api.call("POST", f"/orders/{order['id']}/cancel", user=user)
                self.count("cancelled orders")
                return
            self.api.call("POST", f"/payments/{order['payments'][0]['id']}/simulate-paid", user=user)
        self.count("orders")
        self.sold.append({"id": order["id"], "at": when, "srp": srp, "items": order["items"]})

    def plan_sales(self):
        for days_ago in range(DAYS, -1, -1):
            day = self.today - dt.timedelta(days=days_ago)
            weekend = day.weekday() >= 5
            for srp in (False, True):
                if srp and days_ago > DAYS - 4:
                    continue  # Siem Reap opens once its stock arrives
                n = self.rng.randint(1, 2) + (1 if weekend else 0) if srp else self.rng.randint(4, 7) + (4 if weekend else 0)
                hours = list(range(9, 21))
                weights = [3, 5, 6, 6, 4, 4, 5, 6, 8, 9, 7, 4]
                if days_ago == 0:  # today: the morning so far
                    keep = [i for i, h in enumerate(hours) if h < self.now.hour] or [0]
                    hours, weights = [hours[i] for i in keep], [weights[i] for i in keep]
                    n = max(2, n * len(hours) // 12)
                for _ in range(n):
                    hour = self.rng.choices(hours, weights=weights)[0]
                    when = dt.datetime(day.year, day.month, day.day, hour, self.rng.randint(0, 59), tzinfo=TZ)
                    if when > self.now:
                        when = self.now - dt.timedelta(minutes=self.rng.randint(5, 50))
                    self.plan(when, f"Sale {'Siem Reap' if srp else 'Main'}", lambda when=when, srp=srp: self.sale(when, srp))

        # Returns and exchanges a few days after a sale, and two full refunds.
        for k in range(9):
            days_ago = self.rng.randint(1, DAYS - 4)
            when = self.at(days_ago, self.rng.randint(10, 18), self.rng.randint(0, 59))
            self.plan(when, "Return / exchange", lambda when=when, k=k: self.give_back(when, full=k in (3, 7)))

    def give_back(self, when, full):
        pool = [s for s in self.sold if not s["srp"] and dt.timedelta(days=1) <= when - s["at"] <= dt.timedelta(days=7)
                and not s.get("returned")]
        if full:
            pool = [s for s in pool if len(s["items"]) == 1]
        else:
            pool = [s for s in pool if any(i["type"] == "PRODUCT" for i in s["items"])]
        if not pool:
            return
        s = self.rng.choice(pool)
        s["returned"] = True
        if full:
            self.api.call("POST", f"/orders/{s['id']}/refund", user="manager")
            self.count("refunds")
            return
        item = next(i for i in s["items"] if i["type"] == "PRODUCT")
        reason = self.rng.choice(RETURN_REASONS)
        self.api.call("POST", f"/orders/{s['id']}/returns",
                      {"lines": [{"orderItemId": item["id"], "quantity": 1}], "reason": reason}, user="manager")
        self.count("returns")
        if "exchanged" in reason:
            # The exchange: the next size of the same garment, if there is one in stock.
            sku = next(k for k, v in self.cat.items() if v["id"] == item["offeringId"])
            family = sorted(k for k in self.cat if k.rsplit("-", 1)[0] == sku.rsplit("-", 1)[0] and k != sku)
            stock = self.stock_in(self.showroom)
            other = next((k for k in family if stock.get(k, 0) > 0), None)
            if other:
                self.api.call("POST", "/orders", {"items": [{"offeringId": self.o(other)["id"], "quantity": 1}],
                                                  "paymentMethod": "CASH", "note": f"Exchange for return on order {s['id']}"},
                              user="cashier")
                self.count("orders")

    # ----------------------------------------------------------------- holds

    def plan_holds(self):
        def hold(label, skus, note, user="cashier"):
            lines = [{"offeringId": self.o(sku)["id"], "quantity": 1} for sku in skus]
            return self.api.call("POST", "/held-sales", {"label": label, "note": note, "lines": lines}, user=user)

        def voided():
            h = hold("Phone order: Ms. Nary", ["GR-CAR-ER"], "Will confirm colour by phone")
            self.api.call("POST", f"/held-sales/{h['id']}/void", {"reason": "Customer cancelled by phone"}, user="cashier")
            self.count("held sales")
        self.plan(self.at(2, 11, 20), "Held sale voided", voided)
        # Never resumed: expires on its own (after 24 h) and shows under Recently closed.
        self.plan(self.at(2, 18, 40), "Held sale left to expire",
                  lambda: (hold("Lady in red dress", ["GC-DRS-3T", "AC-HCL-10"], "Went to the ATM"), self.count("held sales")))

        def resumed_and_paid():
            stock = self.stock_in(self.showroom)
            skus = [k for k in ["BC-SWD-01", "TY-PLH-EL", "BC-BOD-36", "TY-BLK-12", "FD-BTL-260"] if stock.get(k, 0) > 0][:2]
            h = hold("Grandma, back after lunch", skus, None, user="cashier")
            self.api.call("POST", f"/held-sales/{h['id']}/resume", user="manager")
            self.api.call("POST", "/orders", {"items": [{"offeringId": self.o(k)["id"], "quantity": 1} for k in skus],
                                              "paymentMethod": "CASH", "heldSaleId": h["id"]}, user="manager")
            self.count("held sales")
            self.count("orders")
        self.plan(self.at(1, 15, 5), "Held sale resumed and paid", resumed_and_paid)

        # Waiting now, for the demo.
        hour = max(9, min(self.now.hour - 1, 19))
        self.plan(self.at(0, hour, 5), "Held sale waiting (mum choosing sizes)",
                  lambda: (hold("Mum choosing sizes", ["BY-TEE-4T", "BY-SHT-4Y", "AC-SOC-05"], "Trying 4Y or 6Y"), self.count("held sales")))
        self.plan(self.at(0, hour, 25), "Held sale waiting (Sunshine Kindergarten)",
                  lambda: (hold("Sunshine Kindergarten", ["AC-SOC-05", "AC-SOC-05", "BY-POL-4Y", "BY-POL-6Y"],
                                "Teacher brings the PO tomorrow", user="manager"), self.count("held sales")))


def s_update(seeder, key, value):
    seeder.shared[key] = value


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true", help="print the plan without changing anything")
    args = ap.parse_args()
    s = Seeder(args.dry_run)
    s.shared = {}
    s.check()
    s.plan_customers()
    s.plan_purchasing()
    s.plan_stock()
    s.plan_sales()
    s.plan_holds()
    s.run()


if __name__ == "__main__":
    main()
