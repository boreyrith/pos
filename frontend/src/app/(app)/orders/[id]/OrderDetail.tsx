"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Printer } from "lucide-react";
import Link from "next/link";
import { useCallback, useState } from "react";

import { KhqrPayment } from "@/components/pos/KhqrPayment";
import { Receipt } from "@/components/pos/Receipt";
import { StatusBadge } from "@/components/StatusBadge";
import { Badge, Button, Card, ErrorNote, Field, Input, Modal, Spinner } from "@/components/ui";
import { api } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { TYPE_LABELS, amountIn, dateTime, duration, hours, money, qty } from "@/lib/format";
import { refundShare, taxLabel } from "@/lib/pricing";
import { useCurrencies, useStoreSettings } from "@/lib/queries";
import type { Order } from "@/lib/types";
import { useT } from "@/lib/i18n";

export function OrderDetail({ id }: { id: number }) {
  const t = useT();
  const user = useUser();
  const store = useStoreSettings();
  const queryClient = useQueryClient();
  const [confirmRefund, setConfirmRefund] = useState(false);
  const [returning, setReturning] = useState(false);
  const [resumePayment, setResumePayment] = useState(false);

  const order = useQuery({ queryKey: ["orders", id], queryFn: () => api<Order>(`/orders/${id}`) });
  const currencies = useCurrencies().data ?? [];
  const showIn = (amount: number, code: string | null) => {
    const c = currencies.find((x) => x.code === code);
    return c ? amountIn(amount, c) : `${amount} ${code ?? ""}`;
  };

  const update = (o: Order) => {
    queryClient.setQueryData(["orders", id], o);
    queryClient.invalidateQueries({ queryKey: ["orders"] });
    queryClient.invalidateQueries({ queryKey: ["offerings"] });
  };

  const refund = useMutation({
    mutationFn: () => api<Order>(`/orders/${id}/refund`, { method: "POST" }),
    onSuccess: (o) => {
      update(o);
      setConfirmRefund(false);
    },
  });
  const cancel = useMutation({
    mutationFn: () => api<Order>(`/orders/${id}/cancel`, { method: "POST" }),
    onSuccess: update,
  });

  const onPaid = useCallback(
    (o: Order) => {
      queryClient.setQueryData(["orders", id], o);
      queryClient.invalidateQueries({ queryKey: ["orders"] });
      setResumePayment(false);
    },
    [queryClient, id],
  );
  const onClosed = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ["orders"] });
    queryClient.invalidateQueries({ queryKey: ["offerings"] });
    setResumePayment(false);
  }, [queryClient]);

  if (order.isPending || !store.data) return <Spinner />;
  if (order.error) return <div className="p-8"><ErrorNote error={order.error} /></div>;
  const o = order.data;
  const vat = o.taxLabel;
  const left = Math.round((o.total - o.refundedTotal) * 100) / 100;
  const returnable = (o.status === "COMPLETED" || o.status === "PARTIALLY_REFUNDED") && hasPermission(user, "ORDERS_REFUND");

  return (
    <div className="mx-auto max-w-5xl p-4 lg:p-8">
      <Link href="/orders" className="mb-4 inline-flex items-center gap-1 text-sm text-muted hover:text-text">
        <ArrowLeft className="size-4" aria-hidden />{" "}{t("Sales")}
      </Link>
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-semibold">{t("Order {orderNumber}", { orderNumber: o.orderNumber })}</h1>
        <StatusBadge status={o.status} />
        <div className="ml-auto flex gap-2">
          {o.status === "PENDING_PAYMENT" && hasPermission(user, "ORDERS_CANCEL", "SALES_SELL") && (
            <>
              <Button variant="secondary" onClick={() => cancel.mutate()} loading={cancel.isPending}>
                {t("Cancel order")}
              </Button>
              <Button onClick={() => setResumePayment(true)}>{t("Show KHQR")}</Button>
            </>
          )}
          {returnable && (
            <>
              <Button variant="secondary" onClick={() => setReturning(true)}>
                {t("Return items")}
              </Button>
              <Button variant="secondary" onClick={() => setConfirmRefund(true)}>
                {o.status === "PARTIALLY_REFUNDED" ? t("Refund the rest") : t("Refund all")}
              </Button>
            </>
          )}
          {o.status !== "PENDING_PAYMENT" && o.status !== "CANCELLED" && (
            <Button variant="secondary" onClick={() => window.print()}>
              <Printer className="size-4" aria-hidden />{" "}{t("Print receipt")}
            </Button>
          )}
        </div>
      </div>
      <ErrorNote error={cancel.error} />

      <div className="grid gap-5 lg:grid-cols-[1fr_340px]">
        <div className="flex flex-col gap-5">
          <Card className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b border-border text-left text-xs text-muted">
                <tr>
                  <th className="px-4 py-3 font-medium">{t("Item")}</th>
                  <th className="px-4 py-3 text-right font-medium">{t("Qty")}</th>
                  <th className="px-4 py-3 text-right font-medium">{t("Price")}</th>
                  <th className="px-4 py-3 text-right font-medium">{t("Total")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {o.items.map((i, index) => (
                  <tr key={index}>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2 font-medium">
                        {i.name}
                        {i.nameKm && (
                          <span lang="km" className="font-normal text-muted">
                            {i.nameKm}
                          </span>
                        )}
                        {i.type !== "PRODUCT" && <Badge>{t(TYPE_LABELS[i.type])}</Badge>}
                      </div>
                      <div className="text-xs text-muted">
                        {i.sku}
                        {i.staffName && t(" · done by {staffName}", { staffName: i.staffName })}
                        {i.durationMinutes != null && ` · ${duration(i.durationMinutes)}`}
                        {o.taxMode !== "NONE" &&
                          ` · ${i.taxRate === 0 ? i.taxName : `${taxLabel({ name: i.taxName, rate: i.taxRate })} ${i.priceIncludesTax ? "incl." : "added"}`}`}
                      </div>
                      {i.contents.length > 0 && (
                        <ul className="mt-1 text-xs text-muted">
                          {i.contents.map((c, j) => (
                            <li key={j} style={{ paddingLeft: `${c.depth * 12}px` }}>
                              {qty(c.quantity, c.unit)} {c.name}
                            </li>
                          ))}
                        </ul>
                      )}
                      {i.itemDiscount > 0 && (
                        <div className="text-xs text-success">
                          {t("Item discount")}
                          {i.itemDiscountType === "PERCENT" ? ` ${i.itemDiscountValue}%` : ""}: -{money(i.itemDiscount, o.currency)}
                        </div>
                      )}
                      {i.priceOverridden && (
                        <div className="text-xs text-warning">
                          {t("Price changed from {listPrice} by {priceOverrideBy}: {priceOverrideReason}", { listPrice: money(i.listPrice, o.currency), priceOverrideBy: i.priceOverrideBy, priceOverrideReason: i.priceOverrideReason })}
                        </div>
                      )}
                      {i.type !== "PACKAGE" && i.partsUsed.length > 0 && (
                        <div className="text-xs text-muted">
                          {t("Parts used: {join}", { join: i.partsUsed.map((p) => `${p.quantity} × ${p.itemName}`).join(", ") })}
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums">
                      {i.quantity}
                      {i.hours != null && <div className="text-xs text-muted">{hours(i.hours)}</div>}
                      {i.refundedQty > 0 && <div className="text-xs text-danger">{t("{refundedQty} returned", { refundedQty: i.refundedQty })}</div>}
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums">
                      {i.priceOverridden && <div className="text-xs text-muted line-through">{money(i.listPrice, o.currency)}</div>}
                      {money(i.unitPrice, o.currency)}
                      {i.hours != null && <span className="text-xs text-muted"> / h</span>}
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums">{money(i.lineTotal, o.currency)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot className="border-t border-border text-sm">
                <SumRow label={o.taxMode === "EXCLUSIVE" ? t("Subtotal (excl. {vat})", { vat }) : t("Subtotal")} value={money(o.subtotal, o.currency)} />
                {o.itemDiscount > 0 && <SumRow label={t("Item discounts")} value={`-${money(o.itemDiscount, o.currency)}`} />}
                {o.discountAmount > 0 && (
                  <SumRow
                    label={`${o.itemDiscount > 0 ? t("Order discount") : t("Discount")}${o.discountType === "PERCENT" ? ` (${o.discountValue}%)` : ""}`}
                    value={`-${money(o.discountAmount, o.currency)}`}
                  />
                )}
                {o.taxMode === "EXCLUSIVE" && o.taxAmount > 0 && (
                  <SumRow label={t("Taxable amount")} value={money(o.taxableAmount, o.currency)} />
                )}
                {o.taxes
                  .filter((x) => !x.included && x.tax > 0)
                  .map((x) => (
                    <SumRow key={`${x.name}-added`} label={t("{taxLabel} on {base}", { taxLabel: taxLabel(x), base: money(x.base, o.currency) })} value={money(x.tax, o.currency)} />
                  ))}
                <SumRow label={t("Total")} value={money(o.total, o.currency)} strong />
                {o.taxes
                  .filter((x) => x.included && x.tax > 0)
                  .map((x) => (
                    <SumRow key={`${x.name}-incl`} label={t("Includes {taxLabel} on {base}", { taxLabel: taxLabel(x), base: money(x.base, o.currency) })} value={money(x.tax, o.currency)} />
                  ))}
                {o.taxMode !== "NONE" && o.nonTaxableAmount > 0 && (
                  <SumRow label={t("Not taxed")} value={money(o.nonTaxableAmount, o.currency)} />
                )}
                {o.refundedTotal > 0 && (
                  <>
                    <SumRow
                      label={t("Refunded") + (o.refundedTax > 0 ? ` (${t("incl. {vat}", { vat })} ${money(o.refundedTax, o.currency)})` : "")}
                      value={`-${money(o.refundedTotal, o.currency)}`}
                    />
                    <SumRow label={t("Net")} value={money(left, o.currency)} strong />
                  </>
                )}
              </tfoot>
            </table>
          </Card>

          {o.returns.length > 0 && (
            <Card className="p-4">
              <h2 className="mb-3 text-sm font-semibold">{t("Returns")}</h2>
              <ul className="divide-y divide-border text-sm">
                {o.returns.map((r) => (
                  <li key={r.id} className="py-2">
                    <div className="flex flex-wrap items-center gap-3">
                      <span className="font-medium">{r.returnNumber}</span>
                      <span className="text-muted">
                        {dateTime(r.createdAt, store.data.timezone)} · {r.createdBy}
                      </span>
                      <span className="ml-auto tabular-nums">
                        -{money(r.total, o.currency)}
                        {r.tax > 0 && <span className="block text-right text-xs text-muted">{t("incl. {vat}", { vat })} {money(r.tax, o.currency)}</span>}
                      </span>
                    </div>
                    <div className="text-xs text-muted">
                      {r.lines.map((l) => `${l.quantity} × ${l.name}`).join(", ")}
                      {r.reason && ` · ${r.reason}`}
                    </div>
                  </li>
                ))}
              </ul>
            </Card>
          )}

          <Card className="p-4">
            <h2 className="mb-3 text-sm font-semibold">{t("Payments")}</h2>
            <ul className="divide-y divide-border text-sm">
              {o.payments.map((p) => (
                <li key={p.id} className="flex flex-wrap items-center gap-3 py-2">
                  <span className="font-medium">{p.method === "KHQR" ? "KHQR" : t("Cash")}</span>
                  <StatusBadge status={p.status} />
                  <span className="text-muted">{p.paidAt ? dateTime(p.paidAt, store.data.timezone) : ""}</span>
                  <span className="ml-auto tabular-nums">
                    {money(p.amount, o.currency)}
                    {p.method === "KHQR" && p.currency && p.currency !== o.currency && p.currencyAmount != null && (
                      <span className="text-muted">{" "}{t("(charged {showIn})", { showIn: showIn(p.currencyAmount, p.currency) })}</span>
                    )}
                    {p.method === "CASH" && p.tenders.length > 0 && (
                      <span className="block text-xs text-muted">
                        {t("Received {join}", { join: p.tenders.map((x) => showIn(x.amount, x.currency)).join(" + ") })}
                        {p.changeAmount ? t(" · change {showIn}", { showIn: showIn(p.changeAmount, p.changeCurrency) }) : ""}
                      </span>
                    )}
                  </span>
                </li>
              ))}
            </ul>
          </Card>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
            <dt className="text-muted">{t("Branch")}</dt>
            <dd>
              {o.branchName} <span className="text-muted">{t("· stock from {storageName}", { storageName: o.storageName })}</span>
            </dd>
            <dt className="text-muted">{t("Customer")}</dt>
            <dd>
              {o.walkIn ? (
                <span>
                  {t("Walk-in Customer")}{o.customerName && <span className="text-muted"> · {o.customerName}</span>}
                </span>
              ) : hasPermission(user, "CUSTOMERS_VIEW") ? (
                <Link href={`/customers/${o.customerId}`} className="text-primary hover:underline">
                  {o.customerName}
                </Link>
              ) : (
                o.customerName
              )}
              {!o.walkIn && o.customerPhone && <span className="text-muted"> · {o.customerPhone}</span>}
            </dd>
            <dt className="text-muted">{t("Cashier")}</dt>
            <dd>{o.cashierName}</dd>
            <dt className="text-muted">{t("Created")}</dt>
            <dd>{dateTime(o.createdAt, store.data.timezone)}</dd>
            {o.completedAt && (
              <>
                <dt className="text-muted">{t("Paid")}</dt>
                <dd>{dateTime(o.completedAt, store.data.timezone)}</dd>
              </>
            )}
            {o.note && (
              <>
                <dt className="text-muted">{t("Note")}</dt>
                <dd>{o.note}</dd>
              </>
            )}
          </dl>
        </div>

        <div>
          <h2 className="mb-2 text-sm font-semibold text-muted">{t("Receipt")}</h2>
          <div className="print-area rounded-lg border border-border">
            <Receipt order={o} store={store.data} />
          </div>
        </div>
      </div>

      <Modal
        open={confirmRefund}
        onClose={() => setConfirmRefund(false)}
        title={o.status === "PARTIALLY_REFUNDED" ? t("Refund the rest of this order?") : t("Refund this order?")}
      >
        <p className="mb-4 text-sm text-muted">
          {(() => {
            const amount =
              money(left, o.currency) +
              (o.taxAmount - o.refundedTax > 0
                ? ` (${t("incl. {vat}", { vat })} ${money(Math.round((o.taxAmount - o.refundedTax) * 100) / 100, o.currency)})`
                : "");
            const vars = { amount, place: `${o.branchName} / ${o.storageName}` };
            const partly = o.status === "PARTIALLY_REFUNDED";
            const khqr = o.payments.some((p) => p.method === "KHQR");
            return partly
              ? khqr
                ? t("This refunds the remaining {amount} and puts what is left of the products, items and parts used back into {place}. Give the customer their money back by bank transfer. This cannot be undone.", vars)
                : t("This refunds the remaining {amount} and puts what is left of the products, items and parts used back into {place}. Give the customer their money back in cash. This cannot be undone.", vars)
              : khqr
                ? t("This refunds the full {amount} and puts what is left of the products, items and parts used back into {place}. Give the customer their money back by bank transfer. This cannot be undone.", vars)
                : t("This refunds the full {amount} and puts what is left of the products, items and parts used back into {place}. Give the customer their money back in cash. This cannot be undone.", vars);
          })()}
        </p>
        <ErrorNote error={refund.error} />
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => setConfirmRefund(false)}>
            {t("Keep order")}
          </Button>
          <Button variant="danger" onClick={() => refund.mutate()} loading={refund.isPending}>
            {t("Refund {left}", { left: money(left, o.currency) })}
          </Button>
        </div>
      </Modal>

      {returning && <ReturnDialog order={o} onClose={() => setReturning(false)} onDone={(updated) => { update(updated); setReturning(false); }} />}

      <Modal open={resumePayment} onClose={() => {}} dismissable={false} title={t("Scan to pay")}>
        {resumePayment && <KhqrPayment order={o} store={store.data} onPaid={onPaid} onClosed={onClosed} />}
      </Modal>
    </div>
  );
}

function SumRow({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <tr>
      <td colSpan={3} className={`px-4 py-1.5 text-right ${strong ? "font-semibold" : "text-muted"}`}>
        {label}
      </td>
      <td className={`px-4 py-1.5 text-right tabular-nums ${strong ? "font-semibold" : ""}`}>{value}</td>
    </tr>
  );
}

/** Pick how many units of each line come back; the refund per line follows what was paid for it. */
function ReturnDialog({ order, onClose, onDone }: { order: Order; onClose: () => void; onDone: (o: Order) => void }) {
  const t = useT();
  const [qtys, setQtys] = useState<Record<number, string>>({});
  const [reason, setReason] = useState("");
  const lines = order.items.filter((i) => i.quantity - i.refundedQty > 0);
  const picked = lines
    .map((i) => ({ item: i, qty: Math.min(Number(qtys[i.id] || 0), i.quantity - i.refundedQty) }))
    .filter((p) => Number.isInteger(p.qty) && p.qty > 0);
  const shares = picked.map((p) => ({ ...p, ...refundShare(p.item, p.qty) }));
  const total = Math.round(shares.reduce((n, s) => n + s.amount * 100, 0)) / 100;
  const tax = Math.round(shares.reduce((n, s) => n + s.tax * 100, 0)) / 100;
  const invalid = lines.some((i) => {
    const v = qtys[i.id];
    return v !== undefined && v !== "" && (!Number.isInteger(Number(v)) || Number(v) < 0 || Number(v) > i.quantity - i.refundedQty);
  });

  const save = useMutation({
    mutationFn: () =>
      api<Order>(`/orders/${order.id}/returns`, {
        method: "POST",
        json: { lines: picked.map((p) => ({ orderItemId: p.item.id, quantity: p.qty })), reason: reason.trim() || null },
      }),
    onSuccess: onDone,
  });

  return (
    <Modal open onClose={onClose} title={t("Return items from {orderNumber}", { orderNumber: order.orderNumber })}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (picked.length > 0 && !invalid) save.mutate();
        }}
      >
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-muted">
            <tr>
              <th className="py-1 font-medium">{t("Item")}</th>
              <th className="py-1 pl-3 text-right font-medium">{t("Can return")}</th>
              <th className="py-1 pl-3 text-right font-medium">{t("Return")}</th>
              <th className="py-1 pl-3 text-right font-medium">{t("Refund")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {lines.map((i) => {
              const share = shares.find((s) => s.item.id === i.id);
              return (
                <tr key={i.id}>
                  <td className="py-2">
                    {i.name}
                    <div className="text-xs text-muted tabular-nums">
                      {t("Paid {linePaid} for {quantity}", { linePaid: money(i.linePaid, order.currency), quantity: i.quantity })}
                    </div>
                  </td>
                  <td className="py-2 pl-3 text-right tabular-nums">{i.quantity - i.refundedQty}</td>
                  <td className="w-20 py-2 pl-3 text-right">
                    <Input
                      inputMode="numeric"
                      aria-label={t("Units of {name} to return", { name: i.name })}
                      className="h-9 text-right tabular-nums"
                      value={qtys[i.id] ?? ""}
                      placeholder="0"
                      onChange={(e) => setQtys((q) => ({ ...q, [i.id]: e.target.value.replace(/\D/g, "") }))}
                      invalid={Number(qtys[i.id] || 0) > i.quantity - i.refundedQty}
                    />
                  </td>
                  <td className="w-24 py-2 pl-3 text-right tabular-nums">{share ? money(share.amount, order.currency) : "–"}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <Field label={t("Reason")} hint={t("Optional, e.g. damaged, wrong item")}>
          <Input value={reason} maxLength={255} onChange={(e) => setReason(e.target.value)} />
        </Field>
        <p className="text-sm">
          {t("Refund")}{" "}<span className="font-semibold tabular-nums">{money(total, order.currency)}</span>
          {tax > 0 && (
            <span className="text-muted tabular-nums">
              {" "}({t("incl. {vat}", { vat: order.taxLabel })} {money(tax, order.currency)})
            </span>
          )}
          . {t("Returned stock goes back into {storageName}.", { storageName: order.storageName })}
        </p>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" variant="danger" loading={save.isPending} disabled={picked.length === 0 || invalid}>
            {t("Refund {total}", { total: money(total, order.currency) })}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
