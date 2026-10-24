"use client";

import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, ArrowDownRight, ArrowUpRight, Clock, FileBarChart, Receipt, Truck } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { Card, ErrorNote, PageHeader, Select, Spinner, cx } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { TYPE_LABELS, amountIn, money } from "@/lib/format";
import { useCurrencies, useStoreSettings } from "@/lib/queries";
import type { Dashboard } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

import { BranchFilter } from "../inventory/BranchFilter";
import { SalesChart } from "./SalesChart";

const PERIODS = [
  { days: 1, label: msg("Today") },
  { days: 7, label: msg("Last 7 days") },
  { days: 30, label: msg("Last 30 days") },
  { days: 90, label: msg("Last 90 days") },
] as const;

/**
 * The dashboard: headline figures for a period against the period before, daily sales, what sells,
 * how customers pay, the cash drawer, and what needs attention. Uses the same figures as Reports.
 */
export function DashboardView() {
  const t = useT();
  const can = useCan();
  const [days, setDays] = useState(7);
  const [branchId, setBranchId] = useState("");
  const dashboard = useQuery({
    queryKey: ["dashboard", days, branchId],
    queryFn: () => api<Dashboard>(`/reports/dashboard${queryString({ days, branchId })}`),
    refetchInterval: 60_000,
    placeholderData: (previous) => previous,
  });
  const reports = can("REPORTS_SALES", "REPORTS_INVENTORY", "REPORTS_PURCHASING", "REPORTS_TAX");

  return (
    <div className="mx-auto max-w-6xl p-4 lg:p-8">
      <PageHeader
        title={t("Dashboard")}
        actions={
          <>
            <BranchFilter value={branchId} onChange={setBranchId} />
            <Select aria-label={t("Period")} value={days} onChange={(e) => setDays(Number(e.target.value))}>
              {PERIODS.map((p) => (
                <option key={p.days} value={p.days}>
                  {t(p.label)}
                </option>
              ))}
            </Select>
            {reports && (
              <Link href="/reports" className="inline-flex h-9 items-center gap-1.5 rounded-lg px-3 text-sm font-medium text-primary hover:bg-primary-soft">
                <FileBarChart className="size-4" aria-hidden />{" "}{t("Reports")}
              </Link>
            )}
          </>
        }
      />
      {dashboard.isPending ? (
        <Spinner />
      ) : dashboard.error ? (
        <ErrorNote error={dashboard.error} />
      ) : (
        <DashboardBody data={dashboard.data} days={days} />
      )}
    </div>
  );
}

function DashboardBody({ data, days }: { data: Dashboard; days: number }) {
  const t = useT();
  const noVat = useStoreSettings().data?.taxMode === "NONE";
  const currencies = useCurrencies().data ?? [];
  const c = data.currency;
  const p = data.period;
  const prev = data.previous;
  const label = days === 1 ? t("today") : t("last {n} days", { n: days });
  const vs = days === 1 ? t("yesterday") : t("the {n} days before", { n: days });
  const paymentTotal = data.paymentMethods.reduce((sum, x) => sum + x.amount, 0);

  return (
    <div className="flex flex-col gap-5">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
        <Stat label={t("Sales, {label}", { label })} value={money(p.sales, c)} change={change(p.sales, prev.sales)} vs={vs} sub={noVat ? t("After refunds") : t("Tax included, after refunds")} />
        <Stat label={t("Net sales")} value={money(p.netSales, c)} change={change(p.netSales, prev.netSales)} vs={vs} sub={noVat ? t("After discounts and refunds") : t("Without tax · tax {tax}", { tax: money(p.tax, c) })} />
        <Stat
          label={t("Gross profit")}
          value={money(p.profit, c)}
          change={change(p.profit, prev.profit)}
          vs={vs}
          sub={p.margin == null ? t("No sales") : t("{margin}% margin · cost {cost}", { margin: p.margin, cost: money(p.cost, c) })}
        />
        <Stat label={t("Orders")} value={String(p.orders)} change={change(p.orders, prev.orders)} vs={vs} sub={t("Average {average} · {n} units", { average: money(p.averageOrder, c), n: p.items })} />
        <Stat label={t("Refunds")} value={money(p.refunds, c)} sub={p.returns === 1 ? t("1 return") : t("{n} returns", { n: p.returns })} tone={p.refunds > 0 ? "warning" : undefined} />
        <Stat label={t("Discounts given")} value={money(p.discounts, c)} sub={t("Item and order discounts")} />
      </div>
      {days !== 1 && (
        <p className="-mt-2 text-xs text-muted">
          {t("Today so far: {sales} from {orders} orders, profit {profit}.", { sales: money(data.today.sales, c), orders: data.today.orders, profit: money(data.today.profit, c) })}
        </p>
      )}

      <Attention data={data} />

      <Card className="p-5">
        <h2 className="text-sm font-semibold">{t("Daily sales")}</h2>
        <p className="mb-2 text-xs text-muted">
          {noVat
            ? t("By the day orders were paid, less refunds on the day they were made (a day with more refunds than sales shows no bar).")
            : t("Tax included, by the day orders were paid, less refunds on the day they were made (a day with more refunds than sales shows no bar).")}
        </p>
        <SalesChart days={data.daily} currency={c} />
      </Card>

      <div className="grid gap-5 lg:grid-cols-2">
        <Card className="p-5">
          <h2 className="mb-3 text-sm font-semibold">{t("Top sellers, {label}", { label })}</h2>
          {data.topSellers.length === 0 ? (
            <p className="text-sm text-muted">{t("No sales yet.")}</p>
          ) : (
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-muted">
                <tr>
                  <th className="pb-2 font-medium">{t("Name")}</th>
                  <th className="pb-2 text-right font-medium">{t("Units")}</th>
                  <th className="pb-2 text-right font-medium">{t("Net sales")}</th>
                  <th className="pb-2 text-right font-medium">{t("Profit")}</th>
                </tr>
              </thead>
              <tbody>
                {data.topSellers.map((x) => (
                  <tr key={x.offeringId} className="border-t border-border">
                    <td className="py-2">
                      {x.name} <span className="text-xs text-muted">· {t(TYPE_LABELS[x.type])}</span>
                    </td>
                    <td className="py-2 text-right tabular-nums">{x.quantity}</td>
                    <td className="py-2 text-right tabular-nums">{money(x.netSales, c)}</td>
                    <td className="py-2 text-right tabular-nums">{money(x.profit, c)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p className="mt-2 text-xs text-muted">{noVat ? t("Units kept (sold less returned).") : t("Units kept (sold less returned); net sales without tax.")}</p>
        </Card>
        <Card className="p-5">
          <h2 className="mb-3 text-sm font-semibold">{t("Money received, {label}", { label })}</h2>
          {data.paymentMethods.length === 0 ? (
            <p className="text-sm text-muted">{t("No payments yet.")}</p>
          ) : (
            <ul className="flex flex-col gap-3 text-sm">
              {data.paymentMethods.map((x) => (
                <li key={x.method}>
                  <div className="mb-1 flex justify-between">
                    <span className="font-medium">{x.method === "KHQR" ? "KHQR" : t("Cash")}</span>
                    <span className="tabular-nums">
                      {money(x.amount, c)} <span className="text-muted">{t("· {count} payments", { count: x.count })}</span>
                    </span>
                  </div>
                  <div className="h-2 rounded-full bg-surface-2" aria-hidden>
                    <div className="h-2 rounded-full bg-chart-1" style={{ width: `${paymentTotal ? (x.amount / paymentTotal) * 100 : 0}%` }} />
                  </div>
                </li>
              ))}
              {data.refundsPaid > 0 && (
                <li className="flex justify-between border-t border-border pt-2">
                  <span className="font-medium">{t("Refunds paid out")}</span>
                  <span className="tabular-nums text-danger">-{money(data.refundsPaid, c)}</span>
                </li>
              )}
              <li className="flex justify-between border-t border-border pt-2 font-semibold">
                <span>{t("Kept")}</span>
                <span className="tabular-nums">{money(paymentTotal - data.refundsPaid, c)}</span>
              </li>
            </ul>
          )}
        </Card>
      </div>

      <Card className="p-5">
        <h2 className="text-sm font-semibold">{t("Cash drawer by currency, {label}", { label })}</h2>
        <p className="mb-3 text-xs text-muted">
          {t("What customers handed over and what went back as change, per currency. Refunds paid in cash ({refundsPaid}) are not split by currency.", { refundsPaid: money(data.refundsPaid, c) })}
        </p>
        {data.cashByCurrency.length === 0 ? (
          <p className="text-sm text-muted">{t("No cash payments yet.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted">
              <tr>
                <th className="pb-2 font-medium">{t("Currency")}</th>
                <th className="pb-2 text-right font-medium">{t("Received")}</th>
                <th className="pb-2 text-right font-medium">{t("Change given")}</th>
                <th className="pb-2 text-right font-medium">{t("Kept")}</th>
              </tr>
            </thead>
            <tbody>
              {data.cashByCurrency.map((row) => {
                const cur = currencies.find((x) => x.code === row.currency);
                const show = (n: number) => (cur ? amountIn(n, cur) : `${n} ${row.currency}`);
                return (
                  <tr key={row.currency} className="border-t border-border">
                    <td className="py-2 font-medium">{row.currency}</td>
                    <td className="py-2 text-right tabular-nums">{show(row.received)}</td>
                    <td className="py-2 text-right text-muted tabular-nums">{show(row.changeGiven)}</td>
                    <td className="py-2 text-right font-medium tabular-nums">{show(row.net)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>

      <div className="grid gap-5 lg:grid-cols-2">
        <Card className="p-5">
          <h2 className="mb-3 text-sm font-semibold">{t("Low on stock")}</h2>
          {data.lowStockItems.length === 0 ? (
            <p className="text-sm text-muted">{t("Nothing is below its low-stock level.")}</p>
          ) : (
            <ul className="divide-y divide-border text-sm">
              {data.lowStockItems.map((l) => (
                <li key={l.offeringId} className="flex justify-between gap-3 py-1.5">
                  <span className="min-w-0 truncate">{l.name}</span>
                  <span className={cx("shrink-0 tabular-nums", l.stock <= 0 ? "font-medium text-danger" : "text-warning")}>
                    {l.stock} {l.unit ?? ""} <span className="text-xs text-muted">/ {l.threshold}</span>
                  </span>
                </li>
              ))}
            </ul>
          )}
          {data.lowStock > data.lowStockItems.length && (
            <Link href="/catalog?lowStock=1" className="mt-2 inline-block text-xs text-primary hover:underline">
              {t("See all {lowStock}", { lowStock: data.lowStock })}
            </Link>
          )}
        </Card>
        <Card className="p-5">
          <h2 className="mb-1 text-sm font-semibold">{t("Customers, {label}", { label })}</h2>
          <p className="mb-3 text-xs text-muted">{data.newCustomers === 1 ? t("1 new customer added") : t("{n} new customers added", { n: data.newCustomers })}</p>
          {data.topCustomers.length === 0 ? (
            <p className="text-sm text-muted">{t("No sales to named customers yet (walk-in sales are not listed).")}</p>
          ) : (
            <ul className="divide-y divide-border text-sm">
              {data.topCustomers.map((x) => (
                <li key={x.customerId} className="flex justify-between gap-3 py-1.5">
                  <Link href={`/customers/${x.customerId}`} className="min-w-0 truncate text-primary hover:underline">
                    {x.name}
                  </Link>
                  <span className="shrink-0 tabular-nums">
                    {money(x.spent, c)} <span className="text-xs text-muted">{t("· {orders} orders", { orders: x.orders })}</span>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </div>
  );
}

/** Things to act on: low stock, transfers to receive, unpaid KHQR orders, vendor bills. */
function Attention({ data }: { data: Dashboard }) {
  const t = useT();
  const c = data.currency;
  const items: React.ReactNode[] = [];
  if (data.lowStock > 0) {
    items.push(
      <Link key="low" href="/catalog?lowStock=1" className="flex items-center gap-2 rounded-lg bg-warning-soft px-3 py-2 text-sm text-warning hover:underline">
        <AlertTriangle className="size-4" aria-hidden />
        {data.lowStock === 1 ? t("1 product or item is low on stock") : t("{n} products or items are low on stock", { n: data.lowStock })}
      </Link>,
    );
  }
  if (data.incomingTransfers > 0) {
    items.push(
      <Link key="tr" href="/inventory?tab=transfers" className="flex items-center gap-2 rounded-lg bg-primary-soft px-3 py-2 text-sm text-primary hover:underline">
        <Truck className="size-4" aria-hidden />
        {data.incomingTransfers === 1 ? t("1 transfer waiting to be received") : t("{n} transfers waiting to be received", { n: data.incomingTransfers })}
      </Link>,
    );
  }
  if (data.pendingOrders > 0) {
    items.push(
      <Link
        key="pending"
        href="/orders?tab=invoices&status=PENDING_PAYMENT"
        className="flex items-center gap-2 rounded-lg bg-surface px-3 py-2 text-sm text-muted ring-1 ring-border hover:underline"
      >
        <Clock className="size-4" aria-hidden />
        {data.pendingOrders === 1 ? t("1 order awaiting KHQR payment") : t("{n} orders awaiting KHQR payment", { n: data.pendingOrders })}
      </Link>,
    );
  }
  if (data.payables && data.payables.owed > 0) {
    items.push(
      <Link
        key="pay"
        href="/purchasing?tab=invoices"
        className={cx(
          "flex items-center gap-2 rounded-lg px-3 py-2 text-sm hover:underline",
          data.payables.overdue > 0 ? "bg-danger-soft text-danger" : "bg-surface text-muted ring-1 ring-border",
        )}
      >
        <Receipt className="size-4" aria-hidden />
        {data.payables.overdue > 0
          ? t("{owed} owed to vendors, {overdue} overdue", { owed: money(data.payables.owed, c), overdue: money(data.payables.overdue, c) })
          : t("{owed} owed to vendors", { owed: money(data.payables.owed, c) })}
      </Link>,
    );
  }
  if (data.payables && data.payables.openOrders > 0) {
    items.push(
      <Link key="po" href="/purchasing?tab=orders" className="flex items-center gap-2 rounded-lg bg-surface px-3 py-2 text-sm text-muted ring-1 ring-border hover:underline">
        <Truck className="size-4" aria-hidden />
        {data.payables.openOrders === 1 ? t("1 purchase order to receive") : t("{n} purchase orders to receive", { n: data.payables.openOrders })}
      </Link>,
    );
  }
  return items.length ? <div className="flex flex-wrap gap-3">{items}</div> : null;
}

/** % change against the previous period; null when there is nothing to compare with. */
function change(now: number, before: number) {
  if (!before) return null;
  return ((now - before) / Math.abs(before)) * 100;
}

function Stat({
  label,
  value,
  sub,
  change: pct,
  vs,
  tone,
}: {
  label: string;
  value: string;
  sub?: string;
  change?: number | null;
  vs?: string;
  tone?: "warning";
}) {
  const t = useT();
  return (
    <Card className="p-4">
      <div className="text-xs text-muted">{label}</div>
      <div className={cx("mt-1 text-2xl font-semibold tabular-nums", tone === "warning" && "text-warning")}>{value}</div>
      {pct != null && (
        <div className={cx("mt-0.5 flex items-center gap-0.5 text-xs", pct >= 0 ? "text-success" : "text-danger")} title={t("Compared with {vs}", { vs })}>
          {pct >= 0 ? <ArrowUpRight className="size-3.5" aria-hidden /> : <ArrowDownRight className="size-3.5" aria-hidden />}
          {t("{pct}% vs {vs}", { pct: Math.abs(pct).toFixed(1), vs })}
        </div>
      )}
      {sub && <div className="mt-0.5 text-xs text-muted">{sub}</div>}
    </Card>
  );
}
