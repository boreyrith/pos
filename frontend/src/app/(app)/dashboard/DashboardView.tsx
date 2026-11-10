"use client";

import "./dashboard.css";

import { useQuery } from "@tanstack/react-query";
import {
  AlertTriangle,
  ArrowDownRight,
  ArrowLeftRight,
  ArrowUpRight,
  Banknote,
  CheckCircle2,
  ChevronRight,
  ClipboardCheck,
  ClipboardList,
  Clock,
  CoinsIcon,
  FileBarChart,
  FileText,
  Package,
  PiggyBank,
  Receipt,
  RotateCcw,
  Settings,
  ShoppingBag,
  ShoppingCart,
  Truck,
  Users,
  Wallet,
} from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { ErrorNote, cx } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { useCan, useUser } from "@/lib/auth";
import { TYPE_LABELS, amountIn, money, shortDate, todayIn } from "@/lib/format";
import { msg, translateServer, useT } from "@/lib/i18n";
import { useCurrencies, useStoreSettings } from "@/lib/queries";
import type { Dashboard, Permission, ReportInfo } from "@/lib/types";

import { BranchFilter } from "../inventory/BranchFilter";
import { MEASURES, type Measure, Sparkline, TrendChart, useCountUp } from "./Charts";

const PERIODS = [
  { days: 1, label: msg("Today") },
  { days: 7, label: msg("Last 7 days") },
  { days: 30, label: msg("Last 30 days") },
  { days: 90, label: msg("Last 90 days") },
] as const;

/** Management shortcuts, each shown only to roles with one of its permissions. */
const ACTIONS: { href: string; label: string; icon: typeof ShoppingCart; any: Permission[] }[] = [
  { href: "/pos", label: msg("Sell"), icon: ShoppingCart, any: ["SALES_SELL"] },
  { href: "/orders?tab=invoices", label: msg("Invoices"), icon: Receipt, any: ["ORDERS_VIEW"] },
  { href: "/orders?tab=returns", label: msg("Returns & refunds"), icon: RotateCcw, any: ["ORDERS_VIEW"] },
  { href: "/customers", label: msg("Customers"), icon: Users, any: ["CUSTOMERS_VIEW"] },
  { href: "/catalog", label: msg("Catalog"), icon: Package, any: ["CATALOG_MANAGE"] },
  { href: "/inventory?tab=transfers", label: msg("Transfers"), icon: ArrowLeftRight, any: ["STOCK_TRANSFER"] },
  { href: "/inventory?tab=adjustments", label: msg("Adjustments"), icon: ClipboardCheck, any: ["STOCK_ADJUST", "STOCK_COUNT"] },
  { href: "/purchasing?tab=orders", label: msg("Purchase orders"), icon: ClipboardList, any: ["PURCHASE_ORDER", "PURCHASE_RECEIVE"] },
  { href: "/purchasing?tab=invoices", label: msg("Invoices & payments"), icon: Wallet, any: ["PURCHASE_INVOICE", "PURCHASE_PAY"] },
  { href: "/currencies", label: msg("Exchange rates"), icon: CoinsIcon, any: ["CURRENCY_RATES"] },
  { href: "/users", label: msg("Users"), icon: Users, any: ["USERS_VIEW"] },
  { href: "/settings", label: msg("Settings"), icon: Settings, any: ["SETTINGS_VIEW"] },
];

/**
 * The dashboard: headline figures for a period against the period before, the daily trend, what needs
 * attention, shortcuts to the work and the reports a role can open, what sells, how customers pay, the
 * cash drawer, stock and customers. Uses the same figures as Reports.
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

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6 lg:px-8 lg:py-8">
      <Header updatedAt={dashboard.dataUpdatedAt} fetching={dashboard.isFetching} />

      <div className="mt-6 flex flex-wrap items-center gap-2">
        <div role="group" aria-label={t("Period")} className="inline-flex rounded-full bg-surface-2 p-1 ring-1 ring-border">
          {PERIODS.map((p) => (
            <button
              key={p.days}
              type="button"
              aria-pressed={days === p.days}
              onClick={() => setDays(p.days)}
              className={cx(
                "rounded-full px-3.5 py-1.5 text-sm font-medium whitespace-nowrap transition-[background-color,color,box-shadow] duration-200",
                "focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-primary",
                days === p.days ? "bg-surface text-text shadow-sm ring-1 ring-border" : "text-muted hover:text-text",
              )}
            >
              {t(p.label)}
            </button>
          ))}
        </div>
        <BranchFilter value={branchId} onChange={setBranchId} />
        {can("REPORTS_SALES", "REPORTS_INVENTORY", "REPORTS_PURCHASING", "REPORTS_ACCOUNTING", "REPORTS_TAX") && (
          <Link
            href="/reports"
            className="ml-auto inline-flex h-9 items-center gap-1.5 rounded-full px-3.5 text-sm font-medium text-primary ring-1 ring-border transition-colors hover:bg-primary-soft"
          >
            <FileBarChart className="size-4" aria-hidden />
            {t("Reports")}
          </Link>
        )}
      </div>

      <div className="mt-5">
        {dashboard.isPending ? (
          <Skeleton />
        ) : dashboard.error ? (
          <ErrorNote error={dashboard.error} />
        ) : (
          <DashboardBody data={dashboard.data} days={days} />
        )}
      </div>
    </div>
  );
}

function Header({ updatedAt, fetching }: { updatedAt: number; fetching: boolean }) {
  const t = useT();
  const user = useUser();
  const store = useStoreSettings().data;
  const hour = new Date().getHours();
  const first = user.fullName.split(" ")[0];
  const greeting =
    hour < 12 ? t("Good morning, {name}", { name: first }) : hour < 18 ? t("Good afternoon, {name}", { name: first }) : t("Good evening, {name}", { name: first });
  const time = updatedAt ? new Date(updatedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : "";
  return (
    <header className="flex flex-wrap items-end justify-between gap-3">
      <div>
        <p className="text-sm text-muted">
          {store ? shortDate(todayIn(store.timezone)) : ""} · {user.branchName}
        </p>
        <h1 className="mt-1 text-[28px] leading-tight font-semibold tracking-tight sm:text-[32px]">{greeting}</h1>
      </div>
      {time && (
        <p className="flex items-center gap-2 text-xs text-muted" aria-live="polite">
          <span className={cx("dash-live relative size-2 rounded-full", fetching ? "bg-warning" : "bg-success")} aria-hidden />
          {t("Updated {time}", { time })}
        </p>
      )}
    </header>
  );
}

function DashboardBody({ data, days }: { data: Dashboard; days: number }) {
  const t = useT();
  const can = useCan();
  const noVat = useStoreSettings().data?.taxMode === "NONE";
  const [measure, setMeasure] = useState<Measure>("sales");
  const c = data.currency;
  const p = data.period;
  const prev = data.previous;
  const label = days === 1 ? t("today") : t("last {n} days", { n: days });
  const vs = days === 1 ? t("yesterday") : t("the {n} days before", { n: days });
  const actions = ACTIONS.filter((a) => can(...a.any));
  const showReports = can("REPORTS_SALES", "REPORTS_INVENTORY", "REPORTS_PURCHASING", "REPORTS_ACCOUNTING", "REPORTS_TAX");

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
      {/* Headline figures. */}
      <Hero data={data} days={days} label={label} vs={vs} noVat={noVat} />
      <Tile
        i={1}
        icon={Banknote}
        label={t("Net sales")}
        value={p.netSales}
        format={(v) => money(v, c)}
        change={change(p.netSales, prev.netSales)}
        vs={vs}
        sub={noVat ? t("After discounts and refunds") : t("Without tax · tax {tax}", { tax: money(p.tax, c) })}
        trend={data.daily.map((d) => d.sales)}
      />
      <Tile
        i={2}
        icon={PiggyBank}
        label={t("Gross profit")}
        value={p.profit}
        format={(v) => money(v, c)}
        change={change(p.profit, prev.profit)}
        vs={vs}
        sub={p.margin == null ? t("No sales") : t("{margin}% margin · cost {cost}", { margin: p.margin, cost: money(p.cost, c) })}
        meter={p.margin}
        trend={data.daily.map((d) => d.profit)}
      />
      <Tile
        i={3}
        icon={ShoppingBag}
        label={t("Orders")}
        value={p.orders}
        format={(v) => String(Math.round(v))}
        change={change(p.orders, prev.orders)}
        vs={vs}
        sub={t("Average {average} · {n} units", { average: money(p.averageOrder, c), n: p.items })}
        trend={data.daily.map((d) => d.orders)}
      />
      <Tile
        i={4}
        icon={RotateCcw}
        label={t("Refunds")}
        value={p.refunds}
        format={(v) => money(v, c)}
        sub={p.returns === 1 ? t("1 return") : t("{n} returns", { n: p.returns })}
        warn={p.refunds > 0}
      />

      {actions.length > 0 && (
        <Panel i={5} className="md:col-span-2 xl:col-span-4" title={t("Quick actions")}>
          <ul className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-6">
            {actions.slice(0, 12).map(({ href, label: l, icon: Icon }) => (
              <li key={href}>
                <Link
                  href={href}
                  className="group flex h-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium ring-1 ring-border transition-[background-color,box-shadow,transform] duration-200 hover:-translate-y-px hover:bg-surface-2 hover:shadow-sm focus-visible:outline-2 focus-visible:outline-primary"
                >
                  <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-primary-soft text-primary transition-colors group-hover:bg-primary group-hover:text-on-primary">
                    <Icon className="size-4" aria-hidden />
                  </span>
                  <span className="min-w-0 leading-tight">{t(l)}</span>
                </Link>
              </li>
            ))}
          </ul>
        </Panel>
      )}

      {/* The trend, and what needs attention beside it. */}
      <Panel
        i={6}
        className="md:col-span-2 xl:col-span-3"
        title={t("Sales trend")}
        note={
          noVat
            ? t("By the day orders were paid, less refunds on the day they were made (a day with more refunds than sales shows no bar).")
            : t("Tax included, by the day orders were paid, less refunds on the day they were made (a day with more refunds than sales shows no bar).")
        }
        action={
          <div role="group" aria-label={t("Show")} className="inline-flex rounded-full bg-surface-2 p-0.5 ring-1 ring-border">
            {MEASURES.map((m) => (
              <button
                key={m.key}
                type="button"
                aria-pressed={measure === m.key}
                onClick={() => setMeasure(m.key)}
                className={cx(
                  "rounded-full px-3 py-1 text-xs font-medium transition-colors focus-visible:outline-2 focus-visible:outline-primary",
                  measure === m.key ? "bg-surface text-text shadow-sm ring-1 ring-border" : "text-muted hover:text-text",
                )}
              >
                {t(m.label)}
              </button>
            ))}
          </div>
        }
      >
        <TrendChart days={data.daily} currency={c} measure={measure} />
      </Panel>
      <Attention i={7} data={data} />

      {/* What sells and how people pay. */}
      <TopSellers i={8} data={data} label={label} noVat={noVat} />
      <PaymentMix i={9} data={data} label={label} />
      <CashDrawer i={10} data={data} label={label} />

      {/* Stock and customers. */}
      <LowStock i={11} data={data} />
      <TopCustomers i={12} data={data} label={label} />

      {showReports && <ReportsPanel i={13} />}
    </div>
  );
}

function Hero({ data, days, label, vs, noVat }: { data: Dashboard; days: number; label: string; vs: string; noVat: boolean }) {
  const t = useT();
  const c = data.currency;
  const p = data.period;
  const shown = useCountUp(p.sales);
  const pct = change(p.sales, data.previous.sales);
  return (
    <section
      className="dash-hero dash-in relative isolate flex min-h-[300px] flex-col overflow-hidden rounded-[24px] p-6 shadow-[0_24px_48px_-24px_rgb(7_11_20/0.6)] md:col-span-2 md:row-span-2 sm:p-7"
      style={{ "--i": 0 } as React.CSSProperties}
      aria-label={t("Sales, {label}", { label })}
    >
      <div aria-hidden className="dash-hero-grid pointer-events-none absolute inset-0 -z-10" />
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-sm font-medium text-white/70">{t("Sales, {label}", { label })}</h2>
          <p className="mt-0.5 text-xs text-white/50">{noVat ? t("After refunds") : t("Tax included, after refunds")}</p>
        </div>
        {pct != null && <ChangePill pct={pct} vs={vs} dark />}
      </div>
      <p className="mt-5 text-5xl font-semibold tracking-tight sm:text-6xl">{money(shown, c)}</p>
      <div className="mt-auto pt-6">
        <Sparkline values={data.daily.map((d) => d.sales)} tone="#8fb8ff" className="h-24 w-full" />
        <dl className="mt-4 grid grid-cols-2 gap-4 border-t border-white/10 pt-4 text-sm">
          {days !== 1 ? (
            <div>
              <dt className="text-xs text-white/55">{t("Today")}</dt>
              <dd className="mt-0.5 font-medium tabular-nums">
                {money(data.today.sales, c)} <span className="text-white/55">· {data.today.orders === 1 ? t("1 order") : t("{n} orders", { n: data.today.orders })}</span>
              </dd>
            </div>
          ) : (
            <div>
              <dt className="text-xs text-white/55">{t("Average order")}</dt>
              <dd className="mt-0.5 font-medium tabular-nums">{money(p.averageOrder, c)}</dd>
            </div>
          )}
          <div>
            <dt className="text-xs text-white/55">{t("Discounts given")}</dt>
            <dd className="mt-0.5 font-medium tabular-nums">{money(p.discounts, c)}</dd>
          </div>
        </dl>
      </div>
    </section>
  );
}

function Tile({
  i,
  icon: Icon,
  label,
  value,
  format,
  sub,
  change: pct,
  vs,
  warn,
  meter,
  trend,
}: {
  i: number;
  icon: typeof Banknote;
  label: string;
  value: number;
  format: (v: number) => string;
  sub?: string;
  change?: number | null;
  vs?: string;
  warn?: boolean;
  meter?: number | null;
  trend?: number[];
}) {
  const shown = useCountUp(value);
  return (
    <section
      className="dash-in group relative flex flex-col overflow-hidden rounded-[20px] border border-border bg-surface p-5 transition-[box-shadow,transform] duration-300 hover:-translate-y-0.5 hover:shadow-[0_12px_32px_-16px_rgb(0_0_0/0.25)]"
      style={{ "--i": i } as React.CSSProperties}
    >
      <div className="flex items-start justify-between gap-2">
        <h2 className="text-sm text-muted">{label}</h2>
        <span className={cx("grid size-8 place-items-center rounded-lg", warn ? "bg-warning-soft text-warning" : "bg-surface-2 text-muted")}>
          <Icon className="size-4" aria-hidden />
        </span>
      </div>
      <p className={cx("mt-2 text-[28px] leading-tight font-semibold tracking-tight", warn && "text-warning")}>{format(shown)}</p>
      {pct != null && vs && (
        <div className="mt-1">
          <ChangePill pct={pct} vs={vs} />
        </div>
      )}
      {meter != null && (
        <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-surface-2" aria-hidden>
          <div className="dash-grow-x h-full rounded-full bg-chart-1" style={{ width: `${Math.min(100, Math.max(0, meter))}%` }} />
        </div>
      )}
      {sub && <p className="mt-2 text-xs text-muted">{sub}</p>}
      {trend && trend.length > 1 && <Sparkline values={trend} className="mt-auto h-9 w-full pt-2 opacity-80 transition-opacity group-hover:opacity-100" />}
    </section>
  );
}

/** "↑ 12.5% vs …": the arrow and the words carry the direction, not just the colour. */
function ChangePill({ pct, vs, dark }: { pct: number; vs: string; dark?: boolean }) {
  const t = useT();
  const up = pct >= 0;
  return (
    <span
      title={t("Compared with {vs}", { vs })}
      className={cx(
        "inline-flex items-center gap-0.5 rounded-full px-2 py-0.5 text-xs font-medium",
        dark ? (up ? "bg-emerald-400/15 text-emerald-300" : "bg-rose-400/15 text-rose-300") : up ? "bg-success-soft text-success" : "bg-danger-soft text-danger",
      )}
    >
      {up ? <ArrowUpRight className="size-3.5" aria-hidden /> : <ArrowDownRight className="size-3.5" aria-hidden />}
      {t("{pct}% vs {vs}", { pct: Math.abs(pct).toFixed(1), vs })}
    </span>
  );
}

function Panel({
  i,
  title,
  note,
  action,
  className,
  children,
}: {
  i: number;
  title: string;
  note?: string;
  action?: React.ReactNode;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <section className={cx("dash-in min-w-0 rounded-[20px] border border-border bg-surface p-5", className)} style={{ "--i": i } as React.CSSProperties}>
      <div className="mb-4 flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="font-semibold tracking-tight">{title}</h2>
          {note && <p className="mt-0.5 max-w-2xl text-xs text-muted">{note}</p>}
        </div>
        {action}
      </div>
      {children}
    </section>
  );
}

/** Things to act on: low stock, transfers to receive, unpaid KHQR orders, vendor bills. */
function Attention({ i, data }: { i: number; data: Dashboard }) {
  const t = useT();
  const c = data.currency;
  const items: { key: string; href: string; icon: typeof Truck; text: string; tone: "warning" | "danger" | "primary" | "neutral" }[] = [];
  if (data.lowStock > 0) {
    items.push({
      key: "low",
      href: "/catalog?lowStock=1",
      icon: AlertTriangle,
      tone: "warning",
      text: data.lowStock === 1 ? t("1 product or item is low on stock") : t("{n} products or items are low on stock", { n: data.lowStock }),
    });
  }
  if (data.incomingTransfers > 0) {
    items.push({
      key: "tr",
      href: "/inventory?tab=transfers",
      icon: Truck,
      tone: "primary",
      text: data.incomingTransfers === 1 ? t("1 transfer waiting to be received") : t("{n} transfers waiting to be received", { n: data.incomingTransfers }),
    });
  }
  if (data.pendingOrders > 0) {
    items.push({
      key: "pending",
      href: "/orders?tab=invoices&status=PENDING_PAYMENT",
      icon: Clock,
      tone: "neutral",
      text: data.pendingOrders === 1 ? t("1 order awaiting KHQR payment") : t("{n} orders awaiting KHQR payment", { n: data.pendingOrders }),
    });
  }
  if (data.payables && data.payables.owed > 0) {
    items.push({
      key: "pay",
      href: "/purchasing?tab=invoices",
      icon: FileText,
      tone: data.payables.overdue > 0 ? "danger" : "neutral",
      text:
        data.payables.overdue > 0
          ? t("{owed} owed to vendors, {overdue} overdue", { owed: money(data.payables.owed, c), overdue: money(data.payables.overdue, c) })
          : t("{owed} owed to vendors", { owed: money(data.payables.owed, c) }),
    });
  }
  if (data.payables && data.payables.openOrders > 0) {
    items.push({
      key: "po",
      href: "/purchasing?tab=orders",
      icon: ClipboardList,
      tone: "neutral",
      text: data.payables.openOrders === 1 ? t("1 purchase order to receive") : t("{n} purchase orders to receive", { n: data.payables.openOrders }),
    });
  }
  const tones = {
    warning: "bg-warning-soft text-warning",
    danger: "bg-danger-soft text-danger",
    primary: "bg-primary-soft text-primary",
    neutral: "bg-surface-2 text-muted",
  };
  return (
    <Panel i={i} className="md:col-span-2 xl:col-span-1" title={t("Needs attention")}>
      {items.length === 0 ? (
        <div className="grid place-items-center gap-2 py-8 text-center">
          <CheckCircle2 className="size-9 text-success" aria-hidden />
          <p className="font-medium">{t("All clear")}</p>
          <p className="text-sm text-muted">{t("Nothing needs your attention right now.")}</p>
        </div>
      ) : (
        <ul className="-mx-2 flex flex-col gap-1">
          {items.map(({ key, href, icon: Icon, text, tone }) => (
            <li key={key}>
              <Link
                href={href}
                className="group flex items-center gap-3 rounded-xl px-2 py-2.5 text-sm transition-colors hover:bg-surface-2 focus-visible:outline-2 focus-visible:outline-primary"
              >
                <span className={cx("grid size-9 shrink-0 place-items-center rounded-full", tones[tone])}>
                  <Icon className="size-4" aria-hidden />
                </span>
                <span className="min-w-0 flex-1">{text}</span>
                <ChevronRight className="size-4 shrink-0 text-muted transition-transform group-hover:translate-x-0.5" aria-hidden />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function TopSellers({ i, data, label, noVat }: { i: number; data: Dashboard; label: string; noVat: boolean }) {
  const t = useT();
  const c = data.currency;
  const max = Math.max(...data.topSellers.map((x) => x.netSales), 0);
  return (
    <Panel
      i={i}
      className="md:col-span-2"
      title={t("Top sellers, {label}", { label })}
      note={noVat ? t("Units kept (sold less returned).") : t("Units kept (sold less returned); net sales without tax.")}
    >
      {data.topSellers.length === 0 ? (
        <p className="text-sm text-muted">{t("No sales yet.")}</p>
      ) : (
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-muted">
            <tr>
              <th className="pb-2 font-medium">{t("Name")}</th>
              <th className="hidden pb-2 text-right font-medium sm:table-cell">{t("Units")}</th>
              <th className="pb-2 text-right font-medium">{t("Net sales")}</th>
              <th className="hidden pb-2 text-right font-medium sm:table-cell">{t("Profit")}</th>
            </tr>
          </thead>
          <tbody>
            {data.topSellers.map((x, n) => (
              <tr key={x.offeringId} className="border-t border-border transition-colors hover:bg-surface-2/60">
                <td className="py-2.5 pr-3">
                  <div className="flex items-center gap-3">
                    <span className="w-4 shrink-0 text-xs font-semibold text-muted tabular-nums">{n + 1}</span>
                    <div className="min-w-0 flex-1">
                      <div className="truncate font-medium">{x.name}</div>
                      <div className="mt-1 flex items-center gap-2">
                        <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-2" aria-hidden>
                          <div
                            className="dash-grow-x h-full rounded-full bg-chart-1"
                            style={{ width: `${max ? (x.netSales / max) * 100 : 0}%`, "--i": n } as React.CSSProperties}
                          />
                        </div>
                        <span className="text-[11px] whitespace-nowrap text-muted">{t(TYPE_LABELS[x.type])}</span>
                      </div>
                    </div>
                  </div>
                </td>
                <td className="hidden py-2.5 text-right tabular-nums sm:table-cell">{x.quantity}</td>
                <td className="py-2.5 text-right font-medium tabular-nums">{money(x.netSales, c)}</td>
                <td className="hidden py-2.5 text-right text-muted tabular-nums sm:table-cell">{money(x.profit, c)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}

/** How the money came in: one whole split into its methods, as one segmented bar with a labelled legend. */
function PaymentMix({ i, data, label }: { i: number; data: Dashboard; label: string }) {
  const t = useT();
  const c = data.currency;
  const total = data.paymentMethods.reduce((sum, x) => sum + x.amount, 0);
  // Colour follows the method, whatever order the server sends them in.
  const color = (method: string) => (method === "KHQR" ? "bg-chart-2" : "bg-chart-1");
  const name = (method: string) => (method === "KHQR" ? "KHQR" : t("Cash"));
  return (
    <Panel i={i} title={t("Money received, {label}", { label })}>
      {data.paymentMethods.length === 0 ? (
        <p className="text-sm text-muted">{t("No payments yet.")}</p>
      ) : (
        <>
          <div className="flex h-3 gap-0.5 overflow-hidden rounded-full" aria-hidden>
            {data.paymentMethods.map((x, n) => (
              <div
                key={x.method}
                className={cx("dash-grow-x h-full first:rounded-l-full last:rounded-r-full", color(x.method))}
                style={{ width: `${total ? (x.amount / total) * 100 : 0}%`, "--i": n } as React.CSSProperties}
              />
            ))}
          </div>
          <ul className="mt-4 flex flex-col gap-3 text-sm">
            {data.paymentMethods.map((x) => (
              <li key={x.method} className="flex items-center gap-2.5">
                <span className={cx("size-2.5 shrink-0 rounded-sm", color(x.method))} aria-hidden />
                <span className="flex-1 font-medium">
                  {name(x.method)} <span className="font-normal text-muted">{total ? `${Math.round((x.amount / total) * 100)}%` : ""}</span>
                </span>
                <span className="text-right tabular-nums">
                  {money(x.amount, c)}
                  <span className="block text-xs text-muted">{t("· {count} payments", { count: x.count })}</span>
                </span>
              </li>
            ))}
          </ul>
          <dl className="mt-4 flex flex-col gap-2 border-t border-border pt-3 text-sm">
            {data.refundsPaid > 0 && (
              <div className="flex justify-between">
                <dt className="text-muted">{t("Refunds paid out")}</dt>
                <dd className="text-danger tabular-nums">-{money(data.refundsPaid, c)}</dd>
              </div>
            )}
            <div className="flex justify-between font-semibold">
              <dt>{t("Kept")}</dt>
              <dd className="tabular-nums">{money(total - data.refundsPaid, c)}</dd>
            </div>
          </dl>
        </>
      )}
    </Panel>
  );
}

function CashDrawer({ i, data, label }: { i: number; data: Dashboard; label: string }) {
  const t = useT();
  const currencies = useCurrencies().data ?? [];
  return (
    <Panel
      i={i}
      title={t("Cash drawer by currency, {label}", { label })}
      note={t("What customers handed over and what went back as change, per currency. Refunds paid in cash ({refundsPaid}) are not split by currency.", {
        refundsPaid: money(data.refundsPaid, data.currency),
      })}
    >
      {data.cashByCurrency.length === 0 ? (
        <p className="text-sm text-muted">{t("No cash payments yet.")}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {data.cashByCurrency.map((row) => {
            const cur = currencies.find((x) => x.code === row.currency);
            const show = (n: number) => (cur ? amountIn(n, cur) : `${n} ${row.currency}`);
            return (
              <li key={row.currency} className="rounded-xl bg-surface-2 px-3 py-2.5 text-sm">
                <div className="flex items-baseline justify-between gap-2">
                  <span className="font-semibold">{row.currency}</span>
                  <span className="font-semibold tabular-nums">{show(row.net)}</span>
                </div>
                <div className="mt-1 flex justify-between gap-2 text-xs text-muted">
                  <span>
                    {t("Received")} <span className="tabular-nums">{show(row.received)}</span>
                  </span>
                  <span>
                    {t("Change given")} <span className="tabular-nums">{show(row.changeGiven)}</span>
                  </span>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </Panel>
  );
}

function LowStock({ i, data }: { i: number; data: Dashboard }) {
  const t = useT();
  return (
    <Panel
      i={i}
      className="md:col-span-2"
      title={t("Low on stock")}
      action={
        data.lowStock > data.lowStockItems.length ? (
          <Link href="/catalog?lowStock=1" className="text-sm font-medium text-primary hover:underline">
            {t("See all {lowStock}", { lowStock: data.lowStock })}
          </Link>
        ) : undefined
      }
    >
      {data.lowStockItems.length === 0 ? (
        <p className="text-sm text-muted">{t("Nothing is below its low-stock level.")}</p>
      ) : (
        <ul className="flex flex-col gap-3 text-sm">
          {data.lowStockItems.map((l, n) => {
            const out = l.stock <= 0;
            return (
              <li key={l.offeringId}>
                <div className="flex items-baseline justify-between gap-3">
                  <span className="flex min-w-0 items-center gap-1.5">
                    {out && <AlertTriangle className="size-3.5 shrink-0 text-danger" aria-label={t("Out of stock")} />}
                    <span className="truncate">{l.name}</span>
                  </span>
                  <span className={cx("shrink-0 tabular-nums", out ? "font-medium text-danger" : "text-warning")}>
                    {l.stock} {l.unit ?? ""} <span className="text-xs text-muted">/ {l.threshold}</span>
                  </span>
                </div>
                <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-surface-2" aria-hidden>
                  <div
                    className={cx("dash-grow-x h-full rounded-full", out ? "bg-danger" : "bg-warning")}
                    style={{ width: `${l.threshold > 0 ? Math.min(100, Math.max(2, (l.stock / l.threshold) * 100)) : 0}%`, "--i": n } as React.CSSProperties}
                  />
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </Panel>
  );
}

function TopCustomers({ i, data, label }: { i: number; data: Dashboard; label: string }) {
  const t = useT();
  const c = data.currency;
  return (
    <Panel
      i={i}
      className="md:col-span-2"
      title={t("Customers, {label}", { label })}
      action={
        <span className="rounded-full bg-primary-soft px-2.5 py-1 text-xs font-medium text-primary">
          {data.newCustomers === 1 ? t("1 new customer added") : t("{n} new customers added", { n: data.newCustomers })}
        </span>
      }
    >
      {data.topCustomers.length === 0 ? (
        <p className="text-sm text-muted">{t("No sales to named customers yet (walk-in sales are not listed).")}</p>
      ) : (
        <ul className="-mx-2 flex flex-col">
          {data.topCustomers.map((x) => (
            <li key={x.customerId}>
              <Link
                href={`/customers/${x.customerId}`}
                className="group flex items-center gap-3 rounded-xl px-2 py-2 text-sm transition-colors hover:bg-surface-2 focus-visible:outline-2 focus-visible:outline-primary"
              >
                <span className="grid size-9 shrink-0 place-items-center rounded-full bg-linear-to-br from-primary-soft to-surface-2 text-xs font-semibold text-primary ring-1 ring-border">
                  {initials(x.name)}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium group-hover:text-primary">{x.name}</span>
                  <span className="text-xs text-muted">{x.orders === 1 ? t("1 order") : t("{n} orders", { n: x.orders })}</span>
                </span>
                <span className="shrink-0 font-medium tabular-nums">{money(x.spent, c)}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

/** Shortcuts into Reports: only the reports this role may open (the server lists them). */
function ReportsPanel({ i }: { i: number }) {
  const t = useT();
  const can = useCan();
  const taxMode = useStoreSettings().data?.taxMode;
  const catalog = useQuery({ queryKey: ["reports", "catalog"], queryFn: () => api<ReportInfo[]>("/reports/catalog") });
  const groups = new Map<string, ReportInfo[]>();
  for (const r of catalog.data ?? []) groups.set(r.group, [...(groups.get(r.group) ?? []), r]);
  const tax = can("REPORTS_TAX") && taxMode !== "NONE";
  if (!catalog.data?.length && !tax) return null;
  return (
    <Panel
      i={i}
      className="md:col-span-2 xl:col-span-4"
      title={t("Reports")}
      action={
        <Link href="/reports" className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline">
          {t("All reports")}
          <ChevronRight className="size-4" aria-hidden />
        </Link>
      }
    >
      <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
        {[...groups.entries()].map(([group, reports]) => (
          // A long group (Sales) takes two columns, so the panel stays even.
          <div key={group} className={cx(reports.length > 6 && "sm:col-span-2")}>
            <h3 className="mb-1.5 text-xs font-semibold tracking-wide text-muted uppercase">{translateServer(group)}</h3>
            <ul className={cx("-mx-2", reports.length > 6 && "sm:columns-2 sm:gap-6")}>
              {reports.map((r) => (
                <li key={r.key}>
                  <Link
                    href={`/reports?r=${r.key}`}
                    title={translateServer(r.description)}
                    className="group flex items-center justify-between gap-2 rounded-lg px-2 py-1.5 text-sm transition-colors hover:bg-surface-2 focus-visible:outline-2 focus-visible:outline-primary"
                  >
                    <span className="truncate">{translateServer(r.title)}</span>
                    <ChevronRight className="size-3.5 shrink-0 text-muted opacity-0 transition-opacity group-hover:opacity-100" aria-hidden />
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ))}
        {tax && (
          <div>
            <h3 className="mb-1.5 text-xs font-semibold tracking-wide text-muted uppercase">{t("Tax")}</h3>
            <Link
              href="/reports/tax"
              className="-mx-2 flex items-center justify-between gap-2 rounded-lg px-2 py-1.5 text-sm transition-colors hover:bg-surface-2 focus-visible:outline-2 focus-visible:outline-primary"
            >
              {t("Tax report")}
              <ChevronRight className="size-3.5 text-muted" aria-hidden />
            </Link>
          </div>
        )}
      </div>
    </Panel>
  );
}

function Skeleton() {
  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4" aria-hidden>
      <div className="dash-skeleton h-[300px] rounded-[24px] md:col-span-2 md:row-span-2 md:h-auto" />
      {[0, 1, 2, 3].map((n) => (
        <div key={n} className="dash-skeleton h-40 rounded-[20px]" />
      ))}
      <div className="dash-skeleton h-72 rounded-[20px] md:col-span-2 xl:col-span-3" />
      <div className="dash-skeleton h-72 rounded-[20px] md:col-span-2 xl:col-span-1" />
    </div>
  );
}

/** % change against the previous period; null when there is nothing to compare with. */
function change(now: number, before: number) {
  if (!before) return null;
  return ((now - before) / Math.abs(before)) * 100;
}

function initials(name: string) {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part[0])
    .slice(0, 2)
    .join("")
    .toUpperCase();
}
