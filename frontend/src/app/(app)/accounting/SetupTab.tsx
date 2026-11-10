"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { useState } from "react";

import { Button, Card, ErrorNote, Field, Input, Spinner, cx } from "@/components/ui";
import { api } from "@/lib/api";
import { money, shortDate, todayIn } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { AccountingMode, AccountingSettings, SuggestedOpeningLine } from "@/lib/types";
import { msg, translate, translateServer, useT } from "@/lib/i18n";

import { useVendors } from "../purchasing/shared";
import { AccountSelect, useAccountOptions, useAccountingSettings, useRefreshAccounting } from "./shared";

/** Starting the books (once, with opening balances), then closing finished periods. */
export function SetupTab() {
  const settings = useAccountingSettings();
  if (settings.isPending) return <Spinner />;
  if (settings.error) return <ErrorNote error={settings.error} />;
  return settings.data.started ? <ClosePeriod settings={settings.data} /> : <StartBooks />;
}

export const MODES: { key: AccountingMode; label: string; hint: string }[] = [
  {
    key: "NORMAL",
    label: msg("Normal accounting"),
    hint: msg("Income, expenses and profit, without accounting knowledge. No journal, chart of accounts, balance sheet or trial balance to deal with."),
  },
  {
    key: "FULL",
    label: msg("Full accounting"),
    hint: msg("Everything: the journal and chart of accounts, opening balances of any account, manual journal entries and reversals, balance sheet and trial balance. For an accountant."),
  },
];

/** The two modes as cards to pick from. */
function ModePicker({ value, onChange, disabled }: { value: AccountingMode; onChange: (m: AccountingMode) => void; disabled?: boolean }) {
  const t = useT();
  return (
    <div className="grid gap-3 sm:grid-cols-2" role="radiogroup" aria-label={t("Accounting mode")}>
      {MODES.map((m) => (
        <button
          key={m.key}
          type="button"
          role="radio"
          aria-checked={value === m.key}
          disabled={disabled}
          onClick={() => onChange(m.key)}
          className={cx(
            "rounded-xl border p-4 text-left transition-colors disabled:opacity-60",
            value === m.key ? "border-primary bg-primary-soft" : "border-border hover:bg-surface-2",
          )}
        >
          <span className={cx("block font-semibold", value === m.key && "text-primary")}>{t(m.label)}</span>
          <span className="mt-1 block text-xs text-muted">{t(m.hint)}</span>
        </button>
      ))}
    </div>
  );
}

type Line = { accountId: string; debit: string; credit: string; vendorId: number | null; memo: string };
const decimal = (v: string) => v.replace(/[^\d.]/g, "");
const cents = (v: string) => Math.round((Number(v) || 0) * 100);

function StartBooks() {
  const t = useT();
  const store = useStoreSettings();
  const refresh = useRefreshAccounting();
  const vendors = useVendors();
  const accounts = useAccountOptions("all");
  const today = todayIn(store.data?.timezone ?? "Asia/Phnom_Penh");
  const [startDate, setStartDate] = useState(today);
  const [mode, setMode] = useState<AccountingMode>("NORMAL");
  const [lines, setLines] = useState<Line[] | null>(null);
  const suggested = useQuery({
    queryKey: ["accounting", "suggested-opening"],
    queryFn: () => api<SuggestedOpeningLine[]>("/accounting/setup/suggested"),
  });
  // The suggested lines, then the cash and bank accounts to count; edited from then on.
  const shown: Line[] =
    lines ??
    (suggested.data && accounts.data
      ? [
          ...suggested.data.map((s) => ({
            accountId: String(s.accountId),
            debit: s.debit > 0 ? s.debit.toFixed(2) : "",
            credit: s.credit > 0 ? s.credit.toFixed(2) : "",
            vendorId: s.vendorId,
            memo: s.memo ?? "",
          })),
          ...accounts.data
            .filter((a) => a.type === "CASH" || a.type === "BANK")
            .map((a) => ({ accountId: String(a.id), debit: "", credit: "", vendorId: null, memo: "" })),
        ]
      : []);
  const edit = (next: Line[]) => setLines(next);
  const setLine = (i: number, patch: Partial<Line>) => edit(shown.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const debits = shown.reduce((s, l) => s + cents(l.debit), 0);
  const credits = shown.reduce((s, l) => s + cents(l.credit), 0);
  const balancing = (debits - credits) / 100;
  const start = useMutation({
    mutationFn: () =>
      api<AccountingSettings>("/accounting/setup", {
        method: "POST",
        json: {
          startDate,
          mode,
          lines: shown
            .filter((l) => l.accountId && (cents(l.debit) > 0 || cents(l.credit) > 0))
            .map((l) => ({
              accountId: Number(l.accountId),
              debit: cents(l.debit) > 0 ? cents(l.debit) / 100 : null,
              credit: cents(l.credit) > 0 ? cents(l.credit) / 100 : null,
              vendorId: l.vendorId,
              memo: l.memo.trim() || null,
            })),
        },
      }),
    onSuccess: refresh,
  });
  const currency = store.data?.currency;
  const balanceSheet = accounts.data?.filter((a) => !["REVENUE", "OTHER_INCOME", "COST_OF_SALES", "EXPENSE", "OTHER_EXPENSE"].includes(a.type));
  const vendorName = (id: number | null) => vendors.data?.find((v) => v.id === id)?.name;

  return (
    <Card className="flex flex-col gap-5 p-5">
      <div>
        <h2 className="text-lg font-semibold">{t("Start the books")}</h2>
        <p className="mt-1 max-w-3xl text-sm text-muted">
          {t("From the start date, every sale, return, delivery, vendor invoice and payment, stock adjustment and cost change is posted on its own. Enter what the business had and owed on that day. Stock, what is owed to vendors and deliveries not yet invoiced are filled in from Munita; count the cash in each drawer and check the bank.")}
        </p>
      </div>
      <ModePicker value={mode} onChange={setMode} />
      <Field label={t("Start date")}>
        <Input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} className="w-44" />
      </Field>
      {suggested.isPending || accounts.isPending ? (
        <Spinner />
      ) : mode === "NORMAL" ? (
        <div className="flex flex-col gap-4">
          {suggested.data && suggested.data.length > 0 && (
            <div>
              <h3 className="mb-2 text-sm font-semibold">{t("Brought in from Munita")}</h3>
              <ul className="divide-y divide-border rounded-lg border border-border">
                {shown
                  .filter((l) => l.memo)
                  .map((l, i) => (
                    <li key={i} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
                      <span className="min-w-0">{translateServer(l.memo)}</span>
                      <span className="tabular-nums">{money((cents(l.debit) || cents(l.credit)) / 100, currency)}</span>
                    </li>
                  ))}
              </ul>
            </div>
          )}
          <div>
            <h3 className="text-sm font-semibold">{t("Money there on the start date")}</h3>
            <p className="mb-2 text-xs text-muted">{t("Count the cash in each drawer and check the bank. Leave empty if there is none.")}</p>
            <div className="grid gap-3 sm:grid-cols-2">
              {shown.map((l, i) => {
                const a = accounts.data?.find((x) => String(x.id) === l.accountId);
                if (l.memo || !a || (a.type !== "CASH" && a.type !== "BANK")) return null;
                return (
                  <Field key={i} label={`${a.code} ${translateServer(a.name)}`}>
                    <Input inputMode="decimal" className="text-right" placeholder="0.00" value={l.debit} onChange={(e) => setLine(i, { debit: decimal(e.target.value), credit: "" })} />
                  </Field>
                );
              })}
            </div>
          </div>
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          <div className="hidden grid-cols-[1fr_8rem_8rem_2rem] gap-2 text-xs font-medium text-muted md:grid">
            <span>{t("Account")}</span>
            <span className="text-right">{t("Debit (have)")}</span>
            <span className="text-right">{t("Credit (owe)")}</span>
            <span />
          </div>
          {shown.map((l, i) => (
            <div key={i} className="grid grid-cols-2 gap-2 md:grid-cols-[1fr_8rem_8rem_2rem]">
              <div className="col-span-2 md:col-span-1">
                <AccountSelect options={balanceSheet} value={l.accountId} onChange={(v) => setLine(i, { accountId: v })} aria-label={t("Account")} />
                {(l.memo || l.vendorId) && <p className="mt-0.5 text-xs text-muted">{l.memo ? translateServer(l.memo) : vendorName(l.vendorId)}</p>}
              </div>
              <Input inputMode="decimal" aria-label={t("Debit")} placeholder={t("Debit (have)")} className="text-right" value={l.debit} onChange={(e) => setLine(i, { debit: decimal(e.target.value), credit: "" })} />
              <Input inputMode="decimal" aria-label={t("Credit")} placeholder={t("Credit (owe)")} className="text-right" value={l.credit} onChange={(e) => setLine(i, { credit: decimal(e.target.value), debit: "" })} />
              <button
                type="button"
                className="hidden place-items-center rounded-md text-muted hover:bg-surface-2 md:grid"
                aria-label={t("Remove line")}
                onClick={() => edit(shown.filter((_, j) => j !== i))}
              >
                <Trash2 className="size-4" aria-hidden />
              </button>
            </div>
          ))}
          <div>
            <Button type="button" variant="ghost" size="sm" onClick={() => edit([...shown, { accountId: "", debit: "", credit: "", vendorId: null, memo: "" }])}>
              <Plus className="size-4" aria-hidden /> {t("Add line")}
            </Button>
          </div>
          <p className="text-sm text-muted tabular-nums">
            {t("Have {debits} · owe {credits}", { debits: money(debits / 100, currency), credits: money(credits / 100, currency) })}
            {balancing !== 0 && (
              <>
                {" · "}
                {t("{amount} goes to Opening balances (equity)", { amount: money(balancing, currency) })}
              </>
            )}
          </p>
        </div>
      )}
      <ErrorNote error={start.error} />
      <div>
        <Button
          loading={start.isPending}
          onClick={() => {
            if (confirm(translate("Start the books on {date}? This can be done only once.", { date: shortDate(startDate) }))) start.mutate();
          }}
        >
          {t("Start the books")}
        </Button>
      </div>
    </Card>
  );
}

function ClosePeriod({ settings }: { settings: AccountingSettings }) {
  const t = useT();
  const store = useStoreSettings();
  const refresh = useRefreshAccounting();
  const switchMode = useMutation({
    mutationFn: (mode: AccountingMode) => api<AccountingSettings>("/accounting/mode", { method: "PUT", json: { mode } }),
    onSuccess: refresh,
  });
  const today = todayIn(store.data?.timezone ?? "Asia/Phnom_Penh");
  const [lockDate, setLockDate] = useState(settings.lockDate ?? "");
  const save = useMutation({
    mutationFn: (date: string | null) => api<AccountingSettings>("/accounting/lock", { method: "PUT", json: { lockDate: date } }),
    onSuccess: refresh,
  });

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card className="flex flex-col gap-3 p-5 lg:col-span-2">
        <h2 className="text-base font-semibold">{t("Accounting mode")}</h2>
        <ModePicker
          value={settings.mode}
          disabled={switchMode.isPending}
          onChange={(m) => {
            if (m !== settings.mode && confirm(translate("Switch to {mode}? Everything posted stays as it is.", { mode: translate(MODES.find((x) => x.key === m)!.label) })))
              switchMode.mutate(m);
          }}
        />
        <ErrorNote error={switchMode.error} />
      </Card>
      <Card className="flex flex-col gap-2 p-5">
        <h2 className="text-base font-semibold">{t("The books")}</h2>
        <p className="text-sm">{t("Started {date}", { date: shortDate(settings.startDate!) })}</p>
        <p className={cx("text-sm", !settings.lockDate && "text-muted")}>
          {settings.lockDate ? t("Closed up to {date}", { date: shortDate(settings.lockDate) }) : t("No period is closed")}
        </p>
        <p className="text-xs text-muted">{t("Opening balances can be corrected with a journal entry, or by reversing the opening entry in the journal.")}</p>
      </Card>
      <Card className="flex flex-col gap-3 p-5">
        <h2 className="text-base font-semibold">{t("Close a period")}</h2>
        <p className="text-sm text-muted">
          {t("Nothing can be entered on or before the closing date, so reports for it stay as they were filed. Sales and other automatic postings that arrive for a closed day post on the first open day.")}
        </p>
        <Field label={t("Closed up to and including")}>
          <Input type="date" max={today} min={settings.startDate ?? undefined} value={lockDate} onChange={(e) => setLockDate(e.target.value)} className="w-44" />
        </Field>
        <ErrorNote error={save.error} />
        <div className="flex gap-2">
          <Button loading={save.isPending} disabled={!lockDate || lockDate === settings.lockDate} onClick={() => save.mutate(lockDate)}>
            {t("Close the books")}
          </Button>
          {settings.lockDate && (
            <Button
              variant="secondary"
              onClick={() => {
                if (confirm(translate("Reopen the books? Entries could then be made in periods already closed."))) {
                  setLockDate("");
                  save.mutate(null);
                }
              }}
            >
              {t("Reopen")}
            </Button>
          )}
        </div>
      </Card>
    </div>
  );
}
