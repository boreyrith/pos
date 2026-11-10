"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { Lock, Pencil, Plus } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { Badge, Button, Card, ErrorNote, Field, Input, Modal, Select, Spinner, cx } from "@/components/ui";
import { ApiError, api, queryString } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { money, shortDate, todayIn } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { Account, AccountType, Ledger } from "@/lib/types";
import { translateServer, useT } from "@/lib/i18n";

import { BranchFilter } from "../inventory/BranchFilter";
import { ACCOUNT_TYPES, SECTIONS, SOURCES, accountName, sourceHref, useRefreshAccounting } from "./shared";

/** The chart of accounts by section, with each account's balance; open one for its ledger. */
export function AccountsTab() {
  const t = useT();
  const can = useCan();
  const store = useStoreSettings();
  const today = todayIn(store.data?.timezone ?? "Asia/Phnom_Penh");
  const [asOf, setAsOf] = useState(today);
  const [branchId, setBranchId] = useState("");
  const [showInactive, setShowInactive] = useState(false);
  const [editing, setEditing] = useState<Account | "new" | null>(null);
  const [ledger, setLedger] = useState<Account | null>(null);
  const accounts = useQuery({
    queryKey: ["accounts", { asOf, branchId }],
    queryFn: () => api<Account[]>(`/accounting/accounts${queryString({ asOf, branchId })}`),
    placeholderData: (previous) => previous,
  });
  const currency = store.data?.currency;

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Field label={t("Balances at the end of")}>
          <Input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value || today)} className="w-44" />
        </Field>
        <div className="self-end">
          <BranchFilter value={branchId} onChange={setBranchId} />
        </div>
        <label className="flex items-center gap-2 self-end pb-2 text-sm">
          <input type="checkbox" checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} />
          {t("Show accounts turned off")}
        </label>
        {can("ACCOUNTING_MANAGE") && (
          <Button className="ml-auto self-end" onClick={() => setEditing("new")}>
            <Plus className="size-4" aria-hidden /> {t("New account")}
          </Button>
        )}
      </div>
      {accounts.isPending ? (
        <Spinner />
      ) : accounts.error ? (
        <ErrorNote error={accounts.error} />
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {SECTIONS.map((s) => {
            const rows = accounts.data.filter((a) => a.section === s.key && (showInactive || a.active || a.balance !== 0));
            const pl = s.key === "INCOME" || s.key === "EXPENSE";
            return (
              <Card key={s.key} className="overflow-hidden">
                <div className="flex items-baseline justify-between border-b border-border px-4 py-3">
                  <h2 className="text-sm font-semibold">{t(s.label)}</h2>
                  <span className="text-sm font-semibold tabular-nums">{money(rows.reduce((sum, a) => sum + a.balance, 0), currency)}</span>
                </div>
                {pl && <p className="px-4 pt-2 text-xs text-muted">{t("Since the books were started")}</p>}
                <ul className="divide-y divide-border">
                  {rows.map((a) => (
                    <li key={a.id} className={cx("flex items-center gap-3 px-4 py-2 text-sm", !a.active && "text-muted")}>
                      <button className="flex min-w-0 flex-1 items-baseline gap-2 text-left hover:underline" onClick={() => setLedger(a)}>
                        <span className="w-12 shrink-0 text-muted tabular-nums">{a.code}</span>
                        <span className="min-w-0 truncate">{accountName(a.name)}</span>
                        {a.system && <Lock className="size-3 shrink-0 self-center text-muted" aria-label={t("Used by automatic postings")} />}
                        {!a.active && <Badge tone="neutral">{t("Off")}</Badge>}
                      </button>
                      <span className="hidden text-xs text-muted sm:inline">{t(ACCOUNT_TYPES[a.type])}</span>
                      <span className={cx("w-28 text-right tabular-nums", a.balance < 0 && "text-danger")}>{money(a.balance, currency)}</span>
                      {can("ACCOUNTING_MANAGE") && (
                        <button className="rounded-md p-1 text-muted hover:bg-surface-2" aria-label={t("Edit {name}", { name: a.name })} onClick={() => setEditing(a)}>
                          <Pencil className="size-4" aria-hidden />
                        </button>
                      )}
                    </li>
                  ))}
                </ul>
              </Card>
            );
          })}
        </div>
      )}
      {editing && <AccountDialog account={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
      {ledger && <LedgerDialog account={ledger} branchId={branchId} onClose={() => setLedger(null)} />}
    </div>
  );
}

function AccountDialog({ account, onClose }: { account: Account | null; onClose: () => void }) {
  const t = useT();
  const refresh = useRefreshAccounting();
  const [code, setCode] = useState(account?.code ?? "");
  const [name, setName] = useState(account?.name ?? "");
  const [type, setType] = useState<AccountType>(account?.type ?? "EXPENSE");
  const [description, setDescription] = useState(account?.description ?? "");
  const [active, setActive] = useState(account?.active ?? true);
  const save = useMutation({
    mutationFn: () =>
      api<Account>(account ? `/accounting/accounts/${account.id}` : "/accounting/accounts", {
        method: account ? "PUT" : "POST",
        json: { code: code.trim(), name: name.trim(), type, description: description.trim() || null, active },
      }),
    onSuccess: () => {
      refresh();
      onClose();
    },
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};

  return (
    <Modal open onClose={onClose} title={account ? t("Edit account") : t("New account")}>
      <form
        className="grid grid-cols-[8rem_1fr] gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Field label={t("Code")} error={errors.code}>
          <Input required maxLength={10} value={code} onChange={(e) => setCode(e.target.value)} />
        </Field>
        <Field label={t("Name")} error={errors.name}>
          <Input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <div className="col-span-2">
          <Field label={t("Type")} hint={account?.system ? t("Used by automatic postings: its type can't change") : undefined}>
            <Select value={type} disabled={account?.system} onChange={(e) => setType(e.target.value as AccountType)}>
              {Object.entries(ACCOUNT_TYPES).map(([k, v]) => (
                <option key={k} value={k}>
                  {t(v)}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <div className="col-span-2">
          <Field label={t("Description")} hint={t("Optional")}>
            <Input maxLength={255} value={description} onChange={(e) => setDescription(e.target.value)} />
          </Field>
        </div>
        <label className="col-span-2 flex items-center gap-2 text-sm">
          <input type="checkbox" checked={active} disabled={account?.system} onChange={(e) => setActive(e.target.checked)} />
          {t("In use (turned-off accounts keep their history but can't be picked)")}
        </label>
        <div className="col-span-2">
          <ErrorNote error={save.error} />
        </div>
        <div className="col-span-2 flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending} disabled={!code.trim() || !name.trim()}>
            {t("Save")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/** One account's movements over a period, with the running balance. */
function LedgerDialog({ account, branchId, onClose }: { account: Account; branchId: string; onClose: () => void }) {
  const t = useT();
  const store = useStoreSettings();
  const today = todayIn(store.data?.timezone ?? "Asia/Phnom_Penh");
  const [from, setFrom] = useState(today.slice(0, 8) + "01");
  const [to, setTo] = useState(today);
  const ledger = useQuery({
    queryKey: ["ledger", account.id, { from, to, branchId }],
    queryFn: () => api<Ledger>(`/accounting/accounts/${account.id}/ledger${queryString({ from, to, branchId })}`),
    placeholderData: (previous) => previous,
  });
  const currency = store.data?.currency;
  const l = ledger.data;

  return (
    <Modal open onClose={onClose} title={`${account.code} ${accountName(account.name)}`} wide>
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <Input type="date" aria-label={t("From")} value={from} max={to} onChange={(e) => setFrom(e.target.value)} className="w-40" />
          <Input type="date" aria-label={t("To")} value={to} min={from} onChange={(e) => setTo(e.target.value)} className="w-40" />
          <span className="text-xs text-muted">{t(ACCOUNT_TYPES[account.type])}</span>
        </div>
        {ledger.isPending ? (
          <Spinner />
        ) : ledger.error ? (
          <ErrorNote error={ledger.error} />
        ) : (
          l && (
            <>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead className="border-b border-border text-left text-muted">
                    <tr>
                      <th className="py-1.5 pr-2 font-medium">{t("Date")}</th>
                      <th className="py-1.5 pr-2 font-medium">{t("Entry")}</th>
                      <th className="py-1.5 pr-2 font-medium">{t("Details")}</th>
                      <th className="py-1.5 pr-2 text-right font-medium">{t("Debit")}</th>
                      <th className="py-1.5 pr-2 text-right font-medium">{t("Credit")}</th>
                      <th className="py-1.5 text-right font-medium">{t("Balance")}</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    <tr className="text-muted">
                      <td colSpan={5} className="py-1.5">
                        {t("Opening balance")}
                      </td>
                      <td className="py-1.5 text-right tabular-nums">{money(l.opening, currency)}</td>
                    </tr>
                    {l.lines.map((x, i) => {
                      const href = sourceHref(x.source, x.sourceId);
                      return (
                        <tr key={i}>
                          <td className="py-1.5 pr-2 whitespace-nowrap">{shortDate(x.date)}</td>
                          <td className="py-1.5 pr-2 whitespace-nowrap">
                            {x.entryNumber}
                            <span className="block text-muted">
                              {href && x.reference ? (
                                <Link href={href} className="text-primary hover:underline">
                                  {x.reference}
                                </Link>
                              ) : (
                                x.reference ?? t(SOURCES[x.source])
                              )}
                            </span>
                          </td>
                          <td className="py-1.5 pr-2">
                            {translateServer(x.memo)}
                            {x.party && <span className="block text-muted">{x.party}</span>}
                          </td>
                          <td className="py-1.5 pr-2 text-right tabular-nums">{x.debit > 0 ? money(x.debit, currency) : ""}</td>
                          <td className="py-1.5 pr-2 text-right tabular-nums">{x.credit > 0 ? money(x.credit, currency) : ""}</td>
                          <td className={cx("py-1.5 text-right tabular-nums", x.balance < 0 && "text-danger")}>{money(x.balance, currency)}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                  <tfoot className="border-t-2 border-border font-semibold">
                    <tr>
                      <td colSpan={3} className="py-1.5">
                        {t("Closing balance")}
                      </td>
                      <td className="py-1.5 pr-2 text-right tabular-nums">{money(l.debits, currency)}</td>
                      <td className="py-1.5 pr-2 text-right tabular-nums">{money(l.credits, currency)}</td>
                      <td className="py-1.5 text-right tabular-nums">{money(l.closing, currency)}</td>
                    </tr>
                  </tfoot>
                </table>
              </div>
              {l.lines.length === 0 && <p className="text-center text-sm text-muted">{t("No entries in this period.")}</p>}
              {l.truncated && <p className="text-xs text-muted">{t("Only the first {count} lines are listed; the totals cover the whole period.", { count: l.lines.length })}</p>}
            </>
          )
        )}
      </div>
    </Modal>
  );
}
