"use client";

import { useMutation, useQueries, useQuery } from "@tanstack/react-query";
import { Minus, Package, Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

import { Button, Card, ErrorNote, Field, Input, Spinner } from "@pos/shared/components/ui";
import { ApiError } from "@pos/shared/api/client";
import { money } from "@pos/shared/utils/format";
import { calculateTotals, taxKeyFor, taxLabel } from "@pos/shared/utils/pricing";
import { useT } from "@pos/shared/i18n";

import { FulfilmentPicker } from "@/components/FulfilmentPicker";
import { ShopHeader } from "@/components/ShopHeader";
import {
  cartBranch,
  deliveryCharged,
  imageUrl,
  rememberGuestOrder,
  setCart,
  shopApi,
  useAccount,
  useCart,
  useShopStore,
  type OrderView,
  type PromotionPreview,
  type ShopProduct,
} from "@/lib/shop";

/**
 * The cart and checkout. Totals are worked out as the server will (shared pricing.ts, promotions from
 * the server, delivery as the server adds it); the order is placed with that total, and refused if
 * prices changed meanwhile, when the cart reloads them.
 */
export function CartView() {
  const t = useT();
  const router = useRouter();
  const store = useShopStore();
  const account = useAccount().data;
  const cart = useCart();
  const branch = cartBranch(cart, store.data);
  const s = store.data;

  // Today's price and stock of each line, at this branch; lines no longer sold online are dropped.
  const fresh = useQueries({
    queries: cart.lines.map((l) => ({
      queryKey: ["shop", "product", l.product.id, branch],
      queryFn: () => shopApi<ShopProduct>(`/products/${l.product.id}?branchId=${branch}`),
      enabled: branch != null,
      retry: false,
    })),
  });
  const freshKey = fresh.map((f) => `${f.dataUpdatedAt}:${f.isError ? "x" : ""}`).join(",");
  useEffect(() => {
    setCart((c) => {
      const removed: string[] = [];
      const lines = c.lines
        .map((l) => {
          const f = fresh.find((q) => q.data?.id === l.product.id);
          const gone = fresh.some((q, i) => q.isError && c.lines[i]?.product.id === l.product.id);
          const max = f?.data ? Math.min(f.data.available ?? 50, 50) : 1;
          if (gone || max <= 0) {
            removed.push(l.product.name);
            return null;
          }
          return f?.data ? { product: f.data, quantity: Math.min(l.quantity, max) } : l;
        })
        .filter((l): l is NonNullable<typeof l> => l != null);
      return { ...c, lines, removed: removed.length ? [...c.removed, ...removed] : c.removed };
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- when any product is reloaded
  }, [freshKey]);

  const delivery = deliveryCharged(cart, s) && s?.delivery.product ? s.delivery.product : null;
  const promoBody = useMemo(
    () =>
      cart.lines.length === 0 || branch == null
        ? null
        : JSON.stringify({
            branchId: branch,
            fulfilment: cart.fulfilment,
            items: cart.lines.map((l) => ({ offeringId: l.product.id, quantity: l.quantity })),
            couponCode: cart.couponCode.trim() || null,
          }),
    [cart, branch],
  );
  const promos = useQuery({
    queryKey: ["shop", "promotions", promoBody],
    queryFn: () => shopApi<PromotionPreview>("/promotions", { method: "POST", json: JSON.parse(promoBody!) }),
    enabled: promoBody != null,
    placeholderData: (previous) => previous,
  });

  const totals = useMemo(() => {
    if (!s) return null;
    const products = [...cart.lines.map((l) => ({ p: l.product, q: l.quantity })), ...(delivery ? [{ p: delivery, q: 1 }] : [])];
    const lines = products.map(({ p, q }, i) => ({
      unitPrice: p.price,
      quantity: q,
      tax: taxKeyFor(p, s),
      promoDiscount: promos.data?.lines[i]?.promoDiscount ?? 0,
    }));
    return calculateTotals(lines, null, 0, promos.data?.orderDiscount ?? 0);
  }, [cart.lines, delivery, s, promos.data]);

  // Name and phone start as the account's until the customer changes them.
  const [edited, setForm] = useState<{ name: string | null; phone: string | null; address: string; note: string }>({
    name: null,
    phone: null,
    address: "",
    note: "",
  });
  const form = { ...edited, name: edited.name ?? account?.name ?? "", phone: edited.phone ?? account?.phone ?? "" };
  const place = useMutation({
    mutationFn: () =>
      shopApi<{ order: OrderView; accessKey: string | null }>("/orders", {
        method: "POST",
        json: {
          branchId: branch,
          fulfilment: cart.fulfilment,
          items: cart.lines.map((l) => ({ offeringId: l.product.id, quantity: l.quantity })),
          couponCode: cart.couponCode.trim() || null,
          contactName: form.name,
          contactPhone: form.phone,
          address: cart.fulfilment === "DELIVERY" ? form.address : null,
          note: form.note || null,
          expectedTotal: totals?.total,
        },
      }),
    onSuccess: (placed) => {
      if (placed.accessKey) rememberGuestOrder(placed.order.orderNumber, placed.accessKey);
      setCart((c) => ({ ...c, lines: [], couponCode: "", removed: [] }));
      router.push(`/track/${placed.order.orderNumber}${placed.accessKey ? `?key=${encodeURIComponent(placed.accessKey)}` : ""}`);
    },
    onError: (e) => {
      // Prices or promotions moved on: show today's and let the customer place it again.
      if (e instanceof ApiError && e.code === "PRICES_CHANGED") {
        fresh.forEach((f) => f.refetch());
        promos.refetch();
        store.refetch();
      }
    },
  });
  const pricesChanged = place.error instanceof ApiError && place.error.code === "PRICES_CHANGED";
  const ready = !!totals && !promos.isFetching && fresh.every((f) => !f.isFetching);
  const coupon = promos.data?.coupon;

  return (
    <>
      <ShopHeader />
      <main className="mx-auto max-w-5xl px-4 py-6">
        <h1 className="mb-4 text-2xl font-semibold">{t("Cart")}</h1>
        {cart.removed.length > 0 && (
          <div className="mb-4 flex items-start justify-between gap-3 rounded-lg bg-warning-soft px-3 py-2 text-sm text-warning" role="status">
            <span>{t("Not available here any more, so taken out of your cart: {items}", { items: cart.removed.join(", ") })}</span>
            <button className="shrink-0 font-medium hover:underline" onClick={() => setCart((c) => ({ ...c, removed: [] }))}>{t("Close")}</button>
          </div>
        )}
        {store.isPending ? (
          <Spinner />
        ) : !s ? (
          <ErrorNote error={store.error} />
        ) : cart.lines.length === 0 ? (
          <div className="py-16 text-center">
            <p className="text-muted">{t("Your cart is empty.")}</p>
            <Link href="/" className="mt-4 inline-block font-medium text-primary hover:underline">{t("Back to the shop")}</Link>
          </div>
        ) : (
          <div className="grid gap-6 lg:grid-cols-[1fr_380px]">
            <div className="flex flex-col gap-4">
              <FulfilmentPicker store={s} />
              <Card className="divide-y divide-border">
                {cart.lines.map((l, i) => {
                  const max = Math.min(l.product.available ?? 50, 50);
                  const promo = promos.data?.lines[i];
                  return (
                    <div key={l.product.id} className="flex gap-3 p-3">
                      <div className="size-16 shrink-0 overflow-hidden rounded-xl bg-surface-2">
                        {l.product.hasImage ? (
                          // eslint-disable-next-line @next/next/no-img-element -- served by our API
                          <img src={imageUrl(l.product)} alt="" className="size-full object-cover" />
                        ) : (
                          <span className="grid size-full place-items-center text-muted"><Package className="size-6" aria-hidden /></span>
                        )}
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="text-sm font-medium">{l.product.name}</div>
                        {l.product.nameKm && <div lang="km" className="text-xs text-muted">{l.product.nameKm}</div>}
                        <div className="text-sm text-muted">{money(l.product.price, s.currency)}</div>
                        {promo && promo.promoDiscount > 0 && (
                          <div className="text-xs text-success">{promo.promotionName}: -{money(promo.promoDiscount, s.currency)}</div>
                        )}
                      </div>
                      <div className="flex flex-col items-end gap-2">
                        <div className="flex items-center rounded-lg border border-border">
                          <button className="grid size-8 place-items-center disabled:opacity-40" aria-label={t("Fewer")}
                            onClick={() => setCart((c) => ({ ...c, lines: c.lines.map((x) => (x.product.id === l.product.id ? { ...x, quantity: x.quantity - 1 } : x)).filter((x) => x.quantity > 0) }))}>
                            <Minus className="size-3.5" aria-hidden />
                          </button>
                          <span className="w-8 text-center text-sm font-semibold tabular-nums">{l.quantity}</span>
                          <button className="grid size-8 place-items-center disabled:opacity-40" aria-label={t("More")} disabled={l.quantity >= max}
                            onClick={() => setCart((c) => ({ ...c, lines: c.lines.map((x) => (x.product.id === l.product.id ? { ...x, quantity: x.quantity + 1 } : x)) }))}>
                            <Plus className="size-3.5" aria-hidden />
                          </button>
                        </div>
                        <button className="text-muted hover:text-danger" aria-label={t("Remove {name}", { name: l.product.name })}
                          onClick={() => setCart((c) => ({ ...c, lines: c.lines.filter((x) => x.product.id !== l.product.id) }))}>
                          <Trash2 className="size-4" aria-hidden />
                        </button>
                      </div>
                    </div>
                  );
                })}
              </Card>
            </div>

            <form
              className="flex flex-col gap-4"
              onSubmit={(e) => {
                e.preventDefault();
                place.mutate();
              }}
            >
              <Card className="flex flex-col gap-2 p-4 text-sm">
                <Field label={t("Coupon code")}>
                  <Input value={cart.couponCode} onChange={(e) => setCart((c) => ({ ...c, couponCode: e.target.value }))} maxLength={40} />
                </Field>
                {coupon && cart.couponCode.trim() && (
                  <p className={coupon.applied ? "text-success" : "text-danger"}>{coupon.applied ? t("Coupon applied") : coupon.message}</p>
                )}
                {totals && (
                  <div className="mt-2 flex flex-col gap-1">
                    <Row label={t("Subtotal")} value={money(totals.subtotal, s.currency)} />
                    {totals.promoDiscount + totals.orderPromoDiscount > 0 && (
                      <Row label={t("Discounts")} value={`-${money(totals.promoDiscount + totals.orderPromoDiscount, s.currency)}`} tone="text-success" />
                    )}
                    {delivery && <Row label={t("Delivery")} value={money(delivery.price, s.currency)} />}
                    {totals.taxes.filter((x) => !x.included && x.tax > 0).map((x) => (
                      <Row key={x.name} label={taxLabel(x)} value={money(x.tax, s.currency)} />
                    ))}
                    <div className="mt-1 flex justify-between border-t border-border pt-2 text-base font-semibold">
                      <span>{t("Total")}</span>
                      <span>{money(totals.total, s.currency)}</span>
                    </div>
                    {totals.taxIncluded > 0 && <Row label={t("Includes {vat}", { vat: s.taxLabel })} value={money(totals.taxIncluded, s.currency)} />}
                  </div>
                )}
              </Card>

              <Card className="flex flex-col gap-3 p-4">
                <h2 className="text-sm font-semibold">{cart.fulfilment === "DELIVERY" ? t("Deliver to") : t("Your details")}</h2>
                <Field label={t("Name")}>
                  <Input required maxLength={100} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoComplete="name" />
                </Field>
                <Field label={t("Phone")}>
                  <Input required type="tel" maxLength={30} value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} autoComplete="tel" />
                </Field>
                {cart.fulfilment === "DELIVERY" && (
                  <Field label={t("Address")}>
                    <Input required maxLength={255} value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} autoComplete="street-address" />
                  </Field>
                )}
                <Field label={t("Note (optional)")}>
                  <Input maxLength={200} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
                </Field>
                {!account && (
                  <p className="text-xs text-muted">
                    {t("Ordering as a guest.")}{" "}
                    <Link href="/account?next=/cart" className="text-primary hover:underline">{t("Sign in to see your orders later")}</Link>
                  </p>
                )}
              </Card>

              {pricesChanged ? (
                <p className="rounded-lg bg-warning-soft px-3 py-2 text-sm text-warning">{t("Prices or stock changed. Check the cart and place the order again.")}</p>
              ) : (
                <ErrorNote error={place.error ?? promos.error} />
              )}
              <Button type="submit" size="lg" loading={place.isPending} disabled={!ready}>
                {t("Place order and pay by KHQR")}
              </Button>
              <p className="text-center text-xs text-muted">{t("Your items are held for you while you pay.")}</p>
            </form>
          </div>
        )}
      </main>
    </>
  );
}

function Row({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className={`flex justify-between gap-2 ${tone ?? ""}`}>
      <span className="min-w-0">{label}</span>
      <span className="shrink-0">{value}</span>
    </div>
  );
}
