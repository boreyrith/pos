"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";

import { Badge, Card, ErrorNote, Input, Pagination, Spinner } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { dateTime, money } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { Page, ReturnSummary } from "@/lib/types";
import { useT } from "@/lib/i18n";

import { BranchFilter } from "../inventory/BranchFilter";

/**
 * Sales > Returns & refunds: every return, partial or full, newest first. Returns are made from
 * the order (Return items / Refund all); this is the list to look them up and reconcile refunds.
 */
export function ReturnsTab() {
  const t = useT();
  const user = useUser();
  const store = useStoreSettings();
  const [q, setQ] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [branchId, setBranchId] = useState("");
  const [page, setPage] = useState(0);
  const returns = useQuery({
    queryKey: ["orders", "returns", { q, from, to, branchId, page }],
    queryFn: () => api<Page<ReturnSummary>>(`/orders/returns${queryString({ q: q.trim(), from, to, branchId, page })}`),
    placeholderData: (previous) => previous,
  });
  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    setPage(0);
  };
  const tz = store.data?.timezone;
  const pageTotal = returns.data?.items.reduce((n, r) => n + r.total, 0) ?? 0;
  const pageTax = returns.data?.items.reduce((n, r) => n + r.tax, 0) ?? 0;
  const currency = store.data?.currency ?? "USD";

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <BranchFilter value={branchId} onChange={reset(setBranchId)} />
        <Input
          placeholder={t("Return or order number, customer, reason")}
          aria-label={t("Search returns")}
          value={q}
          onChange={(e) => reset(setQ)(e.target.value)}
          className="w-72"
        />
        <label className="flex items-center gap-2 text-sm text-muted">
          {t("From")}
          <Input type="date" value={from} onChange={(e) => reset(setFrom)(e.target.value)} className="w-40" />
        </label>
        <label className="flex items-center gap-2 text-sm text-muted">
          {t("To")}
          <Input type="date" value={to} onChange={(e) => reset(setTo)(e.target.value)} className="w-40" />
        </label>
      </div>
      <p className="mb-3 text-xs text-muted">
        {t("To return items or refund, open the invoice and use “Return items” or “Refund all”")}
        {hasPermission(user, "ORDERS_REFUND") ? "." : t(" (needs the Refunds and returns permission).")}
      </p>
      <Card className="overflow-x-auto">
        {returns.isPending ? (
          <Spinner />
        ) : returns.error ? (
          <div className="p-4">
            <ErrorNote error={returns.error} />
          </div>
        ) : returns.data.items.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("No returns match.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="px-4 py-3 font-medium">{t("Return")}</th>
                <th className="px-4 py-3 font-medium">{t("Invoice")}</th>
                <th className="px-4 py-3 font-medium">{t("Customer")}</th>
                <th className="px-4 py-3 font-medium">{t("Items")}</th>
                <th className="px-4 py-3 text-right font-medium">{t("Refunded")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {returns.data.items.map((r) => (
                <tr key={r.id} className="align-top hover:bg-surface-2">
                  <td className="px-4 py-3">
                    <div className="flex flex-wrap items-center gap-x-2 gap-y-1 font-medium">
                      <span className="whitespace-nowrap">{r.returnNumber}</span>
                      {r.fullRefund ? <Badge tone="danger">{t("Refunded in full")}</Badge> : <Badge tone="warning">{t("Partial")}</Badge>}
                    </div>
                    <div className="text-xs text-muted">
                      {dateTime(r.createdAt, tz)} · {r.createdBy}
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <Link href={`/orders/${r.orderId}`} className="whitespace-nowrap text-primary hover:underline">
                      {r.orderNumber}
                    </Link>
                    <div className="text-xs text-muted">{r.branchName}</div>
                  </td>
                  <td className="px-4 py-3">
                    {r.walkIn ? (
                      <span className="text-muted">{t("Walk-in")}{r.customerName ? ` · ${r.customerName}` : ""}</span>
                    ) : hasPermission(user, "CUSTOMERS_VIEW") ? (
                      <Link href={`/customers/${r.customerId}`} className="text-primary hover:underline">
                        {r.customerName}
                      </Link>
                    ) : (
                      r.customerName
                    )}
                  </td>
                  <td className="max-w-64 px-4 py-3 text-xs">
                    {r.items}
                    {r.reason && <div className="text-muted">{t("Reason: {reason}", { reason: r.reason })}</div>}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums">
                    -{money(r.total, currency)}
                    {r.tax > 0 && <div className="text-xs text-muted">{t("incl. tax {tax}", { tax: money(r.tax, currency) })}</div>}
                  </td>
                </tr>
              ))}
            </tbody>
            <tfoot className="border-t border-border text-sm">
              <tr>
                <td colSpan={4} className="px-4 py-2 text-right text-muted">
                  {t("This page")}
                </td>
                <td className="px-4 py-2 text-right font-semibold tabular-nums">
                  -{money(pageTotal, currency)}
                  {pageTax > 0 && <div className="text-xs font-normal text-muted">{t("incl. tax {pageTax}", { pageTax: money(pageTax, currency) })}</div>}
                </td>
              </tr>
            </tfoot>
          </table>
        )}
      </Card>
      {returns.data && <Pagination page={page} totalPages={returns.data.totalPages} onChange={setPage} />}
    </div>
  );
}
