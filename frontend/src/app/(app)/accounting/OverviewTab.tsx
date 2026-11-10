"use client";

import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, ArrowRight, CheckCircle2, Landmark } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { Button, Card, ErrorNote, Spinner, cx } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { money, shortDate } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { AccountingOverview } from "@/lib/types";
import { useT } from "@/lib/i18n";

import { BranchFilter } from "../inventory/BranchFilter";
import { accountName } from "./shared";

/** The books at a glance: where the money is, this month's profit, what is owed, and a stock check. */
export function OverviewTab({ onGo }: { onGo: (tab: string) => void }) {
  const t = useT();
  const can = useCan();
  const store = useStoreSettings();
  const [branchId, setBranchId] = useState("");
  const overview = useQuery({
    queryKey: ["accounting", "overview", branchId],
    queryFn: () => api<AccountingOverview>(`/accounting/overview${queryString({ branchId })}`),
  });
  const currency = store.data?.currency;
  if (overview.isPending) return <Spinner />;
  if (overview.error) return <ErrorNote error={overview.error} />;
  const o = overview.data;

  if (!o.settings.started) {
    return (
      <Card className="flex flex-col items-start gap-3 p-6">
        <span className="grid size-10 place-items-center rounded-xl bg-primary-soft text-primary">
          <Landmark className="size-5" aria-hidden />
        </span>
        <h2 className="text-lg font-semibold">{t("The books haven't been started yet")}</h2>
        <p className="max-w-2xl text-sm text-muted">
          {t("Choose the day the books start and enter the opening balances (cash, bank, stock and what is owed). From then on, sales, returns, deliveries, vendor invoices and payments, and stock changes post on their own.")}
        </p>
        {can("ACCOUNTING_MANAGE") ? (
          <Button onClick={() => onGo("setup")}>
            {t("Start the books")} <ArrowRight className="size-4" aria-hidden />
          </Button>
        ) : (
          <p className="text-sm text-muted">{t("Ask an accountant or admin to start them.")}</p>
        )}
      </Card>
    );
  }

  const diff = Math.round((o.inventoryBooks - o.inventoryStock) * 100) / 100;
  const tiles = [
    { label: t("Income this month"), value: o.monthIncome, hint: t("Sales and other income, without VAT") },
    { label: t("Cost of sales"), value: o.monthCostOfSales, hint: t("Stock sold at cost, stock adjustments") },
    { label: t("Expenses"), value: o.monthExpenses, hint: t("Rent, salaries, utilities, other expenses") },
    { label: t("Profit this month"), value: o.monthProfit, hint: t("Since {date}", { date: shortDate(o.monthStart) }), strong: true },
  ];

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted">
          {o.settings.mode === "FULL" ? t("Full accounting") : t("Normal accounting")} ·{" "}
          {t("Books started {date}", { date: shortDate(o.settings.startDate!) })}
          {o.settings.lockDate && <> · {t("closed up to {date}", { date: shortDate(o.settings.lockDate) })}</>}
        </p>
        <BranchFilter value={branchId} onChange={setBranchId} />
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {tiles.map((x) => (
          <Card key={x.label} className="p-4">
            <p className="text-xs text-muted">{x.label}</p>
            <p className={cx("mt-1 text-2xl font-semibold tabular-nums", x.strong && x.value < 0 && "text-danger")}>{money(x.value, currency)}</p>
            <p className="mt-1 text-xs text-muted">{x.hint}</p>
          </Card>
        ))}
      </div>

      <div className="grid gap-5 lg:grid-cols-[1.4fr_1fr]">
        <Card className="p-4">
          <h2 className="mb-3 text-sm font-semibold">{t("Cash and bank")}</h2>
          {o.money.length === 0 ? (
            <p className="text-sm text-muted">{t("No cash or bank accounts.")}</p>
          ) : (
            <ul className="divide-y divide-border">
              {o.money.map((a) => (
                <li key={a.id} className="flex items-center justify-between gap-3 py-2 text-sm">
                  <span className="min-w-0 truncate">
                    <span className="text-muted tabular-nums">{a.code}</span> {accountName(a.name)}
                  </span>
                  <span className={cx("font-medium tabular-nums", a.balance < 0 && "text-danger")}>{money(a.balance, currency)}</span>
                </li>
              ))}
              <li className="flex items-center justify-between gap-3 py-2 text-sm font-semibold">
                <span>{t("Total")}</span>
                <span className="tabular-nums">{money(o.money.reduce((s, a) => s + a.balance, 0), currency)}</span>
              </li>
            </ul>
          )}
        </Card>

        <div className="flex flex-col gap-3">
          <Card className="flex items-center justify-between gap-3 p-4 text-sm">
            <span>
              <span className="block font-medium">{t("Owed to vendors")}</span>
              <span className="text-xs text-muted">{t("Accounts payable")}</span>
            </span>
            <span className="text-lg font-semibold tabular-nums">{money(o.payables, currency)}</span>
          </Card>
          <Card className="flex items-center justify-between gap-3 p-4 text-sm">
            <span>
              <span className="block font-medium">{o.vatOwed >= 0 ? t("VAT to pay") : t("VAT to reclaim")}</span>
              <span className="text-xs text-muted">{t("VAT on sales less VAT on purchases and expenses")}</span>
            </span>
            <span className="text-lg font-semibold tabular-nums">{money(Math.abs(o.vatOwed), currency)}</span>
          </Card>
          <Card className="flex items-start gap-3 p-4 text-sm">
            {diff === 0 ? (
              <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-success" aria-hidden />
            ) : (
              <AlertTriangle className="mt-0.5 size-5 shrink-0 text-warning" aria-hidden />
            )}
            <span className="min-w-0">
              <span className="block font-medium">
                {diff === 0 ? t("Inventory matches the stock") : t("Inventory differs from the stock by {amount}", { amount: money(diff, currency) })}
              </span>
              <span className="text-xs text-muted">
                {t("Inventory account {books} · stock valuation {stock}", { books: money(o.inventoryBooks, currency), stock: money(o.inventoryStock, currency) })}
                {diff !== 0 && <> · {t("KHQR sales waiting for payment and stock in transit can cause a short-lived difference.")}</>}
              </span>
            </span>
          </Card>
        </div>
      </div>

      {can("REPORTS_ACCOUNTING") && (
        <div className="flex flex-wrap gap-2 text-sm">
          {[
            ["profit-and-loss", t("Profit and loss")],
            ...(o.settings.mode === "FULL"
              ? [
                  ["balance-sheet", t("Balance sheet")],
                  ["trial-balance", t("Trial balance")],
                ]
              : []),
            ["other-income-expenses", t("Other income and expenses")],
          ].map(([key, label]) => (
            <Link key={key} href={`/reports?r=${key}`} className="rounded-full border border-border px-3 py-1.5 hover:bg-surface-2">
              {label}
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
