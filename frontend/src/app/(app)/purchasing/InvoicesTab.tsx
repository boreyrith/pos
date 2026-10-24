"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, Plus } from "lucide-react";
import Link from "next/link";
import { Fragment, useState } from "react";

import { Button, Card, ErrorNote, Input, Pagination, Select, Spinner } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { money, shortDate } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { InvoiceStatus, Page, VendorInvoice } from "@/lib/types";
import { translate, useT } from "@/lib/i18n";

import { INVOICE_STATUS, InvoiceBadge, InvoiceDialog, METHODS, PaymentDialog, useRefreshPurchasing, useVendors } from "./shared";

/** Vendor invoices with what is owed and paid; record invoices and payments. */
export function InvoicesTab() {
  const t = useT();
  const can = useCan();
  const store = useStoreSettings();
  const vendors = useVendors();
  const refresh = useRefreshPurchasing();
  const [status, setStatus] = useState<InvoiceStatus | "OVERDUE" | "">("");
  const [vendorId, setVendorId] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<number | null>(null);
  const [recording, setRecording] = useState(false);
  const [paying, setPaying] = useState<VendorInvoice | null>(null);
  const invoices = useQuery({
    queryKey: ["vendor-invoices", { status, vendorId, q, page }],
    queryFn: () =>
      api<Page<VendorInvoice>>(
        `/vendor-invoices${queryString({
          status: status === "OVERDUE" ? null : status,
          overdue: status === "OVERDUE" ? true : null,
          vendorId,
          q: q.trim(),
          page,
        })}`,
      ),
    placeholderData: (previous) => previous,
  });
  const voidInvoice = useMutation({
    mutationFn: (id: number) => api<VendorInvoice>(`/vendor-invoices/${id}/void`, { method: "POST" }),
    onSuccess: refresh,
  });
  const reset = <T,>(setter: (v: T) => void) => (v: T) => {
    setter(v);
    setPage(0);
  };
  const currency = store.data?.currency ?? "USD";

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Input placeholder={t("Invoice number or vendor")} aria-label={t("Search invoices")} value={q} onChange={(e) => reset(setQ)(e.target.value)} className="w-64" />
        <Select aria-label={t("Status")} value={status} onChange={(e) => reset(setStatus)(e.target.value as InvoiceStatus | "OVERDUE" | "")}>
          <option value="">{t("Any status")}</option>
          <option value="OVERDUE">{t("Overdue")}</option>
          {Object.entries(INVOICE_STATUS).map(([k, v]) => (
            <option key={k} value={k}>
              {t(v.label)}
            </option>
          ))}
        </Select>
        <Select aria-label={t("Vendor")} value={vendorId} onChange={(e) => reset(setVendorId)(e.target.value)}>
          <option value="">{t("All vendors")}</option>
          {vendors.data?.map((v) => (
            <option key={v.id} value={v.id}>
              {v.name}
            </option>
          ))}
        </Select>
        {can("PURCHASE_INVOICE") && (
          <Button className="ml-auto" onClick={() => setRecording(true)}>
            <Plus className="size-4" aria-hidden />{" "}{t("Record invoice")}
          </Button>
        )}
      </div>
      <ErrorNote error={voidInvoice.error} />
      <Card className="overflow-x-auto">
        {invoices.isPending ? (
          <Spinner />
        ) : invoices.error ? (
          <div className="p-4">
            <ErrorNote error={invoices.error} />
          </div>
        ) : invoices.data.items.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("No invoices match.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="w-8" aria-label={t("Details")} />
                <th className="px-3 py-3 font-medium">{t("Invoice")}</th>
                <th className="px-3 py-3 font-medium">{t("Vendor")}</th>
                <th className="px-3 py-3 font-medium">{t("Due")}</th>
                <th className="px-3 py-3 font-medium">{t("Status")}</th>
                <th className="px-3 py-3 text-right font-medium">{t("Total")}</th>
                <th className="px-3 py-3 text-right font-medium">{t("Owed")}</th>
                <th className="px-3 py-3" aria-label={t("Actions")} />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {invoices.data.items.map((i) => (
                <Fragment key={i.id}>
                  <tr className={i.status === "VOID" ? "text-muted" : "hover:bg-surface-2"}>
                    <td className="pl-3">
                      <button onClick={() => setOpen(open === i.id ? null : i.id)} aria-label={t("Show payments")} className="rounded p-1 hover:bg-surface-2">
                        {open === i.id ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
                      </button>
                    </td>
                    <td className="px-3 py-3">
                      <div className="font-medium">{i.invoiceNumber}</div>
                      <div className="text-xs text-muted">
                        {shortDate(i.invoiceDate)}
                        {i.poNumber && (
                          <>
                            {" · "}
                            <Link href={`/purchasing/orders/${i.purchaseOrderId}`} className="text-primary hover:underline">
                              {i.poNumber}
                            </Link>
                          </>
                        )}
                      </div>
                    </td>
                    <td className="px-3 py-3">{i.vendorName}</td>
                    <td className={`px-3 py-3 ${i.overdue ? "font-medium text-danger" : "text-muted"}`}>{shortDate(i.dueDate)}</td>
                    <td className="px-3 py-3">
                      <InvoiceBadge invoice={i} />
                    </td>
                    <td className="px-3 py-3 text-right tabular-nums">
                      {money(i.total, currency)}
                      {i.tax > 0 && <div className="text-xs text-muted">{t("tax {tax}", { tax: money(i.tax, currency) })}</div>}
                    </td>
                    <td className="px-3 py-3 text-right font-medium tabular-nums">{money(i.balance, currency)}</td>
                    <td className="px-3 py-3 text-right whitespace-nowrap">
                      {can("PURCHASE_PAY") && i.balance > 0 && (
                        <Button size="sm" onClick={() => setPaying(i)}>
                          {t("Pay")}
                        </Button>
                      )}
                      {can("PURCHASE_INVOICE") && i.paid === 0 && i.status !== "VOID" && (
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => {
                            if (confirm(translate("Void {vendorName} invoice {invoiceNumber}?", { vendorName: i.vendorName, invoiceNumber: i.invoiceNumber }))) voidInvoice.mutate(i.id);
                          }}
                        >
                          {t("Void")}
                        </Button>
                      )}
                    </td>
                  </tr>
                  {open === i.id && (
                    <tr className="bg-surface-2">
                      <td />
                      <td colSpan={7} className="px-3 py-3 text-xs">
                        {i.payments.length === 0 ? (
                          <span className="text-muted">{t("No payments yet.")}</span>
                        ) : (
                          <ul className="flex flex-col gap-1">
                            {i.payments.map((p) => (
                              <li key={p.id} className="flex flex-wrap gap-x-3">
                                <span className="font-medium tabular-nums">{money(p.amount, currency)}</span>
                                <span>{t(METHODS[p.method])}</span>
                                <span className="text-muted">{shortDate(p.paidOn)}</span>
                                {p.reference && <span className="text-muted">{t("ref {reference}", { reference: p.reference })}</span>}
                                <span className="text-muted">{t("by {createdBy}", { createdBy: p.createdBy })}</span>
                                {p.note && <span className="text-muted">· {p.note}</span>}
                              </li>
                            ))}
                          </ul>
                        )}
                        {i.note && <p className="mt-2 text-muted">{t("Note: {note}", { note: i.note })}</p>}
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      {invoices.data && <Pagination page={page} totalPages={invoices.data.totalPages} onChange={setPage} />}
      {recording && <InvoiceDialog onClose={() => setRecording(false)} />}
      {paying && <PaymentDialog invoice={paying} onClose={() => setPaying(null)} />}
    </div>
  );
}
