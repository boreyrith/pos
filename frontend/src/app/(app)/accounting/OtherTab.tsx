"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { Minus, Plus } from "lucide-react";
import { useState } from "react";

import { Badge, Button, Card, ErrorNote, Field, Input, Modal, Pagination, Select, Spinner, cx } from "@/components/ui";
import { ApiError, api, queryString } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { money, shortDate, todayIn } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { OtherKind, OtherTransaction, Page } from "@/lib/types";
import { translate, useT } from "@/lib/i18n";

import { BranchFilter } from "../inventory/BranchFilter";
import { AccountSelect, useAccountOptions, useAccountingSettings, useRefreshAccounting } from "./shared";

const decimal = (v: string) => v.replace(/[^\d.]/g, "");

/** Other income and expenses: money in or out that isn't a sale or a vendor invoice. */
export function OtherTab() {
  const t = useT();
  const can = useCan();
  const store = useStoreSettings();
  const settings = useAccountingSettings();
  const refresh = useRefreshAccounting();
  const today = todayIn(store.data?.timezone ?? "Asia/Phnom_Penh");
  const [from, setFrom] = useState(today.slice(0, 8) + "01");
  const [to, setTo] = useState(today);
  const [kind, setKind] = useState<OtherKind | "">("");
  const [branchId, setBranchId] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [recording, setRecording] = useState<OtherKind | null>(null);
  const list = useQuery({
    queryKey: ["other-transactions", { from, to, kind, branchId, q, page }],
    queryFn: () => api<Page<OtherTransaction>>(`/accounting/other${queryString({ from, to, kind, branchId, q: q.trim(), page })}`),
    placeholderData: (previous) => previous,
  });
  const voidOne = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) =>
      api<OtherTransaction>(`/accounting/other/${id}/void`, { method: "POST", json: { reason } }),
    onSuccess: refresh,
  });
  const reset = <T,>(setter: (v: T) => void) => (v: T) => {
    setter(v);
    setPage(0);
  };
  const currency = store.data?.currency;
  const started = settings.data?.started;
  const canRecord = can("ACCOUNTING_RECORD") && started;

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Input type="date" aria-label={t("From")} value={from} max={to} onChange={(e) => reset(setFrom)(e.target.value)} className="w-40" />
        <Input type="date" aria-label={t("To")} value={to} min={from} onChange={(e) => reset(setTo)(e.target.value)} className="w-40" />
        <Select aria-label={t("Kind")} value={kind} onChange={(e) => reset(setKind)(e.target.value as OtherKind | "")}>
          <option value="">{t("Income and expenses")}</option>
          <option value="INCOME">{t("Income")}</option>
          <option value="EXPENSE">{t("Expenses")}</option>
        </Select>
        <BranchFilter value={branchId} onChange={reset(setBranchId)} />
        <Input placeholder={t("Number, who, reference or note")} aria-label={t("Search")} value={q} onChange={(e) => reset(setQ)(e.target.value)} className="w-60" />
        {canRecord && (
          <div className="ml-auto flex gap-2">
            <Button variant="secondary" onClick={() => setRecording("INCOME")}>
              <Plus className="size-4" aria-hidden /> {t("Record income")}
            </Button>
            <Button onClick={() => setRecording("EXPENSE")}>
              <Minus className="size-4" aria-hidden /> {t("Record expense")}
            </Button>
          </div>
        )}
      </div>
      {settings.data && !started && (
        <p className="mb-4 rounded-lg bg-warning-soft px-3 py-2 text-sm text-warning">{t("Start the books first (Accounting > Setup).")}</p>
      )}
      <ErrorNote error={voidOne.error} />
      <Card className="overflow-x-auto">
        {list.isPending ? (
          <Spinner />
        ) : list.error ? (
          <div className="p-4">
            <ErrorNote error={list.error} />
          </div>
        ) : list.data.items.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("Nothing recorded in this period.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="px-3 py-2 font-medium">{t("Date")}</th>
                <th className="px-3 py-2 font-medium">{t("Number")}</th>
                <th className="px-3 py-2 font-medium">{t("Account")}</th>
                <th className="px-3 py-2 font-medium">{t("Paid by / to")}</th>
                <th className="px-3 py-2 font-medium">{t("Cash or bank")}</th>
                <th className="px-3 py-2 text-right font-medium">{t("VAT")}</th>
                <th className="px-3 py-2 text-right font-medium">{t("Amount")}</th>
                <th className="px-3 py-2" />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {list.data.items.map((x) => {
                const income = x.kind === "INCOME";
                const voided = x.status === "VOID";
                return (
                  <tr key={x.id} className={cx(voided && "text-muted")}>
                    <td className="px-3 py-2 whitespace-nowrap">{shortDate(x.date)}</td>
                    <td className="px-3 py-2 whitespace-nowrap">
                      <span className={cx(voided && "line-through")}>{x.number}</span>
                      {voided && (
                        <span className="ml-2">
                          <Badge tone="neutral">{t("Void")}</Badge>
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      {x.account}
                      {(x.note || x.voidReason) && <span className="block text-xs text-muted">{voided ? t("Voided: {reason}", { reason: x.voidReason ?? "" }) : x.note}</span>}
                    </td>
                    <td className="px-3 py-2">
                      {x.party ?? x.vendorName ?? x.customerName ?? <span className="text-muted">—</span>}
                      {x.reference && <span className="block text-xs text-muted">{t("ref {reference}", { reference: x.reference })}</span>}
                    </td>
                    <td className="px-3 py-2">
                      {x.moneyAccount}
                      <span className="block text-xs text-muted">{x.branchName}</span>
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">{x.tax > 0 ? money(x.tax, currency) : ""}</td>
                    <td className={cx("px-3 py-2 text-right font-medium whitespace-nowrap tabular-nums", !voided && (income ? "text-success" : ""))}>
                      {income ? "+" : "−"}
                      {money(x.amount, currency)}
                    </td>
                    <td className="px-3 py-2 text-right">
                      {can("ACCOUNTING_RECORD") && !voided && (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => {
                            const reason = prompt(translate("Why void {number}?", { number: x.number }));
                            if (reason?.trim()) voidOne.mutate({ id: x.id, reason: reason.trim() });
                          }}
                        >
                          {t("Void")}
                        </Button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>
      {list.data && <Pagination page={page} totalPages={list.data.totalPages} onChange={setPage} />}
      {recording && <OtherDialog kind={recording} onClose={() => setRecording(null)} />}
    </div>
  );
}

/** Records one income or expense. The amount includes any VAT; the VAT is entered separately. */
function OtherDialog({ kind, onClose }: { kind: OtherKind; onClose: () => void }) {
  const t = useT();
  const store = useStoreSettings();
  const refresh = useRefreshAccounting();
  const income = kind === "INCOME";
  const accounts = useAccountOptions(income ? "income" : "expense");
  const moneyAccounts = useAccountOptions("money");
  const today = todayIn(store.data?.timezone ?? "Asia/Phnom_Penh");
  const [form, setForm] = useState({ date: today, accountId: "", moneyAccountId: "", amount: "", tax: "", party: "", reference: "", note: "" });
  const set = (k: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [k]: v }));
  const amount = Number(form.amount) || 0;
  const tax = Number(form.tax) || 0;
  const save = useMutation({
    mutationFn: () =>
      api<OtherTransaction>("/accounting/other", {
        method: "POST",
        json: {
          kind,
          date: form.date,
          accountId: Number(form.accountId),
          moneyAccountId: Number(form.moneyAccountId),
          amount,
          tax,
          party: form.party.trim() || null,
          reference: form.reference.trim() || null,
          note: form.note.trim() || null,
        },
      }),
    onSuccess: () => {
      refresh();
      onClose();
    },
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  const currency = store.data?.currency;

  return (
    <Modal open onClose={onClose} title={income ? t("Record income") : t("Record expense")}>
      <form
        className="grid grid-cols-2 gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="col-span-2">
          <Field label={income ? t("Income account") : t("Expense account")}>
            <AccountSelect options={accounts.data} value={form.accountId} onChange={set("accountId")} required />
          </Field>
        </div>
        <Field label={t("Date")} error={errors.date}>
          <Input type="date" required max={today} value={form.date} onChange={(e) => set("date")(e.target.value)} />
        </Field>
        <Field label={income ? t("Received into") : t("Paid from")}>
          <AccountSelect options={moneyAccounts.data} value={form.moneyAccountId} onChange={set("moneyAccountId")} required />
        </Field>
        <Field label={t("Amount (VAT included)")} error={errors.amount}>
          <Input inputMode="decimal" required value={form.amount} onChange={(e) => set("amount")(decimal(e.target.value))} />
        </Field>
        <Field label={t("VAT in it")} hint={t("Optional")} error={tax >= amount && tax > 0 ? t("The tax must be less than the amount") : errors.tax}>
          <Input inputMode="decimal" value={form.tax} onChange={(e) => set("tax")(decimal(e.target.value))} />
        </Field>
        <Field label={income ? t("Received from") : t("Paid to")} hint={t("Optional")}>
          <Input maxLength={150} value={form.party} onChange={(e) => set("party")(e.target.value)} />
        </Field>
        <Field label={t("Reference")} hint={t("Receipt, bill or transaction number")}>
          <Input maxLength={60} value={form.reference} onChange={(e) => set("reference")(e.target.value)} />
        </Field>
        <div className="col-span-2">
          <Field label={t("Note")}>
            <Input maxLength={255} value={form.note} onChange={(e) => set("note")(e.target.value)} />
          </Field>
        </div>
        {amount > 0 && (
          <p className="col-span-2 text-sm text-muted">
            {income
              ? t("{net} to income, {tax} VAT charged", { net: money(amount - tax, currency), tax: money(tax, currency) })
              : t("{net} to expenses, {tax} VAT to reclaim", { net: money(amount - tax, currency), tax: money(tax, currency) })}
          </p>
        )}
        <div className="col-span-2">
          <ErrorNote error={save.error} />
        </div>
        <div className="col-span-2 flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending} disabled={!form.accountId || !form.moneyAccountId || !(amount > 0) || tax >= amount}>
            {income ? t("Record income") : t("Record expense")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
