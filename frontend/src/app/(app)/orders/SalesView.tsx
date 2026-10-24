"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { PageHeader, cx } from "@/components/ui";
import { hasPermission, useUser } from "@/lib/auth";
import type { Permission } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

import { CustomersView } from "../customers/CustomersView";
import { OrdersList } from "./OrdersList";
import { ReturnsTab } from "./ReturnsTab";

const TABS: { key: "invoices" | "returns" | "customers"; label: string; any: Permission[] }[] = [
  { key: "invoices", label: msg("Invoices"), any: ["ORDERS_VIEW"] },
  { key: "returns", label: msg("Returns & refunds"), any: ["ORDERS_VIEW"] },
  { key: "customers", label: msg("Customers"), any: ["CUSTOMERS_VIEW"] },
];

/** Sales: invoices (orders), returns and refunds, and customers, each tab shown if the role can open it. */
export function SalesView() {
  const t = useT();
  const user = useUser();
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const tabs = TABS.filter((x) => hasPermission(user, ...x.any));
  const tab = tabs.find((x) => x.key === params.get("tab")) ?? tabs[0];

  return (
    <div className="mx-auto max-w-5xl p-4 lg:p-8">
      <PageHeader title={t("Sales")} />
      {!tab ? (
        <p className="text-sm text-muted">{t("Your role can't see sales records.")}</p>
      ) : (
        <>
          <div className="mb-4 flex gap-1 overflow-x-auto border-b border-border" role="tablist" aria-label={t("Sales")}>
            {tabs.map((x) => (
              <button
                key={x.key}
                role="tab"
                aria-selected={tab.key === x.key}
                onClick={() => router.replace(`${pathname}?tab=${x.key}`)}
                className={cx(
                  "-mb-px shrink-0 border-b-2 px-4 py-2 text-sm font-medium",
                  tab.key === x.key ? "border-primary text-primary" : "border-transparent text-muted hover:text-text",
                )}
              >
                {t(x.label)}
              </button>
            ))}
          </div>
          {tab.key === "invoices" && <OrdersList />}
          {tab.key === "returns" && <ReturnsTab />}
          {tab.key === "customers" && <CustomersView />}
        </>
      )}
    </div>
  );
}
