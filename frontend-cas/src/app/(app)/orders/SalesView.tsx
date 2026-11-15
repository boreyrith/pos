"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { PageHeader, cx } from "@pos/shared/components/ui";
import { hasPermission, useUser } from "@pos/shared/auth/auth";
import type { Permission } from "@pos/shared/types";
import { msg, useT } from "@pos/shared/i18n";

import { OrdersList } from "@/app/(app)/orders/OrdersList";
import { ReturnsTab } from "@/app/(app)/orders/ReturnsTab";

const TABS: { key: "invoices" | "returns"; label: string; any: Permission[] }[] = [
  { key: "invoices", label: msg("Invoices"), any: ["ORDERS_VIEW"] },
  { key: "returns", label: msg("Returns & refunds"), any: ["ORDERS_VIEW"] },
];

/** Sales: invoices (orders) and returns and refunds, each tab shown if the role can open it. Customers have their own page. */
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
        </>
      )}
    </div>
  );
}
