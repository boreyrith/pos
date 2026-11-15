"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Clock, XCircle } from "lucide-react";
import { useSearchParams } from "next/navigation";
import { QRCodeSVG } from "qrcode.react";
import { useEffect, useState, useSyncExternalStore } from "react";

import { CopyBox } from "@pos/shared/components/public/CopyBox";
import { Button, Card, ErrorNote, Spinner, cx } from "@pos/shared/components/ui";
import { dateTime, money } from "@pos/shared/utils/format";
import { useT } from "@pos/shared/i18n";

import { ShopHeader } from "@/components/ShopHeader";
import { shopApi, useShopStore, type OrderView } from "@/lib/shop";

/**
 * An order as its customer follows it: the KHQR code to pay (checked every few seconds), then each
 * step until it is collected or delivered. Reached by the account that ordered, or the private link.
 */
export function TrackView({ number }: { number: string }) {
  const t = useT();
  const key = useSearchParams().get("key");
  const query = key ? `?key=${encodeURIComponent(key)}` : "";
  const store = useShopStore().data;
  const queryClient = useQueryClient();
  const order = useQuery({
    queryKey: ["shop", "order", number, key],
    queryFn: () => shopApi<OrderView>(`/orders/${encodeURIComponent(number)}${query}`),
    // While it waits for payment, ask the server (which asks Bakong) every few seconds; then now and then.
    refetchInterval: (q) => (q.state.data?.orderStatus === "PENDING_PAYMENT" ? false : 30_000),
  });
  const pending = order.data?.orderStatus === "PENDING_PAYMENT";
  useEffect(() => {
    if (!pending) return;
    const id = setInterval(async () => {
      try {
        const v = await shopApi<OrderView>(`/orders/${encodeURIComponent(number)}/payment/check${query}`, { method: "POST" });
        queryClient.setQueryData(["shop", "order", number, key], v);
      } catch {
        // Try again at the next tick.
      }
    }, 4000);
    return () => clearInterval(id);
  }, [pending, number, query, key, queryClient]);
  const simulate = useMutation({
    mutationFn: () => shopApi<OrderView>(`/orders/${encodeURIComponent(number)}/payment/simulate${query}`, { method: "POST" }),
    onSuccess: (v) => queryClient.setQueryData(["shop", "order", number, key], v),
  });
  const cancel = useMutation({
    mutationFn: () => shopApi<OrderView>(`/orders/${encodeURIComponent(number)}/cancel${query}`, { method: "POST" }),
    onSuccess: (v) => queryClient.setQueryData(["shop", "order", number, key], v),
  });
  // This page's full address, for a guest to keep (only known in the browser).
  const link = useSyncExternalStore(noSubscribe, () => window.location.href, () => "");

  return (
    <>
      <ShopHeader />
      <main className="mx-auto max-w-2xl px-4 py-6">
        {order.isPending ? (
          <Spinner />
        ) : order.error ? (
          <ErrorNote error={order.error} />
        ) : (
          <div className="flex flex-col gap-4">
            <div>
              <p className="text-sm text-muted">{t("Order {number}", { number: order.data.orderNumber })} · {dateTime(order.data.createdAt)}</p>
              <h1 className="mt-1 text-2xl font-semibold">{headline(order.data, t)}</h1>
            </div>
            {pending && order.data.payment?.khqrPayload && (
              <Card className="flex flex-col items-center gap-3 p-6 text-center">
                <p className="font-medium">{t("Scan with your banking app to pay")}</p>
                <div className="rounded-2xl bg-white p-3">
                  <QRCodeSVG value={order.data.payment.khqrPayload} size={240} level="M" marginSize={1} />
                </div>
                <p className="text-2xl font-semibold">
                  {order.data.payment.currency && order.data.payment.currencyAmount != null && order.data.payment.currency !== order.data.currency
                    ? money(order.data.payment.currencyAmount, order.data.payment.currency)
                    : money(order.data.total, order.data.currency)}
                </p>
                {order.data.payment.expiresAt && <Countdown until={order.data.payment.expiresAt} />}
                <p className="text-xs text-muted">{t("This page updates by itself once the payment arrives.")}</p>
                {store?.khqrSimulated && (
                  <Button variant="secondary" size="sm" loading={simulate.isPending} onClick={() => simulate.mutate()}>
                    {t("Simulate payment (test mode)")}
                  </Button>
                )}
                <ErrorNote error={simulate.error ?? cancel.error} />
                <button
                  type="button"
                  className="text-sm text-muted hover:text-danger hover:underline disabled:opacity-50"
                  disabled={cancel.isPending}
                  onClick={() => confirm(t("Cancel this order? Nothing has been charged.")) && cancel.mutate()}
                >
                  {t("Cancel order")}
                </button>
              </Card>
            )}
            {!pending && <Steps order={order.data} />}
            {key && (
              <Card className="flex flex-col gap-2 border-primary/40 p-4">
                <p className="text-sm font-medium">{t("Keep this link to follow your order")}</p>
                {link && <CopyBox value={link} />}
                <p className="text-xs text-muted">
                  {t("This device remembers it, but on another phone you need the link. Anyone with it can see the order.")}
                </p>
              </Card>
            )}
            <Card className="flex flex-col gap-2 p-4 text-sm">
              <div className="font-medium">
                {order.data.fulfilment === "PICKUP"
                  ? t("Pick up at {branch}", { branch: order.data.branchName })
                  : t("Delivery to {address}", { address: order.data.address ?? "" })}
              </div>
              {order.data.fulfilment === "PICKUP" && order.data.branchAddress && <div className="text-muted">{order.data.branchAddress}</div>}
              <div className="text-muted">{order.data.contactName} · {order.data.contactPhone}</div>
              {order.data.note && <div className="text-muted">{order.data.note}</div>}
              <ul className="mt-2 divide-y divide-border border-t border-border">
                {order.data.items.map((i, n) => (
                  <li key={n} className="flex justify-between gap-2 py-1.5">
                    <span className="min-w-0">
                      {i.delivery ? t("Delivery") : `${i.quantity} × ${i.name}`}
                      {!i.delivery && i.nameKm && <span lang="km" className="block text-xs text-muted">{i.nameKm}</span>}
                    </span>
                    <span className="shrink-0">{money(i.lineTotal, order.data.currency)}</span>
                  </li>
                ))}
              </ul>
              {order.data.discount > 0 && (
                <div className="flex justify-between text-success">
                  <span>{t("Discounts")}</span>
                  <span>-{money(order.data.discount, order.data.currency)}</span>
                </div>
              )}
              {order.data.taxMode === "EXCLUSIVE" && order.data.taxAmount > 0 && (
                <div className="flex justify-between">
                  <span>{order.data.taxLabel}</span>
                  <span>{money(order.data.taxAmount, order.data.currency)}</span>
                </div>
              )}
              <div className="flex justify-between border-t border-border pt-2 text-base font-semibold">
                <span>{t("Total")}</span>
                <span>{money(order.data.total, order.data.currency)}</span>
              </div>
              {order.data.pointsEarned > 0 && (
                <p className="text-xs text-success">{t("You earned {n} points", { n: order.data.pointsEarned })}</p>
              )}
            </Card>
          </div>
        )}
      </main>
    </>
  );
}

const noSubscribe = () => () => {};

function headline(o: OrderView, t: ReturnType<typeof useT>) {
  if (o.orderStatus === "PENDING_PAYMENT") return t("Pay to confirm your order");
  if (o.orderStatus === "CANCELLED") return t("This order was cancelled");
  if (o.orderStatus === "REFUNDED") return t("This order was refunded");
  if (o.status === "DONE") return o.fulfilment === "PICKUP" ? t("Collected. Thank you!") : t("Delivered. Thank you!");
  if (o.status === "READY") return t("Ready to collect");
  if (o.status === "OUT_FOR_DELIVERY") return t("On its way");
  return t("Thank you! We're preparing your order");
}

/** Placed, paid, ready or on the way, done. */
function Steps({ order: o }: { order: OrderView }) {
  const t = useT();
  if (o.orderStatus === "CANCELLED") {
    return (
      <Card className="flex items-center gap-3 p-4 text-sm">
        <XCircle className="size-5 text-danger" aria-hidden />
        {t("The order was cancelled before it was paid. Nothing was charged.")}
      </Card>
    );
  }
  const reached = { NEW: 1, READY: 2, OUT_FOR_DELIVERY: 2, DONE: 3 }[o.status];
  const steps = [
    t("Paid"),
    o.fulfilment === "PICKUP" ? t("Ready to collect") : t("Out for delivery"),
    o.fulfilment === "PICKUP" ? t("Collected") : t("Delivered"),
  ];
  return (
    <Card className="p-4">
      <ol className="flex flex-col gap-3">
        {steps.map((label, i) => {
          const done = i < reached;
          return (
            <li key={label} className="flex items-center gap-3 text-sm">
              <span className={cx("grid size-7 place-items-center rounded-full", done ? "bg-success text-white" : "bg-surface-2 text-muted")}>
                {done ? <Check className="size-4" aria-hidden /> : <Clock className="size-4" aria-hidden />}
              </span>
              <span className={done ? "font-medium" : "text-muted"}>{label}</span>
            </li>
          );
        })}
      </ol>
    </Card>
  );
}

function Countdown({ until }: { until: string }) {
  const t = useT();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  const left = Math.max(0, Math.floor((new Date(until).getTime() - now) / 1000));
  return (
    <p className="text-sm text-muted">
      {t("Pay within {time}", { time: `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}` })}
    </p>
  );
}
