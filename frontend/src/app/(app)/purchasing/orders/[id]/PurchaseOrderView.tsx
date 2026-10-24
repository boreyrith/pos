"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, PackageCheck } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { Badge, Button, Card, ErrorNote, Field, Input, Modal, Spinner } from "@/components/ui";
import { api } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { dateTime, money, shortDate } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { PurchaseOrder, VendorInvoice } from "@/lib/types";
import { translate, useT } from "@/lib/i18n";

import { PurchaseOrderDialog } from "../../PurchaseOrderDialog";
import { InvoiceBadge, InvoiceDialog, PO_STATUS, PaymentDialog, useRefreshPurchasing } from "../../shared";

export function PurchaseOrderView({ id }: { id: number }) {
  const t = useT();
  const can = useCan();
  const store = useStoreSettings();
  const queryClient = useQueryClient();
  const refresh = useRefreshPurchasing();
  const order = useQuery({ queryKey: ["purchase-orders", id], queryFn: () => api<PurchaseOrder>(`/purchase-orders/${id}`) });
  const [editing, setEditing] = useState(false);
  const [receiving, setReceiving] = useState(false);
  const [invoicing, setInvoicing] = useState(false);
  const [paying, setPaying] = useState<VendorInvoice | null>(null);
  const act = useMutation({
    mutationFn: (action: "place" | "cancel" | "close") => api<PurchaseOrder>(`/purchase-orders/${id}/${action}`, { method: "POST" }),
    onSuccess: (po) => {
      queryClient.setQueryData(["purchase-orders", id], po);
      refresh();
    },
  });

  if (order.isPending || !store.data) return <Spinner />;
  if (order.error) return <div className="p-8"><ErrorNote error={order.error} /></div>;
  const po = order.data;
  const currency = store.data.currency;
  const tz = store.data.timezone;
  const s = PO_STATUS[po.status];
  const receivable = po.status === "ORDERED" || po.status === "PARTIALLY_RECEIVED";

  return (
    <div className="mx-auto max-w-5xl p-4 lg:p-8">
      <Link href="/purchasing" className="mb-4 inline-flex items-center gap-1 text-sm text-muted hover:text-text">
        <ArrowLeft className="size-4" aria-hidden />{" "}{t("Purchasing")}
      </Link>
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-semibold">{po.poNumber}</h1>
        <Badge tone={s.tone}>{t(s.label)}</Badge>
        <span className="text-sm text-muted">{po.vendorName}</span>
        <div className="ml-auto flex flex-wrap gap-2">
          {po.status === "DRAFT" && can("PURCHASE_ORDER") && (
            <>
              <Button variant="secondary" onClick={() => setEditing(true)}>
                {t("Edit")}
              </Button>
              <Button
                onClick={() => {
                  if (confirm(translate("Place {poNumber} with {vendorName} for {total}?", { poNumber: po.poNumber, vendorName: po.vendorName, total: money(po.total, currency) }))) act.mutate("place");
                }}
                loading={act.isPending && act.variables === "place"}
              >
                {t("Place order")}
              </Button>
            </>
          )}
          {receivable && can("PURCHASE_RECEIVE") && (
            <Button onClick={() => setReceiving(true)}>
              <PackageCheck className="size-4" aria-hidden />{" "}{t("Receive delivery")}
            </Button>
          )}
          {(po.status === "DRAFT" || po.status === "ORDERED") && can("PURCHASE_ORDER") && (
            <Button
              variant="ghost"
              onClick={() => {
                if (confirm(translate("Cancel {poNumber}?", { poNumber: po.poNumber }))) act.mutate("cancel");
              }}
            >
              {t("Cancel order")}
            </Button>
          )}
          {po.status === "PARTIALLY_RECEIVED" && can("PURCHASE_ORDER") && (
            <Button
              variant="ghost"
              onClick={() => {
                if (confirm(translate("Stop waiting for the rest of this order? What hasn't arrived won't be received."))) act.mutate("close");
              }}
            >
              {t("Close short")}
            </Button>
          )}
          {po.status !== "DRAFT" && po.status !== "CANCELLED" && can("PURCHASE_INVOICE") && (
            <Button variant="secondary" onClick={() => setInvoicing(true)}>
              {t("Record invoice")}
            </Button>
          )}
        </div>
      </div>
      <ErrorNote error={act.error} />

      <div className="grid gap-5 lg:grid-cols-[1fr_300px]">
        <div className="flex flex-col gap-5">
          <Card className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b border-border text-left text-xs text-muted">
                <tr>
                  <th className="px-4 py-3 font-medium">{t("Item")}</th>
                  <th className="px-4 py-3 text-right font-medium">{t("Ordered")}</th>
                  <th className="px-4 py-3 text-right font-medium">{t("Received")}</th>
                  <th className="px-4 py-3 text-right font-medium">{t("Cost")}</th>
                  <th className="px-4 py-3 text-right font-medium">{t("Total")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {po.lines.map((l) => (
                  <tr key={l.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium">{l.name}</div>
                      <div className="font-mono text-xs text-muted">
                        {l.sku}
                        {l.factor > 1 && t(" · 1 {unit} = {factor} base units", { unit: l.unit, factor: l.factor })}
                      </div>
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums">
                      {l.quantity} {l.unit}
                    </td>
                    <td className={`px-4 py-3 text-right tabular-nums ${l.remaining > 0 && po.status !== "DRAFT" ? "text-warning" : ""}`}>
                      {l.receivedQty}
                      {l.remaining > 0 && po.status !== "DRAFT" && po.status !== "CANCELLED" && (
                        <div className="text-xs">{t("{remaining} to come", { remaining: l.remaining })}</div>
                      )}
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums">{money(l.unitCost, currency)}</td>
                    <td className="px-4 py-3 text-right tabular-nums">{money(l.lineTotal, currency)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot className="border-t border-border">
                <tr>
                  <td colSpan={4} className="px-4 py-2 text-right font-semibold">
                    {t("Total")}
                  </td>
                  <td className="px-4 py-2 text-right font-semibold tabular-nums">{money(po.total, currency)}</td>
                </tr>
                {po.receivedValue > 0 && po.receivedValue !== po.total && (
                  <tr>
                    <td colSpan={4} className="px-4 py-1 text-right text-muted">
                      {t("Received so far")}
                    </td>
                    <td className="px-4 py-1 text-right tabular-nums">{money(po.receivedValue, currency)}</td>
                  </tr>
                )}
              </tfoot>
            </table>
          </Card>

          <Card className="p-4">
            <h2 className="mb-3 text-sm font-semibold">{t("Deliveries")}</h2>
            {po.receipts.length === 0 ? (
              <p className="text-sm text-muted">{t("Nothing received yet.")}</p>
            ) : (
              <ul className="divide-y divide-border text-sm">
                {po.receipts.map((r) => (
                  <li key={r.id} className="py-2">
                    <div className="flex flex-wrap gap-x-3">
                      <span className="font-medium">{r.receiptNumber}</span>
                      <span className="text-muted">
                        {t("{receivedAt} · {receivedBy} · into {storageName}", { receivedAt: dateTime(r.receivedAt, tz), receivedBy: r.receivedBy, storageName: r.storageName })}
                      </span>
                      {r.deliveryRef && <span className="text-muted">{t("delivery note {deliveryRef}", { deliveryRef: r.deliveryRef })}</span>}
                    </div>
                    <div className="text-xs text-muted">
                      {r.lines.map((l) => `${l.quantity} ${l.unit ?? ""} ${l.name}`.replace(/\s+/g, " ")).join(", ")}
                      {r.note && ` · ${r.note}`}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Card>

          <Card className="p-4">
            <h2 className="mb-3 text-sm font-semibold">{t("Vendor invoices")}</h2>
            {po.invoices.length === 0 ? (
              <p className="text-sm text-muted">{t("No invoice recorded for this order.")}</p>
            ) : (
              <ul className="divide-y divide-border text-sm">
                {po.invoices.map((i) => (
                  <li key={i.id} className="flex flex-wrap items-center gap-3 py-2">
                    <span className="font-medium">{i.invoiceNumber}</span>
                    <InvoiceBadge invoice={i} />
                    <span className="text-muted">{t("due {dueDate}", { dueDate: shortDate(i.dueDate) })}</span>
                    <span className="ml-auto text-right tabular-nums">
                      {money(i.total, currency)}
                      {i.balance > 0 && i.status !== "VOID" && <span className="block text-xs text-muted">{t("owed {balance}", { balance: money(i.balance, currency) })}</span>}
                    </span>
                    {can("PURCHASE_PAY") && i.balance > 0 && (
                      <Button size="sm" onClick={() => setPaying(i)}>
                        {t("Pay")}
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>

        <dl className="grid h-fit grid-cols-2 gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted">{t("Vendor")}</dt>
          <dd>{po.vendorName}</dd>
          <dt className="text-muted">{t("Deliver to")}</dt>
          <dd>
            {po.branchName} / {po.storageName}
          </dd>
          {po.expectedDate && (
            <>
              <dt className="text-muted">{t("Expected")}</dt>
              <dd>{shortDate(po.expectedDate)}</dd>
            </>
          )}
          {po.vendorRef && (
            <>
              <dt className="text-muted">{t("Vendor ref")}</dt>
              <dd>{po.vendorRef}</dd>
            </>
          )}
          <dt className="text-muted">{t("Created")}</dt>
          <dd>
            {t("{createdAt} by {createdBy}", { createdAt: dateTime(po.createdAt, tz), createdBy: po.createdBy })}
          </dd>
          {po.orderedAt && (
            <>
              <dt className="text-muted">{t("Placed")}</dt>
              <dd>{dateTime(po.orderedAt, tz)}</dd>
            </>
          )}
          {po.closedAt && (
            <>
              <dt className="text-muted">{po.status === "CANCELLED" ? t("Cancelled") : t("Completed")}</dt>
              <dd>{dateTime(po.closedAt, tz)}</dd>
            </>
          )}
          {po.note && (
            <>
              <dt className="text-muted">{t("Note")}</dt>
              <dd>{po.note}</dd>
            </>
          )}
        </dl>
      </div>

      {editing && (
        <PurchaseOrderDialog
          order={po}
          onClose={() => setEditing(false)}
          onSaved={(updated) => {
            queryClient.setQueryData(["purchase-orders", id], updated);
            setEditing(false);
          }}
        />
      )}
      {receiving && (
        <ReceiveDialog
          order={po}
          onClose={() => setReceiving(false)}
          onDone={(updated) => {
            queryClient.setQueryData(["purchase-orders", id], updated);
            refresh();
            setReceiving(false);
          }}
        />
      )}
      {invoicing && (
        <InvoiceDialog vendorId={po.vendorId} purchaseOrderId={po.id} suggested={po.receivedValue || po.total} onClose={() => setInvoicing(false)} />
      )}
      {paying && <PaymentDialog invoice={paying} onClose={() => setPaying(null)} />}
    </div>
  );
}

/** Receives a delivery: how much of each outstanding line arrived. Stock goes up straight away. */
function ReceiveDialog({ order, onClose, onDone }: { order: PurchaseOrder; onClose: () => void; onDone: (po: PurchaseOrder) => void }) {
  const t = useT();
  const can = useCan();
  const store = useStoreSettings();
  const open = order.lines.filter((l) => l.remaining > 0);
  const [qtys, setQtys] = useState<Record<number, string>>(() => Object.fromEntries(open.map((l) => [l.id, String(l.remaining)])));
  const [deliveryRef, setDeliveryRef] = useState("");
  const [note, setNote] = useState("");
  const canSetCost = can("CATALOG_MANAGE", "PURCHASE_ORDER");
  const [updateCost, setUpdateCost] = useState(canSetCost);
  const lines = open.map((l) => ({ line: l, qty: Number(qtys[l.id] || 0) })).filter((x) => x.qty > 0);
  const invalid = lines.length === 0 || open.some((l) => {
    const v = Number(qtys[l.id] || 0);
    return !Number.isInteger(v) || v < 0 || v > l.remaining;
  });
  const currency = store.data?.currency ?? "USD";
  const value = lines.reduce((n, x) => n + x.qty * x.line.unitCost, 0);
  const save = useMutation({
    mutationFn: () =>
      api<PurchaseOrder>(`/purchase-orders/${order.id}/receive`, {
        method: "POST",
        json: {
          lines: lines.map((x) => ({ lineId: x.line.id, quantity: x.qty })),
          deliveryRef: deliveryRef.trim() || null,
          note: note.trim() || null,
          updateCost: canSetCost && updateCost,
        },
      }),
    onSuccess: onDone,
  });
  return (
    <Modal open onClose={onClose} title={t("Receive {poNumber}", { poNumber: order.poNumber })} wide>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (!invalid) save.mutate();
        }}
      >
        <p className="text-sm text-muted">
          {t("Into {branchName} / {storageName}. Enter what actually arrived; the rest stays on order.", { branchName: order.branchName, storageName: order.storageName })}
        </p>
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-muted">
            <tr>
              <th className="py-1 font-medium">{t("Item")}</th>
              <th className="py-1 pl-3 text-right font-medium">{t("Still to come")}</th>
              <th className="py-1 pl-3 text-right font-medium">{t("Arrived")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {open.map((l) => {
              const v = Number(qtys[l.id] || 0);
              return (
                <tr key={l.id}>
                  <td className="py-2">
                    {l.name}
                    {l.factor > 1 && <div className="text-xs text-muted">{t("{factor} base units per {unit}", { factor: l.factor, unit: l.unit })}</div>}
                  </td>
                  <td className="py-2 pl-3 text-right tabular-nums">
                    {l.remaining} {l.unit}
                  </td>
                  <td className="w-28 py-2 pl-3">
                    <Input
                      inputMode="numeric"
                      aria-label={t("{name} arrived", { name: l.name })}
                      value={qtys[l.id] ?? ""}
                      onChange={(e) => setQtys((q) => ({ ...q, [l.id]: e.target.value.replace(/\D/g, "") }))}
                      invalid={v > l.remaining}
                      className="h-9 text-right tabular-nums"
                    />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t("Delivery note number")}>
            <Input maxLength={60} value={deliveryRef} onChange={(e) => setDeliveryRef(e.target.value)} />
          </Field>
          <Field label={t("Note")}>
            <Input maxLength={255} value={note} onChange={(e) => setNote(e.target.value)} placeholder={t("e.g. 2 boxes damaged, refused")} />
          </Field>
        </div>
        {canSetCost && (
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={updateCost} onChange={(e) => setUpdateCost(e.target.checked)} />
            {t("Update each item's catalog cost to this purchase price")}
          </label>
        )}
        <ErrorNote error={save.error} />
        <div className="flex items-center justify-between gap-2">
          <span className="text-sm text-muted tabular-nums">{t("Value {value}", { value: money(value, currency) })}</span>
          <div className="flex gap-2">
            <Button type="button" variant="secondary" onClick={onClose}>
              {t("Cancel")}
            </Button>
            <Button type="submit" loading={save.isPending} disabled={invalid}>
              {t("Receive into stock")}
            </Button>
          </div>
        </div>
      </form>
    </Modal>
  );
}
