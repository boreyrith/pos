/** The sell screen's carts: lines, the carts kept per person and branch, and refreshing prices. */
import type { PickedCustomer } from "@/components/customers/CustomerPicker";
import { api, queryString } from "@/lib/api";
import { money } from "@/lib/format";
import { taxKeyFor } from "@/lib/pricing";
import type { DiscountType, Offering, OfferingType, Order, Page, StoreSettings } from "@/lib/types";
import { msg, translate } from "@/lib/i18n";

/**
 * `hours` and `staffId` only apply to services; `staffId` null means the cashier did the work.
 * `price` is a changed price (needs "Override prices"), null for the catalog price.
 * `discountType`/`discountValue` are an item discount (needs "Discount items").
 */
export interface CartLine {
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
export const unitPrice = (l: CartLine) => l.price ?? l.offering.price;

/** The sell screen's quick views. */
export type SellView = "all" | "favourites" | "top";

/** Top selling products look back this many days. */
export const TOP_DAYS = 30;

export const TYPE_FILTERS: { value: OfferingType | null; label: string }[] = [
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
export const maxQuantity = (o: Offering) => (o.stock !== null ? o.stock : (o.packageInfo?.available ?? Infinity));

export type Stage =
  | { kind: "cart" }
  | { kind: "cash" }
  | { kind: "khqr-currency" }
  | { kind: "khqr"; order: Order; cart: number }
  | { kind: "done"; order: Order };

/** How many sales a cashier can keep open at once. */
export const CART_COUNT = 3;

/** One open sale: its lines, discount, who it is for and a note. Held carts keep all of it. */
export interface SaleCart {
  lines: CartLine[];
  discountType: DiscountType;
  discountValue: string;
  /** Who the sale is for; null is the Walk-in Customer. */
  customer: PickedCustomer | null;
  note: string;
  /** The held sale this cart was resumed from; paying completes it, holding again updates it. */
  hold?: { id: number; number: string; label?: string | null } | null;
  /** A KHQR order waiting to be paid for this cart; kept so a reload shows its QR code again. */
  pendingOrderId?: number | null;
  /** A coupon code the customer gave; the server checks it and works out what it takes off. */
  couponCode?: string | null;
  /** Loyalty points the customer wants to use on this sale. */
  redeemPoints?: number | null;
}

export const emptyCart = (): SaleCart => ({ lines: [], discountType: "PERCENT", discountValue: "", customer: null, note: "" });

/** Carts are kept per cashier and branch for the browser tab, so switching or reloading loses nothing. */
export const cartsKey = (userId: number, branchId: number | undefined) => `pos.carts.v5.${userId}.${branchId ?? "home"}`;

/** The current tax settings decide each line's tax (the server applies the same rules at checkout). */
export const taxKey = (o: Offering, store: StoreSettings | undefined) =>
  store ? taxKeyFor(o, store) : { rateId: o.taxRateId, name: o.effectiveTaxName, rate: o.effectiveTaxRate, included: o.includesTax };

export function loadCarts(key: string): { carts: SaleCart[]; active: number } {
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

export const cartItems = (c: SaleCart) => c.lines.reduce((n, l) => n + l.quantity, 0);

/** Today's catalog entries for these ids; anything missing is no longer for sale. */
export async function freshOfferings(ids: number[]): Promise<Map<number, Offering>> {
  if (ids.length === 0) return new Map();
  const page = await api<Page<Offering>>(`/offerings${queryString({ ids: [...new Set(ids)].join(","), forSale: true, size: 200 })}`);
  return new Map(page.items.map((o) => [o.id, o]));
}

/**
 * Brings cart lines up to date with the catalog: today's price and stock, and drops what can no
 * longer be sold. Returns what changed, to tell the cashier. Changed prices on a line are kept.
 */
export function refreshLines(lines: CartLine[], fresh: Map<number, Offering>, currency: string | undefined) {
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
export const linePayload = (l: CartLine) => ({
  offeringId: l.offering.id,
  quantity: l.quantity,
  hours: l.offering.pricing === "HOURLY" ? l.hours : null,
  staffId: l.offering.type === "SERVICE" || l.offering.packageInfo?.containsServices ? l.staffId : null,
  ...(l.price != null ? { unitPrice: l.price, priceReason: l.priceReason } : {}),
  ...(l.discountType && l.discountValue ? { discountType: l.discountType, discountValue: l.discountValue } : {}),
});
