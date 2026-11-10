"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";

import { Select } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import type { AccountOption, AccountSection, AccountType, AccountingSettings, JournalSource } from "@/lib/types";
import { msg, translateServer, useT } from "@/lib/i18n";

export const ACCOUNT_TYPES: Record<AccountType, string> = {
  CASH: msg("Cash"),
  BANK: msg("Bank"),
  CURRENT_ASSET: msg("Current asset"),
  FIXED_ASSET: msg("Fixed asset"),
  CURRENT_LIABILITY: msg("Current liability"),
  LONG_TERM_LIABILITY: msg("Long-term liability"),
  EQUITY: msg("Equity"),
  REVENUE: msg("Revenue"),
  OTHER_INCOME: msg("Other income"),
  COST_OF_SALES: msg("Cost of sales"),
  EXPENSE: msg("Expense"),
  OTHER_EXPENSE: msg("Other expense"),
};

export const SECTIONS: { key: AccountSection; label: string }[] = [
  { key: "ASSET", label: msg("Assets") },
  { key: "LIABILITY", label: msg("Liabilities") },
  { key: "EQUITY", label: msg("Equity") },
  { key: "INCOME", label: msg("Income") },
  { key: "EXPENSE", label: msg("Costs and expenses") },
];

export const SOURCES: Record<JournalSource, string> = {
  SALE: msg("Sale"),
  SALE_RETURN: msg("Return"),
  GOODS_RECEIPT: msg("Delivery"),
  VENDOR_INVOICE: msg("Vendor invoice"),
  VENDOR_INVOICE_VOID: msg("Voided vendor invoice"),
  VENDOR_PAYMENT: msg("Vendor payment"),
  STOCK_ADJUSTMENT: msg("Stock adjustment"),
  OPENING_STOCK: msg("Opening stock"),
  REVALUATION: msg("Cost change"),
  OTHER_INCOME: msg("Other income"),
  OTHER_EXPENSE: msg("Other expense"),
  MANUAL: msg("Journal entry"),
  OPENING: msg("Opening balances"),
  REVERSAL: msg("Reversal"),
};

/** Where the document behind an entry is, if it has a page. */
export function sourceHref(source: JournalSource, sourceId: number | null): string | null {
  if (sourceId == null) return null;
  switch (source) {
    case "SALE":
    case "SALE_RETURN":
      return `/orders/${sourceId}`;
    case "GOODS_RECEIPT":
      return `/purchasing/orders/${sourceId}`;
    case "VENDOR_INVOICE":
    case "VENDOR_INVOICE_VOID":
    case "VENDOR_PAYMENT":
      return "/purchasing?tab=invoices";
    case "STOCK_ADJUSTMENT":
      return "/inventory?tab=adjustments";
    case "OTHER_INCOME":
    case "OTHER_EXPENSE":
      return "/accounting?tab=other";
    default:
      return null;
  }
}

/** Seeded account names are translated; the accountant's own are shown as typed. */
export const accountName = (name: string) => translateServer(name);

export const accountLabel = (a: { code: string; name: string }) => `${a.code} ${accountName(a.name)}`;

/** Active accounts for a picker: money (cash and bank), expense, income, or all. */
export function useAccountOptions(purpose: "money" | "expense" | "income" | "all", enabled = true) {
  return useQuery({
    queryKey: ["accounts", "options", purpose],
    queryFn: () => api<AccountOption[]>(`/accounting/accounts/options${queryString({ purpose })}`),
    staleTime: 60_000,
    enabled,
  });
}

export function useAccountingSettings(enabled = true) {
  return useQuery({ queryKey: ["accounting", "settings"], queryFn: () => api<AccountingSettings>("/accounting/settings"), enabled });
}

/** Invalidates everything the books show after a change. */
export function useRefreshAccounting() {
  const queryClient = useQueryClient();
  return () => {
    for (const key of ["accounting", "accounts", "journal", "ledger", "other-transactions"]) {
      queryClient.invalidateQueries({ queryKey: [key] });
    }
  };
}

/** A select of accounts; `empty` is the label of the "none chosen" choice (omit to require one). */
export function AccountSelect({
  options,
  value,
  onChange,
  empty,
  required,
  id,
  ...rest
}: {
  options: AccountOption[] | undefined;
  value: string;
  onChange: (v: string) => void;
  empty?: string;
  required?: boolean;
  id?: string;
  "aria-label"?: string;
}) {
  const t = useT();
  return (
    <Select id={id} value={value} required={required} onChange={(e) => onChange(e.target.value)} aria-label={rest["aria-label"]}>
      {empty != null ? <option value="">{empty}</option> : <option value="" disabled>{t("Choose an account")}</option>}
      {options?.map((a) => (
        <option key={a.id} value={a.id}>
          {accountLabel(a)}
        </option>
      ))}
    </Select>
  );
}
