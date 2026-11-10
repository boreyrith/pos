"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { cx } from "@/components/ui";
import { useCan } from "@/lib/auth";
import type { Permission } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

import { AccountsTab } from "./AccountsTab";
import { JournalTab } from "./JournalTab";
import { OtherTab } from "./OtherTab";
import { OverviewTab } from "./OverviewTab";
import { SetupTab } from "./SetupTab";
import { useAccountingSettings } from "./shared";

const TABS: { key: string; label: string; hint: string; needs?: Permission; fullOnly?: boolean }[] = [
  { key: "overview", label: msg("Overview"), hint: msg("Cash and bank balances, this month's profit, and what is owed") },
  { key: "other", label: msg("Income & expenses"), hint: msg("Money in or out that isn't a sale or a vendor invoice: rent, electricity, salaries, interest") },
  { key: "journal", label: msg("Journal"), hint: msg("Every entry in the books; sales, purchases and stock post on their own"), fullOnly: true },
  { key: "accounts", label: msg("Chart of accounts"), hint: msg("The accounts the books are kept in, with their balances and ledgers"), fullOnly: true },
  { key: "setup", label: msg("Setup"), hint: msg("Start the books with opening balances, and close finished periods"), needs: "ACCOUNTING_MANAGE" },
];

/** Accounting: the books every part of Munita posts to, other income and expenses, and the chart of accounts. */
export function AccountingView() {
  const t = useT();
  const can = useCan();
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const settings = useAccountingSettings();
  // Normal accounting keeps the journal and chart of accounts out of sight; full accounting shows them.
  const full = settings.data?.mode === "FULL";
  const tabs = TABS.filter((x) => (!x.needs || can(x.needs)) && (!x.fullOnly || full));
  const tab = tabs.find((x) => x.key === params.get("tab")) ?? tabs[0];
  const go = (key: string) => router.replace(`${pathname}?tab=${key}`);

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6 lg:px-8 lg:py-8">
      <h1 className="text-[28px] leading-tight font-semibold tracking-tight">{t("Accounting")}</h1>
      <div className="mt-4 mb-1 flex gap-1 overflow-x-auto border-b border-border" role="tablist" aria-label={t("Accounting")}>
        {tabs.map((x) => (
          <button
            key={x.key}
            role="tab"
            aria-selected={tab.key === x.key}
            onClick={() => go(x.key)}
            className={cx(
              "-mb-px border-b-2 px-4 py-2 text-sm font-medium whitespace-nowrap",
              tab.key === x.key ? "border-primary text-primary" : "border-transparent text-muted hover:text-text",
            )}
          >
            {t(x.label)}
          </button>
        ))}
      </div>
      <p className="mb-5 text-xs text-muted">{t(tab.hint)}.</p>
      {tab.key === "overview" && <OverviewTab onGo={go} />}
      {tab.key === "other" && <OtherTab />}
      {tab.key === "journal" && <JournalTab />}
      {tab.key === "accounts" && <AccountsTab />}
      {tab.key === "setup" && <SetupTab />}
    </div>
  );
}
