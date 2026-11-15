"use client";

import { useQuery } from "@tanstack/react-query";
import { useSyncExternalStore } from "react";

import { ApiError, toError } from "@pos/shared/api/client";
import { msg } from "@pos/shared/i18n";
import type { OfferingType, OrderStatus, PromotionPreview, TaxMode } from "@pos/shared/types";

/** The shop's API (/api/shop, ShopController). Mirrors the backend's records. */

export type Fulfilment = "PICKUP" | "DELIVERY";
export type OnlineStatus = "NEW" | "READY" | "OUT_FOR_DELIVERY" | "DONE";

export interface ShopProduct {
  id: number;
  type: OfferingType;
  name: string;
  nameKm: string | null;
  description: string | null;
  hasImage: boolean;
  imageVersion: number;
  unit: string | null;
  categoryId: number | null;
  categoryName: string | null;
  price: number;
  taxRateId: number;
  taxRateName: string;
  taxRate: number;
  /** How many can be ordered; null when stock isn't counted. */
  available: number | null;
}

export interface ShopStore {
  name: string;
  hasLogo: boolean;
  logoVersion: number;
  currency: string;
  currencies: { code: string; symbol: string; decimals: number; rate: number; base: boolean }[];
  taxMode: TaxMode;
  taxLabel: string;
  taxScope: OfferingType[];
  defaultTaxRateId: number | null;
  timezone: string;
  open: boolean;
  pickupEnabled: boolean;
  pickupBranches: { id: number; name: string; address: string | null }[];
  delivery: { enabled: boolean; fee: number | null; freeFrom: number | null; branchId: number | null; product: ShopProduct | null };
  khqrSimulated: boolean;
}

export interface ProductPage {
  items: ShopProduct[];
  page: number;
  totalPages: number;
  totalItems: number;
}

export interface OrderView {
  orderNumber: string;
  orderStatus: OrderStatus;
  status: OnlineStatus;
  fulfilment: Fulfilment;
  createdAt: string;
  branchName: string;
  branchAddress: string | null;
  contactName: string;
  contactPhone: string;
  address: string | null;
  note: string | null;
  items: { name: string; nameKm: string | null; unit: string | null; quantity: number; unitPrice: number; lineTotal: number; discount: number; paid: number; delivery: boolean }[];
  subtotal: number;
  discount: number;
  taxAmount: number;
  taxIncluded: number;
  taxLabel: string;
  taxMode: TaxMode;
  total: number;
  refundedTotal: number;
  currency: string;
  couponCode: string | null;
  pointsEarned: number;
  payment: {
    status: "PENDING" | "PAID" | "EXPIRED" | "CANCELLED" | "REFUNDED";
    amount: number;
    currency: string | null;
    currencyAmount: number | null;
    khqrPayload: string | null;
    expiresAt: string | null;
    paidAt: string | null;
  } | null;
}

export interface Account {
  id: number;
  name: string;
  phone: string;
  /** Confirmed by the shop as this customer's (see CustomerAuthService); until then no in-store history. */
  verified: boolean;
}

export interface OrderSummary {
  orderNumber: string;
  orderStatus: OrderStatus;
  status: OnlineStatus;
  fulfilment: Fulfilment;
  createdAt: string;
  total: number;
  currency: string;
  items: number;
}

export type { PromotionPreview };

/** A request to the shop's API with this browser's shop cookie (never a staff sign-in). */
export async function shopApi<T>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const { json, ...rest } = init;
  let res: Response;
  try {
    res = await fetch(`/api/shop${path}`, {
      ...rest,
      credentials: "same-origin",
      headers: { ...(json !== undefined ? { "Content-Type": "application/json" } : {}), ...(rest.headers ?? {}) },
      body: json !== undefined ? JSON.stringify(json) : rest.body,
    });
  } catch {
    throw new ApiError(503, msg("Can't reach the shop right now. Check your connection and try again."));
  }
  if (!res.ok) throw await toError(res);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export function useShopStore() {
  return useQuery({ queryKey: ["shop", "store"], queryFn: () => shopApi<ShopStore>("/store"), staleTime: 60_000 });
}

/** The signed-in customer, or null. */
export function useAccount() {
  return useQuery({
    queryKey: ["shop", "account"],
    queryFn: async () => {
      try {
        return await shopApi<Account>("/account");
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null;
        throw e;
      }
    },
    staleTime: 60_000,
  });
}

export const imageUrl = (p: Pick<ShopProduct, "id" | "imageVersion">) => `/api/offerings/${p.id}/image?v=${p.imageVersion}`;

// ---------------------------------------------------------------- the cart, kept on this device

export interface CartLine {
  product: ShopProduct;
  quantity: number;
}

export interface Cart {
  fulfilment: Fulfilment;
  /** The pickup branch (delivery always comes from the store's delivery branch). */
  branchId: number | null;
  lines: CartLine[];
  couponCode: string;
  /** Items taken out when the cart was checked again (no longer sold online, or none left at the branch). */
  removed: string[];
}

const CART_KEY = "pos.shop.cart";
const EMPTY: Cart = { fulfilment: "PICKUP", branchId: null, lines: [], couponCode: "", removed: [] };
let cached: Cart | null = null;
const listeners = new Set<() => void>();

function read(): Cart {
  if (cached) return cached;
  try {
    const raw = localStorage.getItem(CART_KEY);
    cached = raw ? { ...EMPTY, ...(JSON.parse(raw) as Cart) } : EMPTY;
  } catch {
    cached = EMPTY;
  }
  return cached;
}

export function setCart(update: (c: Cart) => Cart) {
  cached = update(read());
  try {
    localStorage.setItem(CART_KEY, JSON.stringify(cached));
  } catch {
    // Private windows: the cart lasts for this page only.
  }
  listeners.forEach((l) => l());
}

export function useCart(): Cart {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      const onStorage = (e: StorageEvent) => {
        if (e.key === CART_KEY) {
          cached = null;
          l();
        }
      };
      window.addEventListener("storage", onStorage);
      return () => {
        listeners.delete(l);
        window.removeEventListener("storage", onStorage);
      };
    },
    read,
    () => EMPTY,
  );
}

/** Adds to the cart, never more than can be ordered. Returns false when nothing more can be added. */
export function addToCart(product: ShopProduct, quantity = 1): boolean {
  let added = false;
  setCart((c) => {
    const line = c.lines.find((l) => l.product.id === product.id);
    const max = Math.min(product.available ?? 50, 50);
    const want = Math.min((line?.quantity ?? 0) + quantity, max);
    added = want > (line?.quantity ?? 0);
    if (!added) return c;
    return {
      ...c,
      lines: line ? c.lines.map((l) => (l === line ? { product, quantity: want } : l)) : [...c.lines, { product, quantity: want }],
    };
  });
  return added;
}

/** The branch the cart is for: the pickup branch, or where deliveries come from. */
export function cartBranch(cart: Cart, store: ShopStore | undefined): number | null {
  if (!store) return null;
  if (cart.fulfilment === "DELIVERY" && store.delivery.enabled) return store.delivery.branchId;
  return cart.branchId && store.pickupBranches.some((b) => b.id === cart.branchId) ? cart.branchId : (store.pickupBranches[0]?.id ?? store.delivery.branchId);
}

/** Delivery is charged unless the goods (at list prices) reach the free-delivery amount; as the server decides. */
export function deliveryCharged(cart: Cart, store: ShopStore | undefined) {
  if (!store || cart.fulfilment !== "DELIVERY" || !store.delivery.enabled || !store.delivery.product) return false;
  const goods = cart.lines.reduce((s, l) => s + Math.round(l.product.price * 100) * l.quantity, 0) / 100;
  return store.delivery.freeFrom == null || goods < store.delivery.freeFrom;
}

// ---------------------------------------------------------------- guest orders placed on this device

const GUEST_KEY = "pos.shop.guestOrders";

/** A guest's order and the key of its private link, so this device can find it again. */
export function rememberGuestOrder(orderNumber: string, key: string) {
  try {
    const list = guestOrders().filter((o) => o.orderNumber !== orderNumber);
    localStorage.setItem(GUEST_KEY, JSON.stringify([{ orderNumber, key }, ...list].slice(0, 20)));
  } catch {
    // Private windows: the link in the address bar still works.
  }
}

export function guestOrders(): { orderNumber: string; key: string }[] {
  try {
    return JSON.parse(localStorage.getItem(GUEST_KEY) ?? "[]");
  } catch {
    return [];
  }
}
