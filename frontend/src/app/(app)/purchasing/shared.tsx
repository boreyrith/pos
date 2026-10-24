"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import { Badge, Button, ErrorNote, Field, Input, Modal, Select } from "@/components/ui";
import { ApiError, api, queryString } from "@/lib/api";
import { money, todayIn } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { InvoiceStatus, Page, PurchaseOrderSummary, PurchaseStatus, Vendor, VendorInvoice, VendorPaymentMethod } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

export const PO_STATUS: Record<PurchaseStatus, { label: string; tone: "neutral" | "primary" | "warning" | "success" | "danger" }> = {
  DRAFT: { label: msg("Draft"), tone: "neutral" },
  ORDERED: { label: msg("Ordered"), tone: "primary" },
  PARTIALLY_RECEIVED: { label: msg("Partly received"), tone: "warning" },
  RECEIVED: { label: msg("Received"), tone: "success" },
  CANCELLED: { label: msg("Cancelled"), tone: "neutral" },
};

export const INVOICE_STATUS: Record<InvoiceStatus, { label: string; tone: "neutral" | "primary" | "warning" | "success" | "danger" }> = {
  UNPAID: { label: msg("Unpaid"), tone: "warning" },
  PARTIALLY_PAID: { label: msg("Partly paid"), tone: "primary" },
  PAID: { label: msg("Paid"), tone: "success" },
  VOID: { label: msg("Void"), tone: "neutral" },
};

export const METHODS: Record<VendorPaymentMethod, string> = {
  BANK_TRANSFER: msg("Bank transfer"),
  CASH: msg("Cash"),
  KHQR: "KHQR",
  CHEQUE: msg("Cheque"),
  OTHER: msg("Other"),
};

export function InvoiceBadge({ invoice }: { invoice: VendorInvoice }) {
  const t = useT();
  if (invoice.overdue) return <Badge tone="danger">{t("Overdue")}</Badge>;
  const s = INVOICE_STATUS[invoice.status];
  return <Badge tone={s.tone}>{t(s.label)}</Badge>;
}

export function useVendors() {
  return useQuery({ queryKey: ["vendors"], queryFn: () => api<Vendor[]>("/vendors"), staleTime: 30_000 });
}

/** Invalidates everything purchasing shows after a change. */
export function useRefreshPurchasing() {
  const queryClient = useQueryClient();
  return () => {
    for (const key of ["vendors", "purchase-orders", "vendor-invoices", "payables", "offerings", "movements", "dashboard"]) {
      queryClient.invalidateQueries({ queryKey: [key] });
    }
  };
}

const decimal = (v: string) => v.replace(/[^\d.]/g, "");

/** Records a vendor's invoice, optionally against one of their placed orders. */
export function InvoiceDialog({
  vendorId: fixedVendor,
  purchaseOrderId,
  suggested,
  onClose,
}: {
  vendorId?: number;
  purchaseOrderId?: number;
  /** Pre-fills the subtotal, e.g. the value received on an order. */
  suggested?: number;
  onClose: () => void;
}) {
  const t = useT();
  const store = useStoreSettings();
  const vendors = useVendors();
  const refresh = useRefreshPurchasing();
  const today = todayIn(store.data?.timezone ?? "Asia/Phnom_Penh");
  const [form, setForm] = useState({
    vendorId: fixedVendor ? String(fixedVendor) : "",
    purchaseOrderId: purchaseOrderId ? String(purchaseOrderId) : "",
    invoiceNumber: "",
    invoiceDate: today,
    dueDate: "",
    subtotal: suggested != null ? suggested.toFixed(2) : "",
    tax: "0",
    note: "",
  });
  const set = (k: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [k]: v }));
  const vendor = vendors.data?.find((v) => String(v.id) === form.vendorId);
  const orders = useQuery({
    queryKey: ["purchase-orders", "for-invoice", form.vendorId],
    queryFn: () => api<Page<PurchaseOrderSummary>>(`/purchase-orders${queryString({ vendorId: form.vendorId, size: 50 })}`),
    enabled: form.vendorId !== "" && !purchaseOrderId,
  });
  const subtotal = Number(form.subtotal) || 0;
  const tax = Number(form.tax) || 0;
  const save = useMutation({
    mutationFn: () =>
      api<VendorInvoice>("/vendor-invoices", {
        method: "POST",
        json: {
          vendorId: Number(form.vendorId),
          purchaseOrderId: form.purchaseOrderId ? Number(form.purchaseOrderId) : null,
          invoiceNumber: form.invoiceNumber.trim(),
          invoiceDate: form.invoiceDate,
          dueDate: form.dueDate || null,
          subtotal,
          tax,
          note: form.note.trim() || null,
        },
      }),
    onSuccess: () => {
      refresh();
      onClose();
    },
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  const currency = store.data?.currency ?? "USD";

  return (
    <Modal open onClose={onClose} title={t("Record vendor invoice")}>
      <form
        className="grid grid-cols-2 gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="col-span-2">
          <Field label={t("Vendor")}>
            <Select value={form.vendorId} disabled={!!fixedVendor} required onChange={(e) => setForm((f) => ({ ...f, vendorId: e.target.value, purchaseOrderId: "" }))}>
              <option value="" disabled>
                {t("Choose a vendor")}
              </option>
              {vendors.data?.filter((v) => v.active || String(v.id) === form.vendorId).map((v) => (
                <option key={v.id} value={v.id}>
                  {v.name}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        {!purchaseOrderId && form.vendorId && (
          <div className="col-span-2">
            <Field label={t("Purchase order")} hint={t("Optional")}>
              <Select value={form.purchaseOrderId} onChange={(e) => set("purchaseOrderId")(e.target.value)}>
                <option value="">{t("None")}</option>
                {orders.data?.items
                  .filter((o) => o.status !== "DRAFT" && o.status !== "CANCELLED")
                  .map((o) => (
                    <option key={o.id} value={o.id}>
                      {o.poNumber} · {money(o.total, currency)}
                    </option>
                  ))}
              </Select>
            </Field>
          </div>
        )}
        <Field label={t("Vendor's invoice number")} error={errors.invoiceNumber}>
          <Input required maxLength={60} value={form.invoiceNumber} onChange={(e) => set("invoiceNumber")(e.target.value)} />
        </Field>
        <Field label={t("Invoice date")} error={errors.invoiceDate}>
          <Input type="date" required max={today} value={form.invoiceDate} onChange={(e) => set("invoiceDate")(e.target.value)} />
        </Field>
        <Field label={t("Subtotal (before tax)")} error={errors.subtotal}>
          <Input inputMode="decimal" required value={form.subtotal} onChange={(e) => set("subtotal")(decimal(e.target.value))} />
        </Field>
        <Field label={t("Tax charged (input VAT)")} error={errors.tax}>
          <Input inputMode="decimal" value={form.tax} onChange={(e) => set("tax")(decimal(e.target.value))} />
        </Field>
        <Field label={t("Due date")} hint={vendor ? t("Empty: {paymentTermsDays} days after the invoice date", { paymentTermsDays: vendor.paymentTermsDays }) : undefined}>
          <Input type="date" min={form.invoiceDate} value={form.dueDate} onChange={(e) => set("dueDate")(e.target.value)} />
        </Field>
        <Field label={t("Note")}>
          <Input maxLength={255} value={form.note} onChange={(e) => set("note")(e.target.value)} />
        </Field>
        <p className="col-span-2 text-sm">
          {t("Total")}{" "}<span className="font-semibold tabular-nums">{money(subtotal + tax, currency)}</span>
        </p>
        <div className="col-span-2">
          <ErrorNote error={save.error} />
        </div>
        <div className="col-span-2 flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending} disabled={!form.vendorId || !form.invoiceNumber.trim() || form.subtotal === ""}>
            {t("Record invoice")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

export function PaymentDialog({ invoice, onClose }: { invoice: VendorInvoice; onClose: () => void }) {
  const t = useT();
  const store = useStoreSettings();
  const refresh = useRefreshPurchasing();
  const today = todayIn(store.data?.timezone ?? "Asia/Phnom_Penh");
  const [amount, setAmount] = useState(invoice.balance.toFixed(2));
  const [method, setMethod] = useState<VendorPaymentMethod>("BANK_TRANSFER");
  const [paidOn, setPaidOn] = useState(today);
  const [reference, setReference] = useState("");
  const [note, setNote] = useState("");
  const value = Number(amount);
  const currency = store.data?.currency ?? "USD";
  const save = useMutation({
    mutationFn: () =>
      api<VendorInvoice>(`/vendor-invoices/${invoice.id}/payments`, {
        method: "POST",
        json: { amount: value, method, paidOn, reference: reference.trim() || null, note: note.trim() || null },
      }),
    onSuccess: () => {
      refresh();
      onClose();
    },
  });
  return (
    <Modal open onClose={onClose} title={t("Pay {vendorName} · {invoiceNumber}", { vendorName: invoice.vendorName, invoiceNumber: invoice.invoiceNumber })}>
      <form
        className="grid grid-cols-2 gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <p className="col-span-2 text-sm text-muted">
          {t("Owed {balance} of {total}", { balance: money(invoice.balance, currency), total: money(invoice.total, currency) })}
        </p>
        <Field label={t("Amount")} error={value > invoice.balance ? t("More than is owed") : undefined}>
          <Input autoFocus inputMode="decimal" value={amount} onChange={(e) => setAmount(decimal(e.target.value))} />
        </Field>
        <Field label={t("Paid on")}>
          <Input type="date" max={today} value={paidOn} onChange={(e) => setPaidOn(e.target.value)} />
        </Field>
        <Field label={t("Method")}>
          <Select value={method} onChange={(e) => setMethod(e.target.value as VendorPaymentMethod)}>
            {Object.entries(METHODS).map(([k, v]) => (
              <option key={k} value={k}>
                {t(v)}
              </option>
            ))}
          </Select>
        </Field>
        <Field label={t("Reference")} hint={t("Bank transaction or cheque number")}>
          <Input maxLength={100} value={reference} onChange={(e) => setReference(e.target.value)} />
        </Field>
        <div className="col-span-2">
          <Field label={t("Note")}>
            <Input maxLength={255} value={note} onChange={(e) => setNote(e.target.value)} />
          </Field>
        </div>
        <div className="col-span-2">
          <ErrorNote error={save.error} />
        </div>
        <div className="col-span-2 flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending} disabled={!(value > 0) || value > invoice.balance}>
            {t("Record payment")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
