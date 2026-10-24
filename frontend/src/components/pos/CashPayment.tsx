"use client";

import { useState } from "react";

import { Button, ErrorNote, Input, cx } from "@/components/ui";
import { amountIn, cashIn } from "@/lib/format";
import type { Currency } from "@/lib/types";
import { useT } from "@/lib/i18n";

const BASE_NOTES = [1, 5, 10, 20, 50, 100];

export interface CashTender {
  currency: string;
  amount: number;
}

/**
 * Cash in any mix of active currencies, with change in the chosen one. Mirrors
 * CashSettlement.java: a shortfall under half a cash step of a currency used counts as paid.
 */
export function CashPayment({
  total,
  currencies,
  submitting,
  error,
  onConfirm,
}: {
  total: number;
  /** Active currencies with rates, base first. */
  currencies: Currency[];
  submitting: boolean;
  error: unknown;
  onConfirm: (tenders: CashTender[], changeCurrency: string) => void;
}) {
  const t = useT();
  const base = currencies[0];
  const [amounts, setAmounts] = useState<Record<string, string>>({ [base.code]: total.toFixed(base.decimals) });
  const [changeCode, setChangeCode] = useState(base.code);

  const lines = currencies
    .map((c) => ({ currency: c, amount: Number(amounts[c.code] || 0) }))
    .filter((l) => Number.isFinite(l.amount) && l.amount > 0);
  const badDecimals = currencies.some((c) => {
    const v = amounts[c.code];
    return v && v.includes(".") && v.split(".")[1].length > c.decimals;
  });
  const received = lines.reduce((sum, l) => sum + l.amount / (l.currency.rate ?? 1), 0);
  const remaining = total - received;
  const paid =
    remaining <= 1e-9 || lines.some((l) => remaining * (l.currency.rate ?? 1) * 2 < l.currency.cashRounding);
  const changeCurrency = currencies.find((c) => c.code === changeCode) ?? base;
  const change = remaining < 0 ? cashIn(-remaining, changeCurrency) : 0;
  const shownForDue = lines.length ? [...new Set(lines.map((l) => l.currency))] : [base];
  const valid = lines.length > 0 && paid && !badDecimals;

  const set = (code: string, value: string) => setAmounts((a) => ({ ...a, [code]: value.replace(/[^\d.]/g, "") }));

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault();
        if (valid) onConfirm(lines.map((l) => ({ currency: l.currency.code, amount: l.amount })), changeCurrency.code);
      }}
    >
      <div className="text-center">
        <div className="text-3xl font-semibold tabular-nums">{amountIn(total, base)}</div>
        <div className="text-sm text-muted tabular-nums">
          {currencies
            .slice(1)
            .map((c) => amountIn(cashIn(total, c), c))
            .join(" · ")}
        </div>
      </div>

      <div className="flex flex-col gap-2">
        <span className="text-sm font-medium">{t("Cash received")}</span>
        {currencies.map((c, i) => (
          <div key={c.code} className="flex items-center gap-2">
            <span className="w-12 text-sm font-medium">{c.code}</span>
            <div className="flex-1">
              <Input
                autoFocus={i === 0}
                inputMode="decimal"
                aria-label={t("Cash received in {currency}", { currency: c.code })}
                placeholder="0"
                value={amounts[c.code] ?? ""}
                onChange={(e) => set(c.code, e.target.value)}
                onFocus={(e) => e.target.select()}
                className="h-11 text-right text-base tabular-nums"
              />
            </div>
            <Button type="button" variant="secondary" size="sm" onClick={() => setAmounts({ [c.code]: String(cashIn(total, c)) })}>
              {t("Exact")}
            </Button>
          </div>
        ))}
        <div className="flex flex-wrap gap-2 pt-1">
          {BASE_NOTES.filter((n) => n > total)
            .slice(0, 4)
            .map((n) => (
              <Button key={n} type="button" variant="secondary" size="sm" onClick={() => setAmounts({ [base.code]: String(n) })}>
                {amountIn(n, { ...base, decimals: 0 })}
              </Button>
            ))}
        </div>
        {badDecimals && <p className="text-xs text-danger">{t("Too many decimals for that currency.")}</p>}
      </div>

      {currencies.length > 1 && (
        <div className="flex items-center gap-2 text-sm">
          <span className="text-muted">{t("Give change in")}</span>
          <div className="flex overflow-hidden rounded-lg border border-border" role="group" aria-label={t("Change currency")}>
            {currencies.map((c) => (
              <button
                key={c.code}
                type="button"
                onClick={() => setChangeCode(c.code)}
                aria-pressed={changeCode === c.code}
                className={cx("h-9 px-3", changeCode === c.code ? "bg-primary text-on-primary" : "hover:bg-surface-2")}
              >
                {c.code}
              </button>
            ))}
          </div>
        </div>
      )}

      <div className={cx("flex items-center justify-between rounded-lg px-4 py-3", paid ? "bg-surface-2" : "bg-warning-soft")}>
        <span className={cx("text-sm", paid ? "text-muted" : "text-warning")}>{paid ? t("Change") : t("Still to pay")}</span>
        <span className={cx("text-xl font-semibold tabular-nums", !paid && "text-warning")}>
          {paid
            ? amountIn(change, changeCurrency)
            : shownForDue
                .map((c) => amountIn(Math.ceil((remaining * (c.rate ?? 1)) / c.cashRounding - 1e-9) * c.cashRounding, c))
                .join(` ${t("or")} `)}
        </span>
      </div>
      <ErrorNote error={error} />
      <Button type="submit" size="lg" disabled={!valid} loading={submitting}>
        {t("Complete sale")}
      </Button>
    </form>
  );
}
