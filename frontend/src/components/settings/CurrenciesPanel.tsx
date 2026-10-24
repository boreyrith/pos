"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";

import { Badge, Button, Card, ErrorNote, Field, Input, Modal, Spinner } from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { amountIn, dateTime } from "@/lib/format";
import { useCurrencies, useStoreSettings } from "@/lib/queries";
import type { Currency, RateChange } from "@/lib/types";
import { translate, translateServer, useT } from "@/lib/i18n";

/** Rates keep up to 6 decimals but show no trailing zeros: 4,105 and 35.1. */
function formatRate(rate: number) {
  return new Intl.NumberFormat("en-US", { maximumFractionDigits: 6 }).format(rate);
}

/** Managers update rates; admins also add currencies and turn them on or off. */
export function CurrenciesPanel() {
  const t = useT();
  const user = useUser();
  const admin = hasPermission(user, "SETTINGS_CURRENCY");
  const setsRates = hasPermission(user, "CURRENCY_RATES");
  const currencies = useCurrencies();
  const store = useStoreSettings();
  const [rateFor, setRateFor] = useState<Currency | null>(null);
  const [editing, setEditing] = useState<Currency | "new" | null>(null);
  const base = currencies.data?.find((c) => c.base);

  return (
    <div>
      {admin && (
        <div className="mb-3 flex justify-end">
          <Button onClick={() => setEditing("new")}>
            <Plus className="size-4" aria-hidden />{" "}{t("Add currency")}
          </Button>
        </div>
      )}
      <p className="mb-4 text-sm text-muted">
        {t("Prices, totals and reports are in {currency}. Customers can pay in any active currency at its current rate. Each sale keeps the rates it was made at.", {
          currency: base ? `${translateServer(base.name)} (${base.code})` : t("the base currency"),
        })}
      </p>
      <Card className="overflow-x-auto">
        {currencies.isPending ? (
          <Spinner />
        ) : currencies.error ? (
          <div className="p-4">
            <ErrorNote error={currencies.error} />
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="px-4 py-3 font-medium">{t("Currency")}</th>
                <th className="px-4 py-3 font-medium">{t("Rate")}</th>
                <th className="px-4 py-3 font-medium">{t("Last changed")}</th>
                <th className="px-4 py-3 font-medium">{t("Cash step")}</th>
                <th className="px-4 py-3" aria-label={t("Actions")} />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {[...currencies.data].sort((a, b) => Number(b.base) - Number(a.base)).map((c) => (
                <tr key={c.code} className={c.active ? "" : "text-muted"}>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2 font-medium">
                      <span className="font-mono">{c.code}</span> {c.name}
                      {c.base && <Badge tone="primary">{t("Base")}</Badge>}
                      {!c.active && <Badge>{t("Off")}</Badge>}
                    </div>
                  </td>
                  <td className="px-4 py-3 tabular-nums">
                    {c.base ? "—" : c.rate !== null && base ? `1 ${base.code} = ${formatRate(c.rate)} ${c.symbol}` : t("Not set")}
                  </td>
                  <td className="px-4 py-3 text-muted">
                    {c.rateSetAt ? (
                      <>
                        {dateTime(c.rateSetAt, store.data?.timezone)}
                        <div className="text-xs">{c.rateSetBy ?? t("Initial setup")}</div>
                      </>
                    ) : (
                      "—"
                    )}
                  </td>
                  <td className="px-4 py-3 tabular-nums">{amountIn(c.cashRounding, c)}</td>
                  <td className="px-4 py-3 text-right whitespace-nowrap">
                    {!c.base && setsRates && (
                      <Button size="sm" variant="secondary" onClick={() => setRateFor(c)}>
                        {t("Update rate")}
                      </Button>
                    )}
                    {admin && (
                      <Button size="sm" variant="ghost" onClick={() => setEditing(c)}>
                        {t("Edit")}
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      {rateFor && base && <RateDialog currency={rateFor} base={base} onClose={() => setRateFor(null)} />}
      {editing && <CurrencyDialog currency={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function RateDialog({ currency, base, onClose }: { currency: Currency; base: Currency; onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const store = useStoreSettings();
  const [rate, setRate] = useState(currency.rate ? String(currency.rate) : "");
  const [note, setNote] = useState("");
  const history = useQuery({
    queryKey: ["currencies", currency.code, "rates"],
    queryFn: () => api<RateChange[]>(`/currencies/${currency.code}/rates`),
  });
  const value = Number(rate);
  const change = currency.rate && value > 0 ? (value - currency.rate) / currency.rate : 0;
  const bigJump = Math.abs(change) > 0.1;

  const save = useMutation({
    mutationFn: () => api<Currency>(`/currencies/${currency.code}/rates`, { method: "POST", json: { rate: value, note: note || null } }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["currencies"] });
      onClose();
    },
  });

  return (
    <Modal open onClose={onClose} title={t("{name} rate", { name: currency.name })}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (!(value > 0)) return;
          if (bigJump && !confirm(translate("That is a {round}% change from the current rate. Save it?", { round: Math.round(change * 100) }))) return;
          save.mutate();
        }}
      >
        <Field label={t("{code} per 1 {code2}", { code: currency.code, code2: base.code })} hint={t("Sales from now on use this rate. Past sales keep theirs.")}>
          <Input autoFocus inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value.replace(/[^\d.]/g, ""))} className="text-right tabular-nums" />
        </Field>
        {value > 0 && (
          <p className="text-sm text-muted tabular-nums">
            {amountIn(10, base)} = {amountIn(Math.round(10 * value * 100) / 100, { ...currency, decimals: Math.min(currency.decimals, 2) })}
            {currency.rate ? ` · ${t("{pct}% vs now", { pct: `${change >= 0 ? "+" : ""}${(change * 100).toFixed(2)}` })}` : ""}
          </p>
        )}
        <Field label={t("Note")}>
          <Input placeholder={t("Optional, e.g. NBC rate of the day")} value={note} onChange={(e) => setNote(e.target.value)} maxLength={255} />
        </Field>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" disabled={!(value > 0)} loading={save.isPending}>
            {t("Save rate")}
          </Button>
        </div>
      </form>

      <h3 className="mt-6 mb-2 text-sm font-semibold">{t("History")}</h3>
      {history.isPending ? (
        <Spinner />
      ) : (
        <ul className="divide-y divide-border text-sm">
          {history.data?.map((r) => (
            <li key={r.id} className="flex items-center justify-between gap-3 py-1.5">
              <span className="font-medium tabular-nums">{formatRate(r.rate)}</span>
              <span className="flex-1 truncate text-xs text-muted">{r.note}</span>
              <span className="text-right text-xs text-muted">
                {dateTime(r.setAt, store.data?.timezone)}
                <br />
                {r.setBy ?? t("Initial setup")}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Modal>
  );
}

function CurrencyDialog({ currency, onClose }: { currency: Currency | null; onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const [form, setForm] = useState({
    code: currency?.code ?? "",
    name: currency?.name ?? "",
    symbol: currency?.symbol ?? "",
    decimals: String(currency?.decimals ?? 2),
    cashRounding: String(currency?.cashRounding ?? "0.01"),
    active: currency?.active ?? true,
    sortOrder: String(currency?.sortOrder ?? 10),
    rate: "",
  });
  const save = useMutation({
    mutationFn: () => {
      const body = {
        code: form.code,
        name: form.name,
        symbol: form.symbol,
        decimals: Number(form.decimals),
        cashRounding: Number(form.cashRounding),
        active: form.active,
        sortOrder: Number(form.sortOrder),
        ...(currency ? {} : { rate: Number(form.rate) }),
      };
      return currency
        ? api<Currency>(`/currencies/${currency.code}`, { method: "PUT", json: body })
        : api<Currency>("/currencies", { method: "POST", json: body });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["currencies"] });
      onClose();
    },
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  const set = (k: keyof typeof form) => (v: string | boolean) => setForm((f) => ({ ...f, [k]: v }));

  return (
    <Modal open onClose={onClose} title={currency ? t("Edit {code}", { code: currency.code }) : t("Add currency")}>
      <form
        className="grid grid-cols-2 gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Field label={t("Code")} error={errors.code} hint={t("3 letters, e.g. EUR")}>
          <Input required disabled={!!currency} maxLength={3} value={form.code} onChange={(e) => set("code")(e.target.value.toUpperCase())} />
        </Field>
        <Field label={t("Symbol")} error={errors.symbol}>
          <Input required maxLength={8} value={form.symbol} onChange={(e) => set("symbol")(e.target.value)} />
        </Field>
        <div className="col-span-2">
          <Field label={t("Name")} error={errors.name}>
            <Input required value={form.name} onChange={(e) => set("name")(e.target.value)} />
          </Field>
        </div>
        <Field label={t("Decimals")} error={errors.decimals} hint={t("0 for riel, 2 for dollars")}>
          <Input inputMode="numeric" value={form.decimals} onChange={(e) => set("decimals")(e.target.value.replace(/\D/g, ""))} />
        </Field>
        <Field label={t("Cash step")} error={errors.cashRounding} hint={t("Smallest cash amount, e.g. 100 for riel")}>
          <Input inputMode="decimal" value={form.cashRounding} onChange={(e) => set("cashRounding")(e.target.value.replace(/[^\d.]/g, ""))} />
        </Field>
        {!currency && (
          <div className="col-span-2">
            <Field label={t("Rate (per 1 of the base currency)")} error={errors.rate}>
              <Input required inputMode="decimal" value={form.rate} onChange={(e) => set("rate")(e.target.value.replace(/[^\d.]/g, ""))} />
            </Field>
          </div>
        )}
        <Field label={t("Order")} hint={t("Lower comes first")}>
          <Input inputMode="numeric" value={form.sortOrder} onChange={(e) => set("sortOrder")(e.target.value.replace(/\D/g, ""))} />
        </Field>
        {!currency?.base && (
          <label className="flex items-center gap-2 self-end pb-2 text-sm">
            <input type="checkbox" checked={form.active} onChange={(e) => set("active")(e.target.checked)} />
            {t("Accept at the till")}
          </label>
        )}
        <div className="col-span-2">
          <ErrorNote error={save.error} />
        </div>
        <div className="col-span-2 flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending}>
            {currency ? t("Save") : t("Add currency")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
