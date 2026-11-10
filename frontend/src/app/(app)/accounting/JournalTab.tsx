"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { Fragment, useState } from "react";

import { Badge, Button, Card, ErrorNote, Field, Input, Modal, Pagination, Select, Spinner, cx } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { useBranch } from "@/lib/branch";
import { money, shortDate, todayIn } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { JournalEntry, JournalSource, Page } from "@/lib/types";
import { translate, translateServer, useT } from "@/lib/i18n";

import { BranchFilter } from "../inventory/BranchFilter";
import { AccountSelect, SOURCES, accountName, sourceHref, useAccountOptions, useAccountingSettings, useRefreshAccounting } from "./shared";

/** Every entry in the books, newest first, with its lines; post and reverse journal entries. */
export function JournalTab({ accountId }: { accountId?: number }) {
  const t = useT();
  const can = useCan();
  const store = useStoreSettings();
  const settings = useAccountingSettings();
  const refresh = useRefreshAccounting();
  const today = todayIn(store.data?.timezone ?? "Asia/Phnom_Penh");
  const [from, setFrom] = useState(today.slice(0, 8) + "01");
  const [to, setTo] = useState(today);
  const [source, setSource] = useState<JournalSource | "">("");
  const [branchId, setBranchId] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<number | null>(null);
  const [writing, setWriting] = useState(false);
  const entries = useQuery({
    queryKey: ["journal", { from, to, source, branchId, q, page, accountId }],
    queryFn: () => api<Page<JournalEntry>>(`/accounting/journal${queryString({ from, to, source, branchId, q: q.trim(), page, accountId })}`),
    placeholderData: (previous) => previous,
  });
  const reverse = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) =>
      api<JournalEntry>(`/accounting/journal/${id}/reverse`, { method: "POST", json: { reason } }),
    onSuccess: refresh,
  });
  const reset = <T,>(setter: (v: T) => void) => (v: T) => {
    setter(v);
    setPage(0);
  };
  const currency = store.data?.currency;
  const full = settings.data?.mode === "FULL";

  return (
    <div>
      {settings.data?.started && !full && (
        <p className="mb-4 text-xs text-muted">{t("Normal accounting: entries are posted by sales, purchases, stock and income and expenses. Switch to full accounting in Setup to write or reverse entries yourself.")}</p>
      )}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Input type="date" aria-label={t("From")} value={from} max={to} onChange={(e) => reset(setFrom)(e.target.value)} className="w-40" />
        <Input type="date" aria-label={t("To")} value={to} min={from} onChange={(e) => reset(setTo)(e.target.value)} className="w-40" />
        <Select aria-label={t("Source")} value={source} onChange={(e) => reset(setSource)(e.target.value as JournalSource | "")}>
          <option value="">{t("Every kind of entry")}</option>
          {Object.entries(SOURCES).map(([k, v]) => (
            <option key={k} value={k}>
              {t(v)}
            </option>
          ))}
        </Select>
        <BranchFilter value={branchId} onChange={reset(setBranchId)} />
        <Input placeholder={t("Entry, document number or memo")} aria-label={t("Search entries")} value={q} onChange={(e) => reset(setQ)(e.target.value)} className="w-60" />
        {can("ACCOUNTING_JOURNAL") && settings.data?.started && full && (
          <Button className="ml-auto" onClick={() => setWriting(true)}>
            <Plus className="size-4" aria-hidden /> {t("New journal entry")}
          </Button>
        )}
      </div>
      <ErrorNote error={reverse.error} />
      <Card className="overflow-x-auto">
        {entries.isPending ? (
          <Spinner />
        ) : entries.error ? (
          <div className="p-4">
            <ErrorNote error={entries.error} />
          </div>
        ) : entries.data.items.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("No entries in this period.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="w-8" />
                <th className="px-3 py-2 font-medium">{t("Date")}</th>
                <th className="px-3 py-2 font-medium">{t("Entry")}</th>
                <th className="px-3 py-2 font-medium">{t("Kind")}</th>
                <th className="px-3 py-2 font-medium">{t("Memo")}</th>
                <th className="px-3 py-2 text-right font-medium">{t("Amount")}</th>
                <th className="px-3 py-2" />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {entries.data.items.map((e) => {
                const href = sourceHref(e.source, e.sourceId);
                return (
                  <Fragment key={e.id}>
                    <tr className="cursor-pointer hover:bg-surface-2" onClick={() => setOpen(open === e.id ? null : e.id)}>
                      <td className="pl-3 text-muted">
                        {open === e.id ? <ChevronDown className="size-4" aria-hidden /> : <ChevronRight className="size-4" aria-hidden />}
                      </td>
                      <td className="px-3 py-2 whitespace-nowrap">{shortDate(e.date)}</td>
                      <td className="px-3 py-2 whitespace-nowrap">
                        <button className="font-medium hover:underline" aria-expanded={open === e.id}>
                          {e.entryNumber}
                        </button>
                        {e.reversedByNumber && (
                          <span className="ml-2">
                            <Badge tone="neutral">{t("Reversed")}</Badge>
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2 whitespace-nowrap">
                        {t(SOURCES[e.source])}
                        {e.reference && (
                          <span className="block text-xs text-muted">
                            {href ? (
                              <Link href={href} className="text-primary hover:underline" onClick={(ev) => ev.stopPropagation()}>
                                {e.reference}
                              </Link>
                            ) : (
                              e.reference
                            )}
                          </span>
                        )}
                      </td>
                      <td className="max-w-md px-3 py-2">
                        {translateServer(e.memo)}
                        {e.branchName && <span className="block text-xs text-muted">{e.branchName}</span>}
                      </td>
                      <td className="px-3 py-2 text-right font-medium whitespace-nowrap tabular-nums">{money(e.total, currency)}</td>
                      <td className="px-3 py-2 text-right" onClick={(ev) => ev.stopPropagation()}>
                        {can("ACCOUNTING_JOURNAL") && full && e.reversible && (
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => {
                              const reason = prompt(translate("Why reverse {entryNumber}?", { entryNumber: e.entryNumber }));
                              if (reason?.trim()) reverse.mutate({ id: e.id, reason: reason.trim() });
                            }}
                          >
                            {t("Reverse")}
                          </Button>
                        )}
                      </td>
                    </tr>
                    {open === e.id && (
                      <tr className="bg-surface-2">
                        <td />
                        <td colSpan={6} className="px-3 py-3">
                          <EntryLines entry={e} currency={currency} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>
      {entries.data && <Pagination page={page} totalPages={entries.data.totalPages} onChange={setPage} />}
      {writing && <JournalDialog onClose={() => setWriting(false)} />}
    </div>
  );
}

/** An entry's debits and credits, and where it came from. */
export function EntryLines({ entry: e, currency }: { entry: JournalEntry; currency: string | undefined }) {
  const t = useT();
  return (
    <div className="flex flex-col gap-2">
      <table className="w-full max-w-3xl text-xs">
        <thead className="text-left text-muted">
          <tr>
            <th className="py-1 pr-3 font-medium">{t("Account")}</th>
            <th className="py-1 pr-3 font-medium">{t("Details")}</th>
            <th className="py-1 pr-3 text-right font-medium">{t("Debit")}</th>
            <th className="py-1 text-right font-medium">{t("Credit")}</th>
          </tr>
        </thead>
        <tbody>
          {e.lines.map((l, i) => (
            <tr key={i}>
              <td className={cx("py-1 pr-3", l.credit > 0 && "pl-6")}>
                <span className="text-muted tabular-nums">{l.accountCode}</span> {accountName(l.accountName)}
              </td>
              <td className="py-1 pr-3 text-muted">{[l.vendorName, l.customerName, l.memo && translateServer(l.memo)].filter(Boolean).join(" · ")}</td>
              <td className="py-1 pr-3 text-right tabular-nums">{l.debit > 0 ? money(l.debit, currency) : ""}</td>
              <td className="py-1 text-right tabular-nums">{l.credit > 0 ? money(l.credit, currency) : ""}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-xs text-muted">
        {e.createdBy ? t("Posted by {name}", { name: e.createdBy }) : t("Posted automatically")}
        {e.reversalOfNumber && <> · {t("Reverses {entryNumber}", { entryNumber: e.reversalOfNumber })}</>}
        {e.reversedByNumber && <> · {t("Reversed by {entryNumber}", { entryNumber: e.reversedByNumber })}</>}
      </p>
    </div>
  );
}

type DraftLine = { accountId: string; debit: string; credit: string; memo: string };
const emptyLine = (): DraftLine => ({ accountId: "", debit: "", credit: "", memo: "" });
const decimal = (v: string) => v.replace(/[^\d.]/g, "");

/** A journal entry the accountant writes: at least two lines; debits must equal credits. */
function JournalDialog({ onClose }: { onClose: () => void }) {
  const t = useT();
  const store = useStoreSettings();
  const settings = useAccountingSettings();
  const refresh = useRefreshAccounting();
  const accounts = useAccountOptions("all");
  const { branches } = useBranch();
  const today = todayIn(store.data?.timezone ?? "Asia/Phnom_Penh");
  const [date, setDate] = useState(today);
  const [memo, setMemo] = useState("");
  const [reference, setReference] = useState("");
  const [branchId, setBranchId] = useState("");
  const [lines, setLines] = useState<DraftLine[]>([emptyLine(), emptyLine()]);
  const setLine = (i: number, patch: Partial<DraftLine>) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const cents = (v: string) => Math.round((Number(v) || 0) * 100);
  const debits = lines.reduce((s, l) => s + cents(l.debit), 0);
  const credits = lines.reduce((s, l) => s + cents(l.credit), 0);
  const used = lines.filter((l) => l.accountId && (cents(l.debit) > 0 || cents(l.credit) > 0));
  const balanced = debits === credits && debits > 0 && used.length >= 2;
  const save = useMutation({
    mutationFn: () =>
      api<JournalEntry>("/accounting/journal", {
        method: "POST",
        json: {
          date,
          memo: memo.trim(),
          reference: reference.trim() || null,
          branchId: branchId ? Number(branchId) : null,
          lines: used.map((l) => ({
            accountId: Number(l.accountId),
            debit: cents(l.debit) > 0 ? cents(l.debit) / 100 : null,
            credit: cents(l.credit) > 0 ? cents(l.credit) / 100 : null,
            memo: l.memo.trim() || null,
          })),
        },
      }),
    onSuccess: () => {
      refresh();
      onClose();
    },
  });
  const currency = store.data?.currency;

  return (
    <Modal open onClose={onClose} title={t("New journal entry")} wide>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label={t("Date")} hint={settings.data?.firstOpenDate ? t("From {date}", { date: shortDate(settings.data.firstOpenDate) }) : undefined}>
            <Input type="date" required min={settings.data?.firstOpenDate ?? undefined} value={date} onChange={(e) => setDate(e.target.value)} />
          </Field>
          <Field label={t("Reference")} hint={t("Optional")}>
            <Input maxLength={60} value={reference} onChange={(e) => setReference(e.target.value)} />
          </Field>
          <Field label={t("Branch")} hint={t("Optional")}>
            <Select value={branchId} onChange={(e) => setBranchId(e.target.value)}>
              <option value="">{t("No branch")}</option>
              {branches.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.name}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <Field label={t("Memo")}>
          <Input required maxLength={255} value={memo} onChange={(e) => setMemo(e.target.value)} placeholder={t("What this entry is for")} />
        </Field>
        <div className="flex flex-col gap-2">
          <div className="hidden grid-cols-[1fr_7rem_7rem_2rem] gap-2 text-xs font-medium text-muted sm:grid">
            <span>{t("Account")}</span>
            <span className="text-right">{t("Debit")}</span>
            <span className="text-right">{t("Credit")}</span>
            <span />
          </div>
          {lines.map((l, i) => (
            <div key={i} className="grid grid-cols-2 gap-2 sm:grid-cols-[1fr_7rem_7rem_2rem]">
              <div className="col-span-2 sm:col-span-1">
                <AccountSelect options={accounts.data} value={l.accountId} onChange={(v) => setLine(i, { accountId: v })} aria-label={t("Account")} />
              </div>
              <Input
                inputMode="decimal"
                aria-label={t("Debit")}
                placeholder={t("Debit")}
                className="text-right"
                value={l.debit}
                onChange={(e) => setLine(i, { debit: decimal(e.target.value), credit: "" })}
              />
              <Input
                inputMode="decimal"
                aria-label={t("Credit")}
                placeholder={t("Credit")}
                className="text-right"
                value={l.credit}
                onChange={(e) => setLine(i, { credit: decimal(e.target.value), debit: "" })}
              />
              <button
                type="button"
                className="hidden place-items-center rounded-md text-muted hover:bg-surface-2 disabled:opacity-30 sm:grid"
                aria-label={t("Remove line")}
                disabled={lines.length <= 2}
                onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}
              >
                <Trash2 className="size-4" aria-hidden />
              </button>
            </div>
          ))}
          <div className="flex flex-wrap items-center justify-between gap-2">
            <Button type="button" variant="ghost" size="sm" onClick={() => setLines((ls) => [...ls, emptyLine()])}>
              <Plus className="size-4" aria-hidden /> {t("Add line")}
            </Button>
            <p className={cx("text-sm tabular-nums", debits !== credits && "text-danger")}>
              {t("Debits {debits} · credits {credits}", { debits: money(debits / 100, currency), credits: money(credits / 100, currency) })}
              {debits !== credits && <> · {t("off by {amount}", { amount: money(Math.abs(debits - credits) / 100, currency) })}</>}
            </p>
          </div>
        </div>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending} disabled={!balanced || !memo.trim()}>
            {t("Post entry")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
