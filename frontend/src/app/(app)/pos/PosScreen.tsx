"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Banknote, Clock, LayoutGrid, Minus, Pause, Plus, Printer, QrCode, ScanLine, Search, ShoppingCart, Star, Tag, Trash2, TrendingUp } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { CameraScanner } from "@/components/CameraScanner";
import { CustomerPicker, type PickedCustomer } from "@/components/customers/CustomerPicker";
import { CashPayment, type CashTender } from "@/components/pos/CashPayment";
import { HeldSalesList, HoldDialog, heldSalesKey } from "@/components/pos/HeldSales";
import { KhqrPayment } from "@/components/pos/KhqrPayment";
import { Receipt } from "@/components/pos/Receipt";
import { Badge, Button, ErrorNote, Field, Input, Modal, Select, Spinner, cx } from "@/components/ui";
import { ApiError, api, queryString } from "@/lib/api";
import { useUser } from "@/lib/auth";
import { useBranch } from "@/lib/branch";
import { amountIn, cashIn, duration, money } from "@/lib/format";
import { calculateTotals, lineTotal, taxKeyFor, taxLabel } from "@/lib/pricing";
import { offeringImageUrl, useActiveCurrencies, useCategories, useStaff, useStoreSettings } from "@/lib/queries";
import type {
  DiscountType,
  HeldSale,
  Offering,
  OfferingType,
  Order,
  Page,
  PaymentMethod,
  ResumedHold,
  StoreSettings,
} from "@/lib/types";
import { msg, translate, translateServer, useT } from "@/lib/i18n";

/**
 * `hours` and `staffId` only apply to services; `staffId` null means the cashier did the work.
 * `price` is a changed price (needs "Override prices"), null for the catalog price.
 * `discountType`/`discountValue` are an item discount (needs "Discount items").
 */
interface CartLine {
  offering: Offering;
  quantity: number;
  hours: number | null;
  staffId: number | null;
  price?: number | null;
  priceReason?: string;
  discountType?: DiscountType | null;
  discountValue?: number;
}

/** What a cart line sells at. */
const unitPrice = (l: CartLine) => l.price ?? l.offering.price;

/** The sell screen's quick views. */
type SellView = "all" | "favourites" | "top";

/** Top selling products look back this many days. */
const TOP_DAYS = 30;

const TYPE_FILTERS: { value: OfferingType | null; label: string }[] = [
  { value: null, label: msg("All") },
  { value: "PRODUCT", label: msg("Products") },
  { value: "SERVICE", label: msg("Services") },
  { value: "ITEM", label: msg("Items") },
  { value: "PACKAGE", label: msg("Packages") },
];

/**
 * The most of this line that can be sold: stock for products and items, how many can be made for
 * packages, Infinity for services (the server checks their parts).
 */
const maxQuantity = (o: Offering) => (o.stock !== null ? o.stock : (o.packageInfo?.available ?? Infinity));

type Stage =
  | { kind: "cart" }
  | { kind: "cash" }
  | { kind: "khqr-currency" }
  | { kind: "khqr"; order: Order; cart: number }
  | { kind: "done"; order: Order };

/** How many sales a cashier can keep open at once. */
const CART_COUNT = 3;

/** One open sale: its lines, discount, who it is for and a note. Held carts keep all of it. */
interface SaleCart {
  lines: CartLine[];
  discountType: DiscountType;
  discountValue: string;
  /** Who the sale is for; null is the Walk-in Customer. */
  customer: PickedCustomer | null;
  note: string;
  /** The held sale this cart was resumed from; paying completes it, holding again updates it. */
  hold?: { id: number; number: string; label?: string | null } | null;
}

const emptyCart = (): SaleCart => ({ lines: [], discountType: "PERCENT", discountValue: "", customer: null, note: "" });

/** Carts are kept per cashier and branch for the browser tab, so switching or reloading loses nothing. */
const cartsKey = (userId: number, branchId: number | undefined) => `pos.carts.v5.${userId}.${branchId ?? "home"}`;

/** The current tax settings decide each line's tax (the server applies the same rules at checkout). */
const taxKey = (o: Offering, store: StoreSettings | undefined) =>
  store ? taxKeyFor(o, store) : { rateId: o.taxRateId, name: o.effectiveTaxName, rate: o.effectiveTaxRate, included: o.includesTax };

function loadCarts(key: string): { carts: SaleCart[]; active: number } {
  try {
    const raw = sessionStorage.getItem(key);
    const saved = raw ? (JSON.parse(raw) as { carts?: Partial<SaleCart>[]; active?: number }) : {};
    const carts = Array.from({ length: CART_COUNT }, (_, i) => {
      const c = saved.carts?.[i];
      return {
        ...emptyCart(),
        ...c,
        // Carts saved before customers existed kept a typed name; start those as walk-ins.
        customer: c?.customer && typeof c.customer === "object" ? c.customer : null,
        lines: Array.isArray(c?.lines) ? c.lines.filter((l) => l?.offering?.id) : [],
      };
    });
    const active = typeof saved.active === "number" && saved.active >= 0 && saved.active < CART_COUNT ? saved.active : 0;
    return { carts, active };
  } catch {
    return { carts: Array.from({ length: CART_COUNT }, emptyCart), active: 0 };
  }
}

const cartItems = (c: SaleCart) => c.lines.reduce((n, l) => n + l.quantity, 0);

/** Today's catalog entries for these ids; anything missing is no longer for sale. */
async function freshOfferings(ids: number[]): Promise<Map<number, Offering>> {
  if (ids.length === 0) return new Map();
  const page = await api<Page<Offering>>(`/offerings${queryString({ ids: [...new Set(ids)].join(","), forSale: true, size: 200 })}`);
  return new Map(page.items.map((o) => [o.id, o]));
}

/**
 * Brings cart lines up to date with the catalog: today's price and stock, and drops what can no
 * longer be sold. Returns what changed, to tell the cashier. Changed prices on a line are kept.
 */
function refreshLines(lines: CartLine[], fresh: Map<number, Offering>, currency: string) {
  const changes: string[] = [];
  const out: CartLine[] = [];
  for (const l of lines) {
    const o = fresh.get(l.offering.id);
    if (!o) {
      changes.push(translate("{name} is no longer available and was removed", { name: l.offering.name }));
      continue;
    }
    if (o.price !== l.offering.price && l.price == null) {
      changes.push(translate("{name} is now {price} (was {old})", { name: o.name, price: money(o.price, currency), old: money(l.offering.price, currency) }));
    }
    const max = maxQuantity(o);
    if (max <= 0) {
      changes.push(translate("{name} is out of stock and was removed", { name: o.name }));
      continue;
    }
    if (l.quantity > max) changes.push(translate("Only {n} {name} left, quantity lowered", { n: max, name: o.name }));
    out.push({ ...l, offering: o, quantity: Math.min(l.quantity, max) });
  }
  return { lines: out, changes };
}

/** What the server needs for each line, at checkout and when holding. */
const linePayload = (l: CartLine) => ({
  offeringId: l.offering.id,
  quantity: l.quantity,
  hours: l.offering.pricing === "HOURLY" ? l.hours : null,
  staffId: l.offering.type === "SERVICE" || l.offering.packageInfo?.containsServices ? l.staffId : null,
  ...(l.price != null ? { unitPrice: l.price, priceReason: l.priceReason } : {}),
  ...(l.discountType && l.discountValue ? { discountType: l.discountType, discountValue: l.discountValue } : {}),
});

export function PosScreen() {
  const t = useT();
  const queryClient = useQueryClient();
  const user = useUser();
  const store = useStoreSettings();
  const categories = useCategories();
  const staff = useStaff();
  const currencies = useActiveCurrencies();
  const { current: branch, sellingStorage } = useBranch();
  const storageKey = cartsKey(user.id, branch?.id);

  const [type, setType] = useState<OfferingType | null>(null);
  const [search, setSearch] = useState("");
  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [stage, setStage] = useState<Stage>({ kind: "cart" });
  const [notice, setNotice] = useState<string | null>(null);
  const [scanning, setScanning] = useState(false);
  const [repricing, setRepricing] = useState<CartLine | null>(null);
  const [discounting, setDiscounting] = useState<CartLine | null>(null);
  const [holding, setHolding] = useState(false);
  const [heldOpen, setHeldOpen] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);
  // AppShell renders pages only after the session check in the browser, so storage is available here.
  const [saved, setSaved] = useState(() => ({ key: storageKey, ...loadCarts(storageKey) }));
  // Switching branch (admins) switches to that branch's carts.
  if (saved.key !== storageKey) setSaved({ key: storageKey, ...loadCarts(storageKey) });
  const { carts, active } = saved;
  const current = carts[active];
  const cart = current.lines;
  const { discountType, discountValue } = current;
  /** Changes the cart being worked on; the others stay as they are. */
  const updateCart = useCallback(
    (change: (c: SaleCart) => SaleCart, index?: number) =>
      setSaved((s) => ({ ...s, carts: s.carts.map((c, i) => (i === (index ?? s.active) ? change(c) : c)) })),
    [],
  );
  const setCart = useCallback(
    (lines: CartLine[] | ((l: CartLine[]) => CartLine[])) =>
      updateCart((c) => ({ ...c, lines: typeof lines === "function" ? lines(c.lines) : lines })),
    [updateCart],
  );
  const setDiscountType = (discountType: DiscountType) => updateCart((c) => ({ ...c, discountType }));
  const setDiscountValue = (discountValue: string) => updateCart((c) => ({ ...c, discountValue }));
  const switchTo = useCallback((index: number) => {
    setSaved((s) => ({ ...s, active: index }));
    setNotice(null);
  }, []);

  // Keep every cart across navigation and reloads within the tab.
  useEffect(() => {
    try {
      sessionStorage.setItem(saved.key, JSON.stringify({ carts: saved.carts, active: saved.active }));
    } catch {
      // storage unavailable: the carts just aren't kept
    }
  }, [saved]);

  /** Units of an entry held in the other carts: they count against what this cart can take. */
  const heldElsewhere = useCallback(
    (offeringId: number) =>
      carts.reduce((n, c, i) => (i === active ? n : n + (c.lines.find((l) => l.offering.id === offeringId)?.quantity ?? 0)), 0),
    [carts, active],
  );
  const available = useCallback((o: Offering) => maxQuantity(o) - heldElsewhere(o.id), [heldElsewhere]);

  // Carts kept in the tab may be hours old: bring prices and stock up to date when they load.
  const refreshedKey = useRef<string | null>(null);
  useEffect(() => {
    if (refreshedKey.current === saved.key || !store.data) return;
    refreshedKey.current = saved.key;
    const ids = saved.carts.flatMap((c) => c.lines.map((l) => l.offering.id));
    if (ids.length === 0) return;
    const currency = store.data.currency;
    freshOfferings(ids)
      .then((fresh) => {
        const notes: string[] = [];
        saved.carts.forEach((c, i) => {
          if (c.lines.length === 0) return;
          const { changes } = refreshLines(c.lines, fresh, currency);
          if (changes.length) notes.push(`Cart ${i + 1}: ${changes.join("; ")}`);
          updateCart((cur) => ({ ...cur, lines: refreshLines(cur.lines, fresh, currency).lines }), i);
        });
        if (notes.length) setNotice(translate("Updated since these carts were started. {changes}", { changes: notes.join(". ") }));
      })
      .catch(() => {
        // offline or signed out: the server still checks prices at checkout
      });
  }, [saved.key, saved.carts, store.data, updateCart]);

  const heldCount = useQuery({
    queryKey: [...heldSalesKey, "count"],
    queryFn: () => api<{ open: number }>("/held-sales/count"),
    refetchInterval: 30_000,
  });

  // Quick views: everything, the cashier's favourites, or what sells most here.
  const viewKey = `pos.sellView.${user.id}`;
  const [view, setViewState] = useState<SellView>(() => {
    try {
      const saved = localStorage.getItem(viewKey);
      return saved === "favourites" || saved === "top" ? saved : "all";
    } catch {
      return "all";
    }
  });
  const setView = (v: SellView) => {
    setViewState(v);
    try {
      localStorage.setItem(viewKey, v);
    } catch {
      // not remembered on this device
    }
  };
  const favouriteIds = useQuery({
    queryKey: ["offerings", "favourites", "ids"],
    queryFn: () => api<number[]>("/offerings/favourites/ids"),
  });
  const favourites = useQuery({
    queryKey: ["offerings", "favourites", "list"],
    queryFn: () => api<Offering[]>("/offerings/favourites"),
    enabled: view === "favourites",
  });
  const topSelling = useQuery({
    queryKey: ["offerings", "top-selling"],
    queryFn: () => api<{ offering: Offering; sold: number }[]>(`/offerings/top-sellers${queryString({ days: TOP_DAYS, limit: 40 })}`),
    enabled: view === "top",
    staleTime: 60_000,
  });
  const isFavourite = useCallback((id: number) => favouriteIds.data?.includes(id) ?? false, [favouriteIds.data]);
  const toggleFavourite = useMutation({
    mutationFn: (o: Offering) => api(`/offerings/${o.id}/favourite`, { method: isFavourite(o.id) ? "DELETE" : "PUT" }),
    // Show the star at once; the lists catch up after.
    onMutate: (o) =>
      queryClient.setQueryData<number[]>(["offerings", "favourites", "ids"], (ids = []) =>
        ids.includes(o.id) ? ids.filter((x) => x !== o.id) : [...ids, o.id],
      ),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ["offerings", "favourites"] }),
  });

  const products = useQuery({
    queryKey: ["offerings", "pos", search.trim(), type, categoryId],
    queryFn: () =>
      api<Page<Offering>>(`/offerings${queryString({ q: search.trim(), type, categoryId, forSale: true, size: 200 })}`),
    placeholderData: (previous) => previous,
  });

  const quick = search.trim() === "" && view !== "all";
  const narrow = (list: Offering[]) =>
    list.filter((o) => (type === null || o.type === type) && (categoryId === null || o.categoryId === categoryId));
  const tiles = quick
    ? view === "favourites"
      ? { pending: favourites.isPending, error: favourites.error, items: narrow(favourites.data ?? []) }
      : { pending: topSelling.isPending, error: topSelling.error, items: narrow((topSelling.data ?? []).map((x) => x.offering)) }
    : { pending: products.isPending, error: products.error, items: products.data?.items ?? [] };
  const soldById = new Map((topSelling.data ?? []).map((x) => [x.offering.id, x.sold]));

  const discount = Number(discountValue) || 0;
  const totals = useMemo(
    () =>
      calculateTotals(
        cart.map((l) => ({
          unitPrice: unitPrice(l),
          quantity: l.quantity,
          hours: l.hours,
          tax: taxKey(l.offering, store.data),
          discountType: l.discountType ?? null,
          discountValue: l.discountValue ?? 0,
        })),
        discount > 0 ? discountType : null,
        discount,
      ),
    [cart, discount, discountType, store.data],
  );
  const itemCount = cart.reduce((n, l) => n + l.quantity, 0);

  const add = useCallback((offering: Offering) => {
    setNotice(null);
    const limit = available(offering);
    const held = heldElsewhere(offering.id);
    setCart((current) => {
      const existing = current.find((l) => l.offering.id === offering.id);
      const inCart = existing?.quantity ?? 0;
      if (inCart + 1 > limit) {
        const vars = { n: maxQuantity(offering), name: offering.name, held };
        setNotice(
          offering.type === "PACKAGE"
            ? held > 0
              ? translate("Only {n} {name} can be made from stock, {held} held in another cart", vars)
              : translate("Only {n} {name} can be made from stock", vars)
            : held > 0
              ? translate("Only {n} {name} in stock, {held} held in another cart", vars)
              : translate("Only {n} {name} in stock", vars),
        );
        return current;
      }
      return existing
        ? current.map((l) => (l.offering.id === offering.id ? { ...l, quantity: l.quantity + 1 } : l))
        : [...current, { offering, quantity: 1, hours: offering.pricing === "HOURLY" ? 1 : null, staffId: null }];
    });
  }, [available, heldElsewhere, setCart]);

  const updateLine = (offeringId: number, change: Partial<CartLine>) =>
    setCart((current) => current.map((l) => (l.offering.id === offeringId ? { ...l, ...change } : l)));

  const setQuantity = (offeringId: number, quantity: number) =>
    setCart((current) =>
      quantity <= 0
        ? current.filter((l) => l.offering.id !== offeringId)
        : current.map((l) =>
            l.offering.id === offeringId ? { ...l, quantity: Math.min(quantity, available(l.offering)) } : l,
          ),
    );

  /** Puts a cart's resumed hold back in the list unchanged, for anyone to finish. */
  const putBack = useCallback(
    (c: SaleCart) => {
      if (!c.hold) return;
      api(`/held-sales/${c.hold.id}/put-back`, { method: "POST" })
        .catch(() => {})
        .finally(() => queryClient.invalidateQueries({ queryKey: heldSalesKey }));
    },
    [queryClient],
  );

  /** Empties a cart (the current one by default), e.g. after it was paid. */
  const clearSale = useCallback(
    (index?: number) => {
      updateCart(() => emptyCart(), index);
      setNotice(null);
    },
    [updateCart],
  );

  /** A code from the camera: add what it belongs to, and say so in the scanner. */
  const onCameraCode = useCallback(
    async (code: string) => {
      try {
        const offering = await api<Offering>(`/offerings/barcode/${encodeURIComponent(code)}`);
        if (maxQuantity(offering) <= 0) {
          return { tone: "warn" as const, text: translate("{name} is out of stock", { name: offering.name }) };
        }
        add(offering);
        return { tone: "ok" as const, text: translate("Added {name}", { name: offering.name }) };
      } catch {
        return { tone: "warn" as const, text: translate("Nothing for sale with code {code}", { code }) };
      }
    },
    [add],
  );

  // Barcode scanners type the code and press Enter.
  async function onSearchEnter() {
    const term = search.trim();
    if (!term) return;
    try {
      add(await api<Offering>(`/offerings/barcode/${encodeURIComponent(term)}`));
      setSearch("");
    } catch (err) {
      const items = products.data?.items ?? [];
      if (items.length === 1) {
        add(items[0]);
        setSearch("");
      } else if (err instanceof ApiError && err.status === 404 && items.length === 0) {
        setNotice(translate("Nothing for sale matches “{term}”", { term }));
      }
    }
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      if (e.key === "/" && !["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName)) {
        e.preventDefault();
        searchRef.current?.focus();
      }
      // Alt+1, Alt+2, Alt+3 switch carts (by key position, so it works with any keyboard layout).
      const digit = /^Digit([1-9])$/.exec(e.code);
      if (e.altKey && digit && Number(digit[1]) <= CART_COUNT) {
        e.preventDefault();
        switchTo(Number(digit[1]) - 1);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [switchTo]);

  const checkout = useMutation({
    mutationFn: (args: { method: PaymentMethod; tenders?: CashTender[]; changeCurrency?: string; khqrCurrency?: string }) =>
      api<Order>("/orders", {
        method: "POST",
        json: {
          items: cart.map(linePayload),
          discountType: discount > 0 ? discountType : null,
          discountValue: discount > 0 ? discount : null,
          customerId: current.customer?.id ?? null,
          note: current.note.trim() || null,
          paymentMethod: args.method,
          tenders: args.tenders,
          changeCurrency: args.changeCurrency,
          khqrCurrency: args.khqrCurrency,
          heldSaleId: current.hold?.id ?? null,
          // The server refuses the sale if its total differs from what the cashier was shown.
          expectedTotal: Number(totals.total.toFixed(2)),
        },
      }),
    onError: async (err) => {
      if (!(err instanceof ApiError && err.code === "PRICES_CHANGED") || !store.data) return;
      // Prices moved since the cart was built: load today's and let the cashier check before charging.
      const index = active;
      const currency = store.data.currency;
      const fresh = await freshOfferings(cart.map((l) => l.offering.id)).catch(() => null);
      if (!fresh) return;
      const { changes } = refreshLines(cart, fresh, currency);
      updateCart((c) => ({ ...c, lines: refreshLines(c.lines, fresh, currency).lines }), index);
      setStage({ kind: "cart" });
      setNotice(
        translate("Prices changed, the cart now shows today's. Check the total with the customer and charge again.") +
          (changes.length ? ` ${changes.join("; ")}.` : ""),
      );
    },
    onSuccess: (order) => {
      queryClient.invalidateQueries({ queryKey: ["offerings"] });
      queryClient.invalidateQueries({ queryKey: ["orders"] });
      queryClient.invalidateQueries({ queryKey: heldSalesKey });
      if (order.status === "COMPLETED") {
        clearSale(active);
        setStage({ kind: "done", order });
      } else {
        setStage({ kind: "khqr", order, cart: active });
      }
    },
  });

  const onKhqrPaid = useCallback(
    (order: Order) => {
      // Clear the cart that was being paid, whichever is showing.
      setStage((s) => {
        clearSale(s.kind === "khqr" ? s.cart : undefined);
        return { kind: "done", order };
      });
      queryClient.invalidateQueries({ queryKey: ["orders"] });
    },
    [queryClient, clearSale],
  );
  const onKhqrClosed = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ["offerings"] });
    setStage({ kind: "cart" });
  }, [queryClient]);

  const holdSale = useMutation({
    mutationFn: ({ label, note }: { label: string; note: string }) =>
      api<HeldSale>(current.hold ? `/held-sales/${current.hold.id}` : "/held-sales", {
        method: current.hold ? "PUT" : "POST",
        json: {
          label: label || null,
          note: note || null,
          customerId: current.customer?.id ?? null,
          discountType: discount > 0 ? discountType : null,
          discountValue: discount > 0 ? discount : null,
          lines: cart.map(linePayload),
        },
      }),
    onSuccess: (h) => {
      setHolding(false);
      clearSale(active);
      setNotice(translate("Held as {holdNumber}. Find it under Held sales on any till in this branch.", { holdNumber: h.holdNumber }));
      queryClient.invalidateQueries({ queryKey: heldSalesKey });
      searchRef.current?.focus();
    },
  });

  const resumeHold = useMutation({
    mutationFn: async ({ hold, takeOver }: { hold: HeldSale; takeOver: boolean }) => {
      // Already open in one of this cashier's carts: just show it.
      const open = carts.findIndex((c) => c.hold?.id === hold.id);
      if (open >= 0) return { kind: "open" as const, open };
      const target = cartItems(current) === 0 && !current.hold ? active : carts.findIndex((c) => c.lines.length === 0 && !c.hold);
      if (target < 0) throw new Error(translate("All carts are in use. Finish, hold or clear one first."));
      const r = await api<ResumedHold>(`/held-sales/${hold.id}/resume${takeOver ? "?takeOver=true" : ""}`, { method: "POST" });
      const fresh = await freshOfferings(r.cart.lines.map((l) => l.offeringId));
      return { kind: "resumed" as const, r, target, fresh };
    },
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: heldSalesKey });
      setHeldOpen(false);
      if (res.kind === "open") {
        switchTo(res.open);
        return;
      }
      const { r, target, fresh } = res;
      const currency = store.data?.currency ?? "USD";
      // Rebuild the cart from the stored lines with today's catalog entries.
      const stored: CartLine[] = r.cart.lines.flatMap((l) => {
        const o = fresh.get(l.offeringId);
        const placeholder = { id: l.offeringId, name: l.name, price: l.catalogPrice ?? 0 } as Offering;
        return [
          {
            offering: o ?? placeholder,
            quantity: l.quantity,
            hours: l.hours,
            staffId: l.staffId,
            price: l.unitPrice,
            priceReason: l.priceReason ?? undefined,
            discountType: l.discountType,
            discountValue: l.discountValue ?? undefined,
          },
        ];
      });
      // Compare with the prices when held, so the cashier sees what moved.
      const asHeld = stored.map((l) => {
        const held = r.cart.lines.find((x) => x.offeringId === l.offering.id);
        return held?.catalogPrice != null ? { ...l, offering: { ...l.offering, price: held.catalogPrice } } : l;
      });
      const { lines, changes } = refreshLines(asHeld, fresh, currency);
      const h = r.hold;
      updateCart(
        () => ({
          lines,
          discountType: r.cart.discountType ?? "PERCENT",
          discountValue: r.cart.discountValue ? String(r.cart.discountValue) : "",
          customer: h.customerId ? { id: h.customerId, code: h.customerCode ?? "", name: h.customerName ?? "", phone: h.customerPhone } : null,
          note: h.note ?? "",
          hold: { id: h.id, number: h.holdNumber, label: h.label },
        }),
        target,
      );
      setSaved((s) => ({ ...s, active: target }));
      // The server's warnings in English: prices and availability are already in `changes`.
      const extra = r.warnings.filter((w) => !w.includes("price changed") && !w.includes("no longer for sale")).map((w) => translateServer(w));
      const all = [...changes, ...extra];
      const name = h.label ? `${h.holdNumber} (${h.label})` : h.holdNumber;
      setNotice(
        all.length
          ? translate("Resumed {name}. Changed since it was held: {changes}.", { name, changes: all.join("; ") })
          : translate("Resumed {name}.", { name }),
      );
    },
  });

  const canOverride = user.permissions.includes("PRICE_OVERRIDE");
  const canDiscount = user.permissions.includes("DISCOUNT_ITEMS");
  if (!store.data) return store.error ? <ErrorNote error={store.error} /> : <Spinner />;
  const settings = store.data;
  const discountInvalid = discountType === "PERCENT" && discount > 100;
  const hoursMissing = cart.some((l) => l.offering.pricing === "HOURLY" && !(l.hours && l.hours > 0));
  const blocked = cart.length === 0 || discountInvalid || hoursMissing;

  return (
    <div className="flex flex-col lg:h-screen lg:flex-row">
      {/* Products */}
      <section aria-label={t("Products")} className="flex min-h-0 min-w-0 flex-1 flex-col p-4 lg:p-5">
        {branch && (
          <p className="mb-2 text-xs text-muted">
            {t("Selling from")}{" "}<span className="font-medium text-text">{branch.name}</span>
            {sellingStorage ? ` / ${sellingStorage.name}` : t(" (no selling storage set up)")}
          </p>
        )}
        <div className="relative mb-3">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted" aria-hidden />
          <Input
            ref={searchRef}
            autoFocus
            placeholder={t("Search name or SKU, or scan a barcode  ( / )")}
            aria-label={t("Search the catalog")}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                onSearchEnter();
              }
            }}
            className="h-12 pr-14 pl-9 text-base"
          />
          <button
            type="button"
            onClick={() => setScanning(true)}
            className="absolute top-1/2 right-1.5 grid size-10 -translate-y-1/2 place-items-center rounded-lg text-muted hover:bg-surface-2 hover:text-text"
            aria-label={t("Scan with camera")}
            title={t("Scan with camera")}
          >
            <ScanLine className="size-5" />
          </button>
        </div>
        <div className="mb-3 flex flex-wrap gap-2">
        <div className="flex w-fit overflow-hidden rounded-lg border border-border bg-surface text-sm" role="group" aria-label={t("Show")}>
          {(
            [
              ["all", LayoutGrid, msg("All items")],
              ["favourites", Star, msg("Favourites")],
              ["top", TrendingUp, msg("Top selling")],
            ] as const
          ).map(([v, Icon, label]) => (
            <button
              key={v}
              onClick={() => setView(v)}
              aria-pressed={view === v}
              className={cx("flex h-9 items-center gap-1.5 px-3 font-medium", view === v ? "bg-primary text-on-primary" : "hover:bg-surface-2")}
            >
              <Icon className={cx("size-4", v === "favourites" && view === v && "fill-current")} aria-hidden />
              {t(label)}
              {v === "favourites" && (favouriteIds.data?.length ?? 0) > 0 && (
                <span className="text-xs tabular-nums opacity-80">{favouriteIds.data!.length}</span>
              )}
            </button>
          ))}
        </div>
        <div className="flex w-fit overflow-hidden rounded-lg border border-border bg-surface text-sm" role="group" aria-label={t("Type")}>
          {TYPE_FILTERS.map((x) => (
            <button
              key={x.label}
              onClick={() => setType(x.value)}
              aria-pressed={type === x.value}
              className={cx("h-9 px-4 font-medium", type === x.value ? "bg-primary text-on-primary" : "hover:bg-surface-2")}
            >
              {t(x.label)}
            </button>
          ))}
        </div>
        </div>
        <div className="mb-4 flex gap-2 overflow-x-auto pb-1" role="group" aria-label={t("Category")}>
          {[{ id: null as number | null, name: t("All") }, ...(categories.data ?? [])].map((c) => (
            <button
              key={c.id ?? "all"}
              onClick={() => setCategoryId(c.id)}
              aria-pressed={categoryId === c.id}
              className={cx(
                "h-9 shrink-0 rounded-full border px-4 text-sm font-medium",
                categoryId === c.id
                  ? "border-primary bg-primary text-on-primary"
                  : "border-border bg-surface text-text hover:bg-surface-2",
              )}
            >
              {c.name}
            </button>
          ))}
        </div>
        {notice && (
          <div role="status" className="mb-3 rounded-lg bg-warning-soft px-3 py-2 text-sm text-warning">
            {notice}
          </div>
        )}
        <div className="min-h-0 flex-1 overflow-y-auto">
          {quick && (
            <p className="mb-2 text-xs text-muted">
              {view === "favourites"
                ? t("Your favourites. Tap the star on any item to add or remove it.")
                : t("What sold most in this branch in the last {n} days (units sold, less returns).", { n: TOP_DAYS })}
            </p>
          )}
          {tiles.pending ? (
            <Spinner />
          ) : tiles.error ? (
            <ErrorNote error={tiles.error} />
          ) : tiles.items.length === 0 ? (
            <p className="p-8 text-center text-sm text-muted">
              {!quick
                ? t("Nothing found.")
                : view === "favourites"
                  ? t("No favourites yet. Tap the star on any item in All items to add it here.")
                  : t("No sales in the last {n} days yet.", { n: TOP_DAYS })}
            </p>
          ) : (
            <ul className="grid grid-cols-[repeat(auto-fill,minmax(140px,1fr))] gap-2 sm:grid-cols-[repeat(auto-fill,minmax(150px,1fr))]">
              {tiles.items.map((p, rank) => {
                const out = available(p) <= 0;
                const fav = isFavourite(p.id);
                return (
                  <li key={p.id} className="relative">
                    <button
                      onClick={() => add(p)}
                      disabled={out}
                      title={p.description ?? undefined}
                      className="flex h-full w-full flex-col items-start gap-1 overflow-hidden rounded-lg border border-border bg-surface p-2 text-left transition-colors hover:border-primary disabled:opacity-50 disabled:hover:border-border"
                    >
                      {/* Every tile has an image area, so all tiles line up; entries without a photo show their initial. */}
                      <span className="-mx-2 -mt-2 mb-0.5 grid aspect-[4/3] w-[calc(100%+1rem)] max-w-none place-items-center overflow-hidden bg-surface-2 text-xl font-semibold text-muted">
                        {p.hasImage ? (
                          // eslint-disable-next-line @next/next/no-img-element -- our own API image
                          <img src={offeringImageUrl(p)!} alt="" loading="lazy" className="size-full object-cover" />
                        ) : (
                          <span aria-hidden>{p.name.slice(0, 1).toUpperCase()}</span>
                        )}
                      </span>
                      <span className="min-h-8 w-full">
                        <span className="block truncate font-mono text-[10px] text-muted" title={p.barcode ? t("Barcode {barcode}", { barcode: p.barcode }) : undefined}>
                          {p.sku}
                        </span>
                        <span className="line-clamp-2 text-xs leading-snug font-medium">{p.name}</span>
                        {p.nameKm && (
                          <span lang="km" className="line-clamp-1 text-[11px] text-muted">
                            {p.nameKm}
                          </span>
                        )}
                      </span>
                      <span className="mt-auto flex w-full flex-wrap items-end justify-between gap-1">
                        <span className="text-sm font-semibold tabular-nums">
                          {money(p.price, settings.currency)}
                          {p.pricing === "HOURLY" && <span className="text-xs font-normal text-muted"> / h</span>}
                        </span>
                        <TileBadge offering={p} />
                      </span>
                      {quick && view === "top" && soldById.has(p.id) && (
                        <span className="text-[11px] text-muted tabular-nums">
                          #{rank + 1} · {t("{n} sold", { n: soldById.get(p.id) })}
                        </span>
                      )}
                    </button>
                    {/* A separate button over the picture's corner (buttons can't sit inside the tile's button). */}
                    <button
                      type="button"
                      onClick={() => toggleFavourite.mutate(p)}
                      aria-pressed={fav}
                      aria-label={fav ? t("Remove {name} from favourites", { name: p.name }) : t("Add {name} to favourites", { name: p.name })}
                      title={fav ? t("Remove from favourites") : t("Add to favourites")}
                      className={cx(
                        "absolute top-1 left-1 grid size-7 place-items-center rounded-full bg-surface/85 shadow-sm",
                        fav ? "text-amber-500" : "text-muted hover:text-amber-500",
                      )}
                    >
                      <Star className={cx("size-4", fav && "fill-current")} aria-hidden />
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </section>

      {/* Cart */}
      <aside
        aria-label={t("Current sale")}
        className="flex w-full flex-col border-t border-border bg-surface lg:h-screen lg:w-[380px] lg:border-t-0 lg:border-l"
      >
        <div className="flex gap-1 border-b border-border p-2">
        <div role="tablist" aria-label={t("Open sales")} className="grid min-w-0 flex-1 grid-cols-3 gap-1">
          {carts.map((c, i) => {
            const n = cartItems(c);
            const selected = i === active;
            return (
              <button
                key={i}
                role="tab"
                aria-selected={selected}
                title={t("Cart {n} (Alt+{n})", { n: i + 1 })}
                onClick={() => switchTo(i)}
                className={cx(
                  "flex min-w-0 flex-col items-start rounded-lg border px-2.5 py-1.5 text-left text-xs",
                  selected ? "border-primary bg-primary-soft" : "border-border hover:bg-surface-2",
                )}
              >
                <span className="flex w-full items-center gap-1.5 font-semibold">
                  <ShoppingCart className="size-3.5 shrink-0" aria-hidden />
                  {t("Cart {i}", { i: i + 1 })}
                  {n > 0 && !selected && <span className="ml-auto size-2 shrink-0 rounded-full bg-warning" aria-label={t("waiting")} />}
                </span>
                <span className="w-full truncate text-muted">{c.customer?.name ?? c.hold?.label ?? (n > 0 ? t("Walk-in") : t("Empty"))}</span>
                <span className="w-full truncate tabular-nums text-muted">
                  {c.hold ? c.hold.number : n > 0 ? n === 1 ? t("1 item") : t("{n} items", { n }) : " "}
                </span>
              </button>
            );
          })}
        </div>
        <button
          type="button"
          onClick={() => {
            resumeHold.reset();
            setHeldOpen(true);
          }}
          title={t("Held sales in this branch")}
          className="relative flex w-14 shrink-0 flex-col items-center justify-center gap-0.5 rounded-lg border border-border text-[11px] text-muted hover:bg-surface-2"
        >
          <Clock className="size-4" aria-hidden />
          {t("Held")}
          {(heldCount.data?.open ?? 0) > 0 && (
            <span className="absolute -top-1.5 -right-1.5 grid min-w-5 place-items-center rounded-full bg-warning px-1 text-[11px] font-semibold text-white tabular-nums">
              {heldCount.data!.open}
            </span>
          )}
        </button>
        </div>
        <div className="flex items-center gap-2 border-b border-border px-4 py-2">
          <CustomerPicker
            key={`${saved.key}-${active}`}
            label={t("Customer for cart {active}", { active: active + 1 })}
            value={current.customer}
            onChange={(customer) => updateCart((c) => ({ ...c, customer }))}
          />
          {itemCount > 0 && <Badge tone="primary">{itemCount}</Badge>}
          {cart.length > 0 && (
            <Button
              variant="secondary"
              size="sm"
              title={t("Park this sale to finish later, at any till")}
              disabled={hoursMissing || discountInvalid}
              onClick={() => {
                holdSale.reset();
                setHolding(true);
              }}
            >
              <Pause className="size-3.5" aria-hidden />{" "}{t("Hold")}
            </Button>
          )}
          {(cart.length > 0 || current.customer || current.note || current.hold) && (
            <Button
              variant="ghost"
              size="sm"
              title={current.hold ? t("Put {number} back in Held sales as it was", { number: current.hold.number }) : undefined}
              onClick={() => {
                if (current.hold) {
                  if (confirm(translate("Put {number} back in Held sales, without your changes?", { number: current.hold.number }))) {
                    putBack(current);
                    clearSale();
                  }
                } else if (cart.length === 0 || confirm(translate("Clear cart {active}?", { active: active + 1 }))) clearSale();
              }}
            >
              {current.hold ? t("Put back") : t("Clear")}
            </Button>
          )}
        </div>

        <div className="min-h-32 flex-1 overflow-y-auto">
          {cart.length === 0 ? (
            <p className="px-4 py-10 text-center text-sm text-muted">
              {t("Tap a product or service, or scan a barcode, to start a sale in cart {active}.", { active: active + 1 })}
              {carts.some((c, i) => i !== active && c.lines.length > 0) && (
                <span className="mt-1 block text-xs">{t("Other carts keep their items while you work here.")}</span>
              )}
            </p>
          ) : (
            <ul className="divide-y divide-border">
              {cart.map((line) => {
                const { offering, quantity } = line;
                const isService = offering.type === "SERVICE" || Boolean(offering.packageInfo?.containsServices);
                return (
                  <li key={offering.id} className="px-4 py-3">
                    <div className="flex items-center gap-3">
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-medium">{offering.name}</div>
                        {offering.nameKm && (
                          <div lang="km" className="truncate text-xs">
                            {offering.nameKm}
                          </div>
                        )}
                        <div className="text-xs text-muted tabular-nums">
                          {line.price != null && (
                            <span className="mr-1 line-through" title={t("Catalog price")}>
                              {money(offering.price, settings.currency)}
                            </span>
                          )}
                          {canOverride ? (
                            <button
                              type="button"
                              onClick={() => setRepricing(line)}
                              className={cx(
                                "rounded underline decoration-dotted underline-offset-2 hover:text-primary",
                                line.price != null && "font-medium text-warning",
                              )}
                              title={t("Change the price")}
                            >
                              {money(unitPrice(line), settings.currency)}
                            </button>
                          ) : (
                            money(unitPrice(line), settings.currency)
                          )}
                          {offering.pricing === "HOURLY" ? " / h" : offering.unit ? ` / ${offering.unit}` : ""}
                          {(() => {
                            const k = taxKey(offering, settings);
                            return k.rate === 0
                              ? settings.taxMode === "NONE" ? "" : ` · ${k.name}`
                              : ` · ${k.included ? "incl." : "+"} ${taxLabel(k)}`;
                          })()}
                        </div>
                        {line.price != null && <div className="truncate text-xs text-warning">{t("Price changed: {priceReason}", { priceReason: line.priceReason })}</div>}
                        {(canDiscount || line.discountValue) && (
                          <button
                            type="button"
                            disabled={!canDiscount}
                            onClick={() => setDiscounting(line)}
                            className={cx(
                              "mt-0.5 inline-flex items-center gap-1 rounded text-xs",
                              line.discountValue ? "font-medium text-success" : "text-muted hover:text-primary",
                            )}
                          >
                            <Tag className="size-3" aria-hidden />
                            {line.discountValue
                              ? `${line.discountType === "PERCENT" ? `${line.discountValue}% off` : `${money(line.discountValue, settings.currency)} off`}`
                              : t("Discount")}
                          </button>
                        )}
                      </div>
                      <div className="flex items-center gap-1">
                        <button
                          className="grid size-8 place-items-center rounded-md border border-border hover:bg-surface-2"
                          onClick={() => setQuantity(offering.id, quantity - 1)}
                          aria-label={quantity === 1 ? t("Remove {name}", { name: offering.name }) : t("One less {name}", { name: offering.name })}
                        >
                          {quantity === 1 ? <Trash2 className="size-4" /> : <Minus className="size-4" />}
                        </button>
                        <span className="w-8 text-center text-sm font-medium tabular-nums">{quantity}</span>
                        <button
                          className="grid size-8 place-items-center rounded-md border border-border hover:bg-surface-2 disabled:opacity-40"
                          onClick={() => setQuantity(offering.id, quantity + 1)}
                          disabled={quantity >= available(offering)}
                          aria-label={t("One more {name}", { name: offering.name })}
                        >
                          <Plus className="size-4" />
                        </button>
                      </div>
                      <div className="w-16 text-right text-sm font-medium tabular-nums">
                        {(() => {
                          const i = cart.indexOf(line);
                          const split = totals.lines[i];
                          const gross = lineTotal({ unitPrice: unitPrice(line), quantity, hours: line.hours });
                          return split && split.itemDiscount > 0 ? (
                            <>
                              <span className="block text-xs font-normal text-muted line-through">{money(gross, settings.currency)}</span>
                              {money(gross - split.itemDiscount, settings.currency)}
                            </>
                          ) : (
                            money(gross, settings.currency)
                          );
                        })()}
                      </div>
                    </div>
                    {isService && (
                      <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
                        {offering.pricing === "HOURLY" && (
                          <label className="flex items-center gap-1.5 text-muted">
                            {t("Hours")}
                            <Input
                              type="number"
                              min="0.25"
                              max="999"
                              step="0.25"
                              value={line.hours ?? ""}
                              onChange={(e) => updateLine(offering.id, { hours: e.target.value === "" ? null : Number(e.target.value) })}
                              invalid={!(line.hours && line.hours > 0)}
                              className="h-8 w-20 text-right text-xs tabular-nums"
                            />
                          </label>
                        )}
                        <label className="flex min-w-0 flex-1 items-center gap-1.5 text-muted">
                          {t("Done by")}
                          <Select
                            value={line.staffId ?? user.id}
                            onChange={(e) => {
                              const id = Number(e.target.value);
                              updateLine(offering.id, { staffId: id === user.id ? null : id });
                            }}
                            className="h-8 min-w-0 flex-1 text-xs"
                          >
                            {(staff.data ?? [{ id: user.id, fullName: user.fullName }]).map((s) => (
                              <option key={s.id} value={s.id}>
                                {s.fullName}
                              </option>
                            ))}
                          </Select>
                        </label>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </div>

        <div className="border-t border-border px-4 py-3">
          <Input
            aria-label={t("Note for cart {active}", { active: active + 1 })}
            placeholder={t("Order note (optional), e.g. table 4, pick up at 5pm")}
            value={current.note}
            maxLength={255}
            onChange={(e) => updateCart((c) => ({ ...c, note: e.target.value }))}
            className="mb-3 h-9 text-sm"
          />
          <div className="mb-3 flex items-center gap-2">
            <span className="text-sm text-muted">{t("Discount")}</span>
            <div className="ml-auto flex overflow-hidden rounded-lg border border-border" role="group" aria-label={t("Discount type")}>
              {(["PERCENT", "AMOUNT"] as const).map((x) => (
                <button
                  key={x}
                  onClick={() => setDiscountType(x)}
                  aria-pressed={discountType === x}
                  className={cx("h-9 w-10 text-sm", discountType === x ? "bg-primary text-on-primary" : "hover:bg-surface-2")}
                >
                  {x === "PERCENT" ? "%" : "$"}
                </button>
              ))}
            </div>
            <Input
              inputMode="decimal"
              placeholder="0"
              aria-label={t("Discount value")}
              value={discountValue}
              onChange={(e) => setDiscountValue(e.target.value.replace(/[^\d.]/g, ""))}
              className="h-9 w-20 text-right tabular-nums"
              invalid={discountInvalid}
            />
          </div>
          <dl className="space-y-1 text-sm">
            <TotalRow
              label={settings.taxMode === "EXCLUSIVE" ? t("Subtotal (before {taxLabel})", { taxLabel: settings.taxLabel }) : t("Subtotal")}
              value={money(totals.subtotal, settings.currency)}
            />
            {totals.itemDiscount > 0 && <TotalRow label={t("Item discounts")} value={`-${money(totals.itemDiscount, settings.currency)}`} />}
            {totals.discount > 0 && <TotalRow label={t("Order discount")} value={`-${money(totals.discount, settings.currency)}`} />}
            {settings.taxMode === "EXCLUSIVE" && totals.taxAdded > 0 && (
              <TotalRow label={t("Taxable amount")} value={money(totals.taxableAmount, settings.currency)} />
            )}
            {totals.taxes
              .filter((x) => !x.included && x.tax > 0)
              .map((x) => (
                <TotalRow key={`${x.rateId}-${x.name}-added`} label={taxLabel(x)} value={money(x.tax, settings.currency)} />
              ))}
          </dl>
          <div className="mt-2 flex items-baseline justify-between">
            <span className="font-semibold">{t("Total")}</span>
            <span className="text-right">
              <span className="block text-2xl font-semibold tabular-nums">{money(totals.total, settings.currency)}</span>
              {currencies.slice(1).map((c) => (
                <span key={c.code} className="block text-xs text-muted tabular-nums">
                  {amountIn(cashIn(totals.total, c), c)}
                </span>
              ))}
            </span>
          </div>
          {totals.taxIncluded > 0 && (
            <p className="mt-1 text-right text-xs text-muted tabular-nums">
              {t("Includes")}{" "}
              {totals.taxes
                .filter((x) => x.included && x.tax > 0)
                .map((x) => `${taxLabel(x)} ${money(x.tax, settings.currency)}`)
                .join(", ")}
            </p>
          )}
          {stage.kind === "cart" && checkout.error && (
            <div className="mt-3">
              <ErrorNote error={checkout.error} />
            </div>
          )}
          <div className="mt-3 grid grid-cols-2 gap-2">
            <Button
              size="lg"
              disabled={blocked}
              onClick={() => {
                checkout.reset();
                setStage({ kind: "cash" });
              }}
            >
              <Banknote className="size-5" aria-hidden />{" "}{t("Cash")}
            </Button>
            <Button
              size="lg"
              variant="secondary"
              disabled={blocked || totals.total <= 0}
              loading={checkout.isPending && stage.kind === "cart"}
              onClick={() => {
                checkout.reset();
                if (currencies.some((c) => c.code === "KHR") && currencies[0]?.code !== "KHR") {
                  setStage({ kind: "khqr-currency" });
                } else {
                  checkout.mutate({ method: "KHQR" });
                }
              }}
            >
              <QrCode className="size-5" aria-hidden /> KHQR
            </Button>
          </div>
        </div>
      </aside>

      <HoldDialog
        open={holding}
        defaultLabel={current.customer?.name ?? ""}
        defaultNote={current.note}
        holdNumber={current.hold?.number ?? null}
        submitting={holdSale.isPending}
        error={holdSale.error}
        onClose={() => setHolding(false)}
        onHold={(label, note) => holdSale.mutate({ label, note })}
      />

      <HeldSalesList
        open={heldOpen}
        onClose={() => setHeldOpen(false)}
        currency={settings.currency}
        canManage={user.permissions.includes("HOLDS_MANAGE")}
        onResume={(hold, takeOver) => resumeHold.mutate({ hold, takeOver })}
        resuming={resumeHold.isPending ? (resumeHold.variables?.hold.id ?? null) : null}
        resumeError={resumeHold.error}
      />

      <CameraScanner
        open={scanning}
        onClose={() => {
          setScanning(false);
          searchRef.current?.focus();
        }}
        onDetected={onCameraCode}
        continuous
        title={t("Scan items")}
      />

      {discounting && (
        <ItemDiscountDialog
          line={discounting}
          gross={lineTotal({ unitPrice: unitPrice(discounting), quantity: discounting.quantity, hours: discounting.hours })}
          currency={settings.currency}
          maxPct={user.role.superAdmin ? null : user.role.maxItemDiscountPct}
          onClose={() => setDiscounting(null)}
          onApply={(type, value) => {
            updateLine(discounting.offering.id, { discountType: type, discountValue: value });
            setDiscounting(null);
          }}
        />
      )}

      {repricing && (
        <PriceDialog
          line={repricing}
          currency={settings.currency}
          belowCost={user.permissions.includes("PRICE_BELOW_COST")}
          onClose={() => setRepricing(null)}
          onApply={(price, reason) => {
            updateLine(repricing.offering.id, { price, priceReason: reason });
            setRepricing(null);
          }}
        />
      )}

      <Modal open={stage.kind === "cash"} onClose={() => setStage({ kind: "cart" })} title={t("Cash payment")}>
        {currencies.length > 0 && (
          <CashPayment
            total={totals.total}
            currencies={currencies}
            submitting={checkout.isPending}
            error={checkout.error}
            onConfirm={(tenders, changeCurrency) => checkout.mutate({ method: "CASH", tenders, changeCurrency })}
          />
        )}
      </Modal>

      <Modal open={stage.kind === "khqr-currency"} onClose={() => setStage({ kind: "cart" })} title={t("KHQR in which currency?")}>
        <p className="mb-4 text-sm text-muted">{t("The customer's banking app pays the amount in the QR code.")}</p>
        <div className="grid grid-cols-2 gap-3">
          {currencies
            .filter((c) => c.code === "USD" || c.code === "KHR")
            .map((c) => (
              <Button
                key={c.code}
                size="lg"
                variant="secondary"
                loading={checkout.isPending && checkout.variables?.khqrCurrency === c.code}
                onClick={() => checkout.mutate({ method: "KHQR", khqrCurrency: c.code })}
                className="h-20 flex-col"
              >
                <span className="text-xs text-muted">{c.name}</span>
                <span className="text-lg tabular-nums">{amountIn(c.base ? totals.total : Math.round(totals.total * (c.rate ?? 1)), c)}</span>
              </Button>
            ))}
        </div>
        <div className="mt-3">
          <ErrorNote error={checkout.error} />
        </div>
      </Modal>

      <Modal open={stage.kind === "khqr"} onClose={() => {}} dismissable={false} title={t("Scan to pay")}>
        {stage.kind === "khqr" && (
          <KhqrPayment order={stage.order} store={settings} onPaid={onKhqrPaid} onClosed={onKhqrClosed} />
        )}
      </Modal>

      <Modal open={stage.kind === "done"} onClose={() => setStage({ kind: "cart" })} title={t("Sale complete")}>
        {stage.kind === "done" && (
          <div className="flex flex-col gap-4">
            {(stage.order.payments[0]?.changeAmount ?? 0) > 0 && (
              <div className="rounded-xl bg-success-soft px-4 py-3 text-center">
                <div className="text-sm text-success">{t("Change due")}</div>
                <div className="text-3xl font-semibold text-success tabular-nums">
                  {(() => {
                    const p = stage.order.payments[0];
                    const c = currencies.find((x) => x.code === p.changeCurrency);
                    return c ? amountIn(p.changeAmount!, c) : `${p.changeAmount} ${p.changeCurrency}`;
                  })()}
                </div>
              </div>
            )}
            <div className="print-area rounded-lg border border-border">
              <Receipt order={stage.order} store={settings} />
            </div>
            <div className="grid grid-cols-2 gap-2">
              <Button variant="secondary" onClick={() => window.print()}>
                <Printer className="size-4" aria-hidden />{" "}{t("Print receipt")}
              </Button>
              <Button
                autoFocus
                onClick={() => {
                  setStage({ kind: "cart" });
                  searchRef.current?.focus();
                }}
              >
                {t("New sale")}
              </Button>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}

function TotalRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between">
      <dt className="text-muted">{label}</dt>
      <dd className="tabular-nums">{value}</dd>
    </div>
  );
}

function TileBadge({ offering: o }: { offering: Offering }) {
  const t = useT();
  if (o.type === "PACKAGE") {
    const available = o.packageInfo?.available;
    if (available === null || available === undefined) return <Badge>{t("Package")}</Badge>;
    if (available <= 0) return <Badge tone="danger">{t("Out")}</Badge>;
    return <Badge tone={available <= 3 ? "warning" : "primary"}>{t("{available} available", { available })}</Badge>;
  }
  if (o.type === "SERVICE") {
    return o.durationMinutes ? <span className="text-xs text-muted">{duration(o.durationMinutes)}</span> : <Badge>{t("Service")}</Badge>;
  }
  const stock = o.stock ?? 0;
  if (stock <= 0) return <Badge tone="danger">{t("Out")}</Badge>;
  if (stock <= (o.lowStockThreshold ?? 0)) return <Badge tone="warning">{t("{stock} left", { stock })}</Badge>;
  return (
    <span className="text-xs text-muted tabular-nums">
      {stock}
      {o.unit ? ` ${o.unit}` : ""}
    </span>
  );
}

const REASONS = [msg("Damaged or near expiry"), msg("Price match"), msg("Bulk deal"), msg("Loyal customer"), msg("Wrong label price")];

/**
 * Changes one line's price. Needs "Override prices"; a reason is required and goes on the order
 * and the audit log. Below the item's cost needs "Sell below cost". The server checks it all again.
 */
function PriceDialog({
  line,
  currency,
  belowCost,
  onClose,
  onApply,
}: {
  line: CartLine;
  currency: string;
  belowCost: boolean;
  onClose: () => void;
  onApply: (price: number | null, reason: string) => void;
}) {
  const t = useT();
  const o = line.offering;
  const [price, setPrice] = useState(String(line.price ?? o.price));
  const [reason, setReason] = useState(line.priceReason ?? "");
  const value = Number(price);
  const valid = price.trim() !== "" && Number.isFinite(value) && value >= 0 && /^\d+(\.\d{1,2})?$/.test(price.trim());
  const underCost = valid && o.cost > 0 && value < o.cost;
  const unchanged = valid && value === o.price;
  const blocked = !valid || (underCost && !belowCost) || (!unchanged && reason.trim().length < 3);
  const per = o.pricing === "HOURLY" ? ` ${t("per hour")}` : o.unit ? ` ${t("per {unit}", { unit: o.unit })}` : "";

  return (
    <Modal open onClose={onClose} title={t("Change price: {name}", { name: o.name })}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (blocked) return;
          onApply(unchanged ? null : Math.round(value * 100) / 100, unchanged ? "" : reason.trim());
        }}
      >
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted">{t("Catalog price")}</dt>
          <dd className="text-right tabular-nums">
            {money(o.price, currency)}
            {per}
          </dd>
          {o.cost > 0 && (
            <>
              <dt className="text-muted">{t("Cost")}</dt>
              <dd className="text-right tabular-nums">{money(o.cost, currency)}</dd>
            </>
          )}
        </dl>
        <Field
          label={t("New price{per}", { per })}
          error={!valid && price !== "" ? t("Enter an amount, e.g. 4.50") : underCost && !belowCost ? t("Below cost: you need the “Sell below cost” permission") : undefined}
          hint={underCost && belowCost ? t("This is below cost.") : t("VAT and discounts apply to the new price as usual.")}
        >
          <Input autoFocus inputMode="decimal" value={price} onChange={(e) => setPrice(e.target.value.replace(/[^\d.]/g, ""))} />
        </Field>
        <Field label={t("Reason")} hint={t("Required. Saved on the order and in the audit log.")}>
          <Input value={reason} maxLength={255} onChange={(e) => setReason(e.target.value)} disabled={unchanged} />
        </Field>
        <div className="flex flex-wrap gap-1.5">
          {REASONS.map((r) => (
            <button
              key={r}
              type="button"
              disabled={unchanged}
              onClick={() => setReason(t(r))}
              className="rounded-full border border-border px-2.5 py-1 text-xs hover:bg-surface-2 disabled:opacity-40"
            >
              {t(r)}
            </button>
          ))}
        </div>
        <div className="flex justify-between gap-2">
          {line.price != null ? (
            <Button type="button" variant="ghost" onClick={() => onApply(null, "")}>
              {t("Use catalog price")}
            </Button>
          ) : (
            <span />
          )}
          <div className="flex gap-2">
            <Button type="button" variant="secondary" onClick={onClose}>
              {t("Cancel")}
            </Button>
            <Button type="submit" disabled={blocked}>
              {unchanged ? t("Keep catalog price") : t("Apply price")}
            </Button>
          </div>
        </div>
      </form>
    </Modal>
  );
}

/**
 * A % or amount off one cart line. Needs "Discount items"; the role's maximum % applies to both
 * kinds (an amount is checked as a % of the line). The order discount, if any, comes on top.
 */
function ItemDiscountDialog({
  line,
  gross,
  currency,
  maxPct,
  onClose,
  onApply,
}: {
  line: CartLine;
  gross: number;
  currency: string;
  maxPct: number | null;
  onClose: () => void;
  onApply: (type: DiscountType | null, value: number) => void;
}) {
  const t = useT();
  const [type, setType] = useState<DiscountType>(line.discountType ?? "PERCENT");
  const [value, setValue] = useState(line.discountValue ? String(line.discountValue) : "");
  const v = Number(value) || 0;
  const off = type === "PERCENT" ? Math.round(gross * Math.min(v, 100)) / 100 : Math.min(v, gross);
  // A % discount counts as the % entered; an amount as the % of the line it takes off (like the server).
  const pct = type === "PERCENT" ? v : gross > 0 ? (off / gross) * 100 : 0;
  const tooMuch = maxPct != null && pct > maxPct + 1e-9;
  const invalid = value !== "" && (!/^\d+(\.\d{1,2})?$/.test(value) || (type === "PERCENT" && v > 100));
  return (
    <Modal open onClose={onClose} title={t("Discount: {name}", { name: line.offering.name })}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (!invalid && !tooMuch) onApply(v > 0 ? type : null, v);
        }}
      >
        <p className="text-sm text-muted">
          {t("Line total {gross}", { gross: money(gross, currency) })}
          {maxPct != null && t(" · your role can take up to {maxPct}% off", { maxPct })}
        </p>
        <div className="flex gap-2">
          <div className="flex overflow-hidden rounded-lg border border-border" role="group" aria-label={t("Discount type")}>
            {(["PERCENT", "AMOUNT"] as const).map((x) => (
              <button
                key={x}
                type="button"
                onClick={() => setType(x)}
                aria-pressed={type === x}
                className={cx("h-10 w-12 text-sm", type === x ? "bg-primary text-on-primary" : "hover:bg-surface-2")}
              >
                {x === "PERCENT" ? "%" : "$"}
              </button>
            ))}
          </div>
          <Input
            autoFocus
            inputMode="decimal"
            aria-label={t("Discount")}
            placeholder="0"
            value={value}
            onChange={(e) => setValue(e.target.value.replace(/[^\d.]/g, ""))}
            invalid={invalid || tooMuch}
            className="h-10 flex-1 text-right tabular-nums"
          />
        </div>
        <div className="flex flex-wrap gap-1.5">
          {[5, 10, 15, 20, 50].filter((p) => maxPct == null || p <= maxPct).map((p) => (
            <button
              key={p}
              type="button"
              onClick={() => {
                setType("PERCENT");
                setValue(String(p));
              }}
              className="rounded-full border border-border px-2.5 py-1 text-xs hover:bg-surface-2"
            >
              {p}%
            </button>
          ))}
        </div>
        <p className={cx("text-sm", tooMuch ? "text-danger" : "")}>
          {tooMuch
            ? t("That is {Number}% off; your role allows up to {maxPct}%.", { Number: Number(pct.toFixed(1)), maxPct })
            : off > 0
              ? t("Takes {off} off ({Number}%); the line becomes {gross}. VAT is worked out after it.", { off: money(off, currency), Number: Number(pct.toFixed(1)), gross: money(gross - off, currency) })
              : t("No discount.")}
        </p>
        <div className="flex justify-between gap-2">
          {line.discountValue ? (
            <Button type="button" variant="ghost" onClick={() => onApply(null, 0)}>
              {t("Remove discount")}
            </Button>
          ) : (
            <span />
          )}
          <div className="flex gap-2">
            <Button type="button" variant="secondary" onClick={onClose}>
              {t("Cancel")}
            </Button>
            <Button type="submit" disabled={invalid || tooMuch}>
              {t("Apply")}
            </Button>
          </div>
        </div>
      </form>
    </Modal>
  );
}
