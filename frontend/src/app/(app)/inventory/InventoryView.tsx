"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { PageHeader, cx } from "@/components/ui";
import { msg, useT } from "@/lib/i18n";

import { AdjustmentsTab } from "./AdjustmentsTab";
import { MovementsTab } from "./MovementsTab";
import { TransfersTab } from "./TransfersTab";

const TABS = [
  { key: "transfers", label: msg("Transfers"), hint: msg("Move stock between storages and branches") },
  { key: "adjustments", label: msg("Adjustments"), hint: msg("Fix stock that differs from what is on the shelf, and reconcile stock counts") },
  { key: "history", label: msg("History"), hint: msg("Every stock change, with who and when") },
] as const;

export function InventoryView() {
  const t = useT();
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const tab = TABS.find((x) => x.key === params.get("tab")) ?? TABS[0];

  return (
    <div className="mx-auto max-w-6xl p-4 lg:p-8">
      <PageHeader title={t("Inventory")} />
      <div className="mb-1 flex gap-1 border-b border-border" role="tablist" aria-label={t("Inventory")}>
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
      <p className="mb-4 text-xs text-muted">
        {t(tab.hint)}. {t("Branches and storages are set up in")}{" "}
        <Link href="/settings?tab=branches" className="text-primary hover:underline">
          {t("Settings")}
        </Link>
        .
      </p>
      {tab.key === "transfers" && <TransfersTab />}
      {tab.key === "adjustments" && <AdjustmentsTab />}
      {tab.key === "history" && <MovementsTab />}
    </div>
  );
}
