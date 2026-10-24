"use client";

import { useQuery } from "@tanstack/react-query";
import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { Card, PageHeader, cx } from "@/components/ui";
import { api } from "@/lib/api";
import { money } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { PayablesSummary } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

import { InvoicesTab } from "./InvoicesTab";
import { OrdersTab } from "./OrdersTab";
import { VendorsTab } from "./VendorsTab";

const TABS = [
  { key: "orders", label: msg("Purchase orders") },
  { key: "invoices", label: msg("Invoices & payments") },
  { key: "vendors", label: msg("Vendors") },
] as const;

/** Purchasing: what is on order, what is owed to vendors, and who they are. */
export function PurchasingView() {
  const t = useT();
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const store = useStoreSettings();
  const tab = TABS.find((x) => x.key === params.get("tab")) ?? TABS[0];
  const summary = useQuery({ queryKey: ["payables"], queryFn: () => api<PayablesSummary>("/vendor-invoices/summary") });
  const currency = store.data?.currency ?? "USD";

  return (
    <div className="mx-auto max-w-6xl p-4 lg:p-8">
      <PageHeader title={t("Purchasing")} />
      {summary.data && (
        <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
          <Stat label={t("Owed to vendors")} value={money(summary.data.owed, currency)} />
          <Stat label={t("Overdue")} value={money(summary.data.overdue, currency)} danger={summary.data.overdue > 0} />
          <Stat label={t("Unpaid invoices")} value={String(summary.data.unpaidInvoices)} />
          <Stat label={t("Orders to receive")} value={String(summary.data.openOrders)} />
        </div>
      )}
      <div className="mb-4 flex gap-1 border-b border-border" role="tablist" aria-label={t("Purchasing")}>
        {TABS.map((x) => (
          <button
            key={x.key}
            role="tab"
            aria-selected={tab.key === x.key}
            onClick={() => router.replace(`${pathname}?tab=${x.key}`)}
            className={cx(
              "-mb-px border-b-2 px-4 py-2 text-sm font-medium",
              tab.key === x.key ? "border-primary text-primary" : "border-transparent text-muted hover:text-text",
            )}
          >
            {t(x.label)}
          </button>
        ))}
      </div>
      {tab.key === "orders" && <OrdersTab />}
      {tab.key === "invoices" && <InvoicesTab />}
      {tab.key === "vendors" && <VendorsTab />}
    </div>
  );
}

function Stat({ label, value, danger }: { label: string; value: string; danger?: boolean }) {
  return (
    <Card className="p-4">
      <div className="text-xs text-muted">{label}</div>
      <div className={cx("mt-1 text-xl font-semibold tabular-nums", danger && "text-danger")}>{value}</div>
    </Card>
  );
}
