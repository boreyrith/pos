"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { CustomerDialog } from "@/components/customers/CustomerDialog";
import { StatusBadge } from "@/components/StatusBadge";
import { Badge, Button, Card, ErrorNote, Pagination, Spinner } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { dateTime, money } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { CustomerDetail, OrderSummary, Page } from "@/lib/types";
import { useT } from "@/lib/i18n";

/** A customer's details, what they have spent, and their orders. */
export function CustomerView({ id }: { id: number }) {
  const t = useT();
  const can = useCan();
  const store = useStoreSettings();
  const [editing, setEditing] = useState(false);
  const [page, setPage] = useState(0);
  const detail = useQuery({ queryKey: ["customers", id], queryFn: () => api<CustomerDetail>(`/customers/${id}`) });
  const orders = useQuery({
    queryKey: ["orders", { customerId: id, page }],
    queryFn: () => api<Page<OrderSummary>>(`/orders${queryString({ customerId: id, page })}`),
    enabled: can("ORDERS_VIEW"),
    placeholderData: (previous) => previous,
  });

  if (detail.isPending || !store.data) return <Spinner />;
  if (detail.error) return <div className="p-8"><ErrorNote error={detail.error} /></div>;
  const { customer: c, stats } = detail.data;
  const currency = store.data.currency;
  const tz = store.data.timezone;

  return (
    <div className="mx-auto max-w-5xl p-4 lg:p-8">
      <Link href="/orders?tab=customers" className="mb-4 inline-flex items-center gap-1 text-sm text-muted hover:text-text">
        <ArrowLeft className="size-4" aria-hidden />{" "}{t("Customers")}
      </Link>
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-semibold">{c.name}</h1>
        <span className="font-mono text-sm text-muted">{c.code}</span>
        {c.walkIn && <Badge tone="primary">{t("Default for walk-in sales")}</Badge>}
        {!c.active && <Badge>{t("Inactive")}</Badge>}
        {can("CUSTOMERS_MANAGE") && (
          <Button variant="secondary" className="ml-auto" onClick={() => setEditing(true)}>
            {t("Edit")}
          </Button>
        )}
      </div>

      <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label={t("Orders")} value={String(stats.orders)} />
        <Stat label={t("Spent (after refunds)")} value={money(stats.spent, currency)} />
        <Stat label={t("Refunded")} value={money(stats.refunded, currency)} />
        <Stat label={t("Last visit")} value={stats.lastVisit ? dateTime(stats.lastVisit, tz) : "–"} />
      </div>

      <div className="grid gap-5 lg:grid-cols-[1fr_280px]">
        <Card className="overflow-x-auto">
          <h2 className="px-4 pt-4 text-sm font-semibold">{t("Orders")}</h2>
          {!can("ORDERS_VIEW") ? (
            <p className="p-4 text-sm text-muted">{t("Your role can't see orders.")}</p>
          ) : orders.isPending ? (
            <Spinner />
          ) : orders.data && orders.data.items.length > 0 ? (
            <table className="mt-2 w-full text-sm">
              <thead className="border-b border-border text-left text-xs text-muted">
                <tr>
                  <th className="px-4 py-2 font-medium">{t("Order")}</th>
                  <th className="px-4 py-2 font-medium">{t("Date")}</th>
                  <th className="px-4 py-2 font-medium">{t("Status")}</th>
                  <th className="px-4 py-2 text-right font-medium">{t("Total")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {orders.data.items.map((o) => (
                  <tr key={o.id}>
                    <td className="px-4 py-2">
                      <Link href={`/orders/${o.id}`} className="text-primary hover:underline">
                        {o.orderNumber}
                      </Link>
                      {c.walkIn && o.customerName && <div className="text-xs text-muted">{o.customerName}</div>}
                    </td>
                    <td className="px-4 py-2 text-muted">
                      {dateTime(o.createdAt, tz)} · {o.branchName}
                    </td>
                    <td className="px-4 py-2">
                      <StatusBadge status={o.status} />
                    </td>
                    <td className="px-4 py-2 text-right tabular-nums">{money(o.total, o.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className="p-4 text-sm text-muted">{t("No orders yet.")}</p>
          )}
          {orders.data && orders.data.totalPages > 1 && (
            <div className="px-4 pb-3">
              <Pagination page={page} totalPages={orders.data.totalPages} onChange={setPage} />
            </div>
          )}
        </Card>

        <dl className="grid h-fit grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted">{t("Phone")}</dt>
          <dd className="tabular-nums">{c.phone ?? "–"}</dd>
          <dt className="text-muted">{t("Email")}</dt>
          <dd className="break-all">{c.email ?? "–"}</dd>
          <dt className="text-muted">{t("Address")}</dt>
          <dd>{c.address ?? "–"}</dd>
          <dt className="text-muted">{t("VAT TIN")}</dt>
          <dd>{c.taxId ?? "–"}</dd>
          {c.note && (
            <>
              <dt className="text-muted">{t("Note")}</dt>
              <dd>{c.note}</dd>
            </>
          )}
          <dt className="text-muted">{t("First visit")}</dt>
          <dd>{stats.firstVisit ? dateTime(stats.firstVisit, tz) : "–"}</dd>
          <dt className="text-muted">{t("Added")}</dt>
          <dd>{dateTime(c.createdAt, tz)}</dd>
        </dl>
      </div>
      {editing && <CustomerDialog customer={c} onClose={() => setEditing(false)} />}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Card className="p-4">
      <div className="text-xs text-muted">{label}</div>
      <div className="mt-1 text-lg font-semibold tabular-nums">{value}</div>
    </Card>
  );
}
