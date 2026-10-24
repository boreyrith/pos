"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { QRCodeSVG } from "qrcode.react";
import { useEffect, useState } from "react";

import { Button, ErrorNote } from "@/components/ui";
import { api } from "@/lib/api";
import { amountIn, money } from "@/lib/format";
import { useCurrencies } from "@/lib/queries";
import type { Order, Payment, StoreSettings } from "@/lib/types";
import { useT } from "@/lib/i18n";

/**
 * Shows the KHQR code for a pending order and polls the payment until it is paid, expires or is
 * cancelled. The server asks Bakong on each poll.
 */
export function KhqrPayment({
  order,
  store,
  onPaid,
  onClosed,
}: {
  order: Order;
  store: StoreSettings;
  onPaid: (order: Order) => void;
  onClosed: () => void;
}) {
  const t = useT();
  const initial = order.payments.find((p) => p.method === "KHQR" && p.status === "PENDING");
  const [now, setNow] = useState(() => Date.now());
  const currencies = useCurrencies().data ?? [];

  const payment = useQuery({
    queryKey: ["payment", initial?.id],
    queryFn: () => api<Payment>(`/payments/${initial!.id}`),
    enabled: !!initial,
    initialData: initial,
    refetchInterval: (q) => (q.state.data?.status === "PENDING" ? 2500 : false),
  });
  const status = payment.data?.status;

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    if (status === "PAID") {
      api<Order>(`/orders/${order.id}`).then(onPaid);
    }
  }, [status, order.id, onPaid]);

  const cancel = useMutation({
    mutationFn: () => api<Order>(`/orders/${order.id}/cancel`, { method: "POST" }),
    onSuccess: onClosed,
  });
  const simulate = useMutation({
    mutationFn: () => api<Payment>(`/payments/${initial!.id}/simulate-paid`, { method: "POST" }),
    onSuccess: () => payment.refetch(),
  });

  if (!initial || !payment.data) return <ErrorNote error={new Error(t("This order has no pending KHQR payment."))} />;

  const expiresAt = payment.data.expiresAt ? new Date(payment.data.expiresAt).getTime() : now;
  const secondsLeft = Math.max(0, Math.round((expiresAt - now) / 1000));
  const closed = status === "EXPIRED" || status === "CANCELLED";

  return (
    <div className="flex flex-col items-center gap-4 text-center">
      <div>
        {(() => {
          const charged = currencies.find((c) => c.code === initial?.currency);
          return charged && !charged.base && initial?.currencyAmount != null ? (
            <>
              <div className="text-3xl font-semibold tabular-nums">{amountIn(initial.currencyAmount, charged)}</div>
              <div className="text-sm text-muted">
                {money(order.total, order.currency)} · {t("Order {number}", { number: order.orderNumber })}
              </div>
            </>
          ) : (
            <>
              <div className="text-3xl font-semibold tabular-nums">{money(order.total, order.currency)}</div>
              <div className="text-sm text-muted">{t("Order {number}", { number: order.orderNumber })}</div>
            </>
          );
        })()}
      </div>

      {closed ? (
        <div className="rounded-xl bg-surface-2 px-6 py-10 text-sm text-muted">
          {status === "EXPIRED" ? t("This QR code expired. The items were returned to stock.") : t("Payment cancelled.")}
        </div>
      ) : (
        <>
          <div className="rounded-2xl border-4 border-[#e1232e] bg-white p-3">
            <div className="mb-2 text-xs font-bold tracking-widest text-[#e1232e]">KHQR</div>
            {initial.khqrPayload && <QRCodeSVG value={initial.khqrPayload} size={224} level="M" marginSize={1} />}
          </div>
          <p className="text-sm text-muted" aria-live="polite">
            {status === "PAID"
              ? t("Payment received")
              : t("Ask the customer to scan with any Bakong-member banking app. Expires in {time}", {
                  time: `${Math.floor(secondsLeft / 60)}:${String(secondsLeft % 60).padStart(2, "0")}`,
                })}
          </p>
        </>
      )}

      <ErrorNote error={cancel.error ?? simulate.error} />

      <div className="flex w-full flex-col gap-2">
        {!closed && store.khqrSimulated && (
          <Button variant="secondary" onClick={() => simulate.mutate()} loading={simulate.isPending}>
            {t("Simulate customer payment (test mode)")}
          </Button>
        )}
        {closed ? (
          <Button onClick={onClosed}>{t("Back to the cart")}</Button>
        ) : (
          <Button variant="ghost" onClick={() => cancel.mutate()} loading={cancel.isPending} disabled={status === "PAID"}>
            {t("Cancel payment")}
          </Button>
        )}
      </div>
    </div>
  );
}
