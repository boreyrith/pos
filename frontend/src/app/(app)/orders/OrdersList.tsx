"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useState } from "react";

import { StatusBadge } from "@/components/StatusBadge";
import { Card, ErrorNote, Input, Pagination, Select, Spinner } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { dateTime, money } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { OrderStatus, OrderSummary, Page } from "@/lib/types";
import { useT } from "@/lib/i18n";

import { BranchFilter } from "../inventory/BranchFilter";

/** Sales > Invoices: every order, with filters. */
export function OrdersList() {
  const t = useT();
  const store = useStoreSettings();
  // A link can open the list already filtered, e.g. ?status=PENDING_PAYMENT from the dashboard.
  const params = useSearchParams();
  const [status, setStatus] = useState<OrderStatus | "">(() => (params.get("status") as OrderStatus | null) ?? "");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [branchId, setBranchId] = useState("");
  const isAdmin = hasPermission(useUser(), "ALL_BRANCHES");

  const orders = useQuery({
    queryKey: ["orders", { status, from, to, q, page, branchId }],
    queryFn: () => api<Page<OrderSummary>>(`/orders${queryString({ status, from, to, q: q.trim(), page, branchId })}`),
    placeholderData: (previous) => previous,
  });

  const resetPage = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    setPage(0);
  };

  return (
    <div>
      <div className="mb-4 flex flex-wrap gap-2">
        <BranchFilter value={branchId} onChange={resetPage(setBranchId)} />
        <Input
          placeholder={t("Order number, customer or phone")}
          aria-label={t("Search by order number")}
          value={q}
          onChange={(e) => resetPage(setQ)(e.target.value)}
          className="w-44"
        />
        <Select aria-label={t("Status")} value={status} onChange={(e) => resetPage(setStatus)(e.target.value as OrderStatus | "")}>
          <option value="">{t("All statuses")}</option>
          <option value="COMPLETED">{t("Completed")}</option>
          <option value="PENDING_PAYMENT">{t("Awaiting payment")}</option>
          <option value="CANCELLED">{t("Cancelled")}</option>
          <option value="PARTIALLY_REFUNDED">{t("Partly refunded")}</option>
          <option value="REFUNDED">{t("Refunded")}</option>
        </Select>
        <label className="flex items-center gap-2 text-sm text-muted">
          {t("From")}
          <Input type="date" value={from} onChange={(e) => resetPage(setFrom)(e.target.value)} className="w-40" />
        </label>
        <label className="flex items-center gap-2 text-sm text-muted">
          {t("To")}
          <Input type="date" value={to} onChange={(e) => resetPage(setTo)(e.target.value)} className="w-40" />
        </label>
      </div>

      <Card className="overflow-x-auto">
        {orders.isPending ? (
          <Spinner />
        ) : orders.error ? (
          <div className="p-4">
            <ErrorNote error={orders.error} />
          </div>
        ) : orders.data.items.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("No orders match these filters.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="px-4 py-3 font-medium">{t("Order")}</th>
                <th className="px-4 py-3 font-medium">{t("Date")}</th>
                {isAdmin && <th className="px-4 py-3 font-medium">{t("Branch")}</th>}
                <th className="px-4 py-3 font-medium">{t("Cashier")}</th>
                <th className="px-4 py-3 font-medium">{t("Status")}</th>
                <th className="px-4 py-3 text-right font-medium">{t("Total")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {orders.data.items.map((o) => (
                <tr key={o.id} className="hover:bg-surface-2">
                  <td className="px-4 py-3 font-medium">
                    <Link href={`/orders/${o.id}`} className="text-primary hover:underline">
                      {o.orderNumber}
                    </Link>
                    {o.customerName && (
                      <div className="text-xs font-normal text-muted">
                        {o.walkIn ? t("Walk-in · {customerName}", { customerName: o.customerName }) : o.customerName}
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap text-muted">{dateTime(o.createdAt, store.data?.timezone)}</td>
                  {isAdmin && <td className="px-4 py-3">{o.branchName}</td>}
                  <td className="px-4 py-3">{o.cashierName}</td>
                  <td className="px-4 py-3">
                    <StatusBadge status={o.status} />
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums">
                    {money(o.total, o.currency)}
                    {o.refundedTotal > 0 && o.refundedTotal < o.total && (
                      <div className="text-xs text-danger">{t("-{refundedTotal} refunded", { refundedTotal: money(o.refundedTotal, o.currency) })}</div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      {orders.data && <Pagination page={page} totalPages={orders.data.totalPages} onChange={setPage} />}
    </div>
  );
}
