import { beforeEach, describe, expect, it, vi } from "vitest";

import type { Cart, ShopProduct, ShopStore } from "./shop";

/** The cart lives in localStorage; each case starts with an empty one (and the module's copy reloaded). */
async function freshShop() {
  const items = new Map<string, string>();
  vi.stubGlobal("localStorage", {
    getItem: (k: string) => items.get(k) ?? null,
    setItem: (k: string, v: string) => items.set(k, v),
  });
  vi.resetModules();
  return { shop: await import("./shop"), items };
}

const product = (id: number, price: number, available: number | null): ShopProduct => ({
  id, type: "PRODUCT", name: `P${id}`, nameKm: null, description: null, hasImage: false, imageVersion: 0, unit: null,
  categoryId: null, categoryName: null, price, taxRateId: 1, taxRateName: "VAT", taxRate: 10, available,
});

const store = (over: Partial<ShopStore["delivery"]> = {}, branches = [1, 2]): ShopStore => ({
  name: "Shop", hasLogo: false, logoVersion: 0, currency: "USD", currencies: [], taxMode: "EXCLUSIVE", taxLabel: "VAT",
  taxScope: [], defaultTaxRateId: 1, timezone: "Asia/Phnom_Penh", open: true, pickupEnabled: true,
  pickupBranches: branches.map((id) => ({ id, name: `B${id}`, address: null })),
  delivery: { enabled: true, fee: 1.5, freeFrom: 20, branchId: 9, product: product(99, 1.5, null), ...over },
  khqrSimulated: false,
});

const cart = (over: Partial<Cart>): Cart => ({ fulfilment: "PICKUP", branchId: null, lines: [], couponCode: "", removed: [], ...over });

beforeEach(() => vi.unstubAllGlobals());

describe("addToCart", () => {
  it("adds up to what can be ordered, at most 50, and says when nothing more fits", async () => {
    const { shop, items } = await freshShop();
    expect(shop.addToCart(product(1, 2, 3), 2)).toBe(true);
    expect(shop.addToCart(product(1, 2, 3), 5)).toBe(true);
    expect(shop.addToCart(product(1, 2, 3))).toBe(false);
    expect(shop.addToCart(product(2, 1, null), 80)).toBe(true);
    const saved = JSON.parse(items.get("pos.shop.cart")!) as Cart;
    expect(saved.lines.map((l) => [l.product.id, l.quantity])).toEqual([[1, 3], [2, 50]]);
  });

  it("keeps the lines when the pickup branch changes (the cart checks them again there)", async () => {
    const { shop } = await freshShop();
    shop.addToCart(product(1, 2, 5));
    shop.setCart((c) => ({ ...c, branchId: 2 }));
    shop.setCart((c) => {
      expect(c.branchId).toBe(2);
      expect(c.lines).toHaveLength(1);
      return c;
    });
  });
});

describe("cartBranch", () => {
  it("is the chosen pickup branch, the first one if that isn't offered, and the delivery branch for delivery", async () => {
    const { shop } = await freshShop();
    expect(shop.cartBranch(cart({ branchId: 2 }), store())).toBe(2);
    expect(shop.cartBranch(cart({ branchId: 7 }), store())).toBe(1);
    expect(shop.cartBranch(cart({ fulfilment: "DELIVERY", branchId: 2 }), store())).toBe(9);
    expect(shop.cartBranch(cart({}), store({}, []))).toBe(9);
    expect(shop.cartBranch(cart({}), undefined)).toBeNull();
  });
});

describe("deliveryCharged", () => {
  it("charges delivery below the free-delivery amount, counted at list prices in cents", async () => {
    const { shop } = await freshShop();
    const lines = (price: number, quantity: number) => [{ product: product(1, price, null), quantity }];
    const delivery = (l: Cart["lines"]) => cart({ fulfilment: "DELIVERY", lines: l });
    expect(shop.deliveryCharged(delivery(lines(6.66, 3)), store())).toBe(true); // 19.98
    expect(shop.deliveryCharged(delivery(lines(0.1, 200)), store())).toBe(false); // exactly 20.00
    expect(shop.deliveryCharged(delivery(lines(100, 1)), store({ freeFrom: null }))).toBe(true);
    expect(shop.deliveryCharged(cart({ lines: lines(1, 1) }), store())).toBe(false); // pickup
    expect(shop.deliveryCharged(delivery(lines(1, 1)), store({ product: null }))).toBe(false);
  });
});
