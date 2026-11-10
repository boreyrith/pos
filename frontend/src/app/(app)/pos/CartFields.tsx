"use client";

import { Coins, TicketPercent, X } from "lucide-react";
import { useEffect, useState } from "react";

import { TierBadge } from "@/components/TierBadge";
import { Badge, Button, Field, Input, Modal, cx } from "@/components/ui";
import { duration, money } from "@/lib/format";
import type { DiscountType, Offering, LoyaltyRules, PromotionPreview, Tier } from "@/lib/types";
import { msg, translateServer, useT } from "@/lib/i18n";
import type { CartLine } from "./cart";

/** Waits until `value` has stopped changing for `ms` before passing it on (e.g. while quantities are being tapped). */
export function useDebounced<T>(value: T, ms: number) {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return settled;
}

/** A coupon the customer gives: typed or scanned, checked by the server, shown with what it did or why it didn't. */
export function CouponField({
  code,
  result,
  checking,
  onChange,
}: {
  code: string | null;
  result: PromotionPreview["coupon"];
  checking: boolean;
  onChange: (code: string | null) => void;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  if (code) {
    const ok = result?.applied && result.code.toUpperCase() === code.toUpperCase();
    return (
      <div className="mb-3">
        <div
          className={cx(
            "flex items-center gap-2 rounded-lg px-3 py-1.5 text-sm",
            checking ? "bg-surface-2 text-muted" : ok ? "bg-success-soft text-success" : "bg-warning-soft text-warning",
          )}
        >
          <TicketPercent className="size-4 shrink-0" aria-hidden />
          <span className="font-mono font-medium">{code}</span>
          <span className="min-w-0 flex-1 truncate text-xs">
            {checking ? t("Checking…") : ok ? t("Applied") : result?.message ? translateServer(result.message) : ""}
          </span>
          <button onClick={() => onChange(null)} aria-label={t("Remove coupon")} className="rounded p-0.5 hover:bg-black/5">
            <X className="size-4" aria-hidden />
          </button>
        </div>
      </div>
    );
  }
  if (!open) {
    return (
      <button onClick={() => setOpen(true)} className="mb-3 inline-flex items-center gap-1.5 text-sm text-primary hover:underline">
        <TicketPercent className="size-4" aria-hidden />
        {t("Add coupon code")}
      </button>
    );
  }
  const apply = () => {
    const value = text.trim().toUpperCase();
    if (value) onChange(value);
    setText("");
    setOpen(false);
  };
  return (
    <div className="mb-3 flex gap-2">
      <Input
        autoFocus
        aria-label={t("Coupon code")}
        placeholder={t("Coupon code")}
        value={text}
        maxLength={40}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") apply();
          if (e.key === "Escape") setOpen(false);
        }}
        className="h-9 font-mono text-sm uppercase"
      />
      <Button size="sm" className="h-9" onClick={apply} disabled={!text.trim()}>
        {t("Apply")}
      </Button>
    </div>
  );
}

/** A customer's loyalty points: what they have, and using some on this sale (up to what the sale allows). */
export function PointsField({
  tier,
  balance,
  rules,
  usable,
  using,
  currency,
  onChange,
}: {
  tier: Tier | null;
  balance: number;
  rules: LoyaltyRules;
  usable: number;
  using: number;
  currency: string;
  onChange: (n: number | null) => void;
}) {
  const t = useT();
  const worth = (n: number) => money(Math.round(n * rules.pointValue * 100) / 100, currency);
  const canUse = usable >= rules.minRedeem;
  return (
    <div className="mb-3 rounded-lg bg-surface-2 px-3 py-2 text-sm">
      <div className="flex items-center gap-2">
        <Coins className="size-4 shrink-0 text-warning" aria-hidden />
        <TierBadge tier={tier} />
        <span className="flex-1">
          {t("{n} points", { n: balance.toLocaleString() })} <span className="text-muted">· {t("worth {amount}", { amount: worth(Math.max(0, balance)) })}</span>
        </span>
        {canUse && (
          <label className="flex items-center gap-1.5 text-xs font-medium">
            <input type="checkbox" checked={using > 0} onChange={(e) => onChange(e.target.checked ? usable : null)} />
            {t("Use points")}
          </label>
        )}
      </div>
      {using > 0 ? (
        <div className="mt-2 flex items-center gap-2">
          <Input
            type="number"
            min={rules.minRedeem}
            max={usable}
            value={using}
            aria-label={t("Points to use")}
            onChange={(e) => onChange(Number(e.target.value) || null)}
            className="h-8 w-24 text-right tabular-nums"
          />
          <span className="text-xs text-muted">{t("= {amount} off · up to {n} on this sale", { amount: worth(using), n: usable.toLocaleString() })}</span>
        </div>
      ) : (
        !canUse &&
        balance > 0 && <p className="mt-1 text-xs text-muted">{t("Needs at least {n} points to use on this sale", { n: rules.minRedeem })}</p>
      )}
    </div>
  );
}

export function TotalRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between">
      <dt className="text-muted">{label}</dt>
      <dd className="tabular-nums">{value}</dd>
    </div>
  );
}

export function TileBadge({ offering: o }: { offering: Offering }) {
  const t = useT();
  if (o.type === "PACKAGE") {
    const available = o.packageInfo?.available;
    if (available === null || available === undefined) return <Badge>{t("Package")}</Badge>;
    if (available <= 0) return <Badge tone="danger">{t("Out")}</Badge>;
    return <Badge tone={available <= 3 ? "warning" : "primary"}>{t("{available} available", { available })}</Badge>;
  }
  if (o.type === "SERVICE") {
    return o.durationMinutes ? <span className="text-xs text-muted">{duration(o.durationMinutes)}</span> : <Badge>{t("Service")}</Badge>;
  }
  const stock = o.stock ?? 0;
  if (stock <= 0) return <Badge tone="danger">{t("Out")}</Badge>;
  if (stock <= (o.lowStockThreshold ?? 0)) return <Badge tone="warning">{t("{stock} left", { stock })}</Badge>;
  return (
    <span className="text-xs text-muted tabular-nums">
      {stock}
      {o.unit ? ` ${o.unit}` : ""}
    </span>
  );
}

export const REASONS = [msg("Damaged or near expiry"), msg("Price match"), msg("Bulk deal"), msg("Loyal customer"), msg("Wrong label price")];

/**
 * Changes one line's price. Needs "Override prices"; a reason is required and goes on the order
 * and the audit log. Below the item's cost needs "Sell below cost". The server checks it all again.
 */
export function PriceDialog({
  line,
  currency,
  belowCost,
  onClose,
  onApply,
}: {
  line: CartLine;
  currency: string;
  belowCost: boolean;
  onClose: () => void;
  onApply: (price: number | null, reason: string) => void;
}) {
  const t = useT();
  const o = line.offering;
  const [price, setPrice] = useState(String(line.price ?? o.price));
  const [reason, setReason] = useState(line.priceReason ?? "");
  const value = Number(price);
  const valid = price.trim() !== "" && Number.isFinite(value) && value >= 0 && /^\d+(\.\d{1,2})?$/.test(price.trim());
  const underCost = valid && o.cost > 0 && value < o.cost;
  const unchanged = valid && value === o.price;
  const blocked = !valid || (underCost && !belowCost) || (!unchanged && reason.trim().length < 3);
  const per = o.pricing === "HOURLY" ? ` ${t("per hour")}` : o.unit ? ` ${t("per {unit}", { unit: o.unit })}` : "";

  return (
    <Modal open onClose={onClose} title={t("Change price: {name}", { name: o.name })}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (blocked) return;
          onApply(unchanged ? null : Math.round(value * 100) / 100, unchanged ? "" : reason.trim());
        }}
      >
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted">{t("Catalog price")}</dt>
          <dd className="text-right tabular-nums">
            {money(o.price, currency)}
            {per}
          </dd>
          {o.cost > 0 && (
            <>
              <dt className="text-muted">{t("Cost")}</dt>
              <dd className="text-right tabular-nums">{money(o.cost, currency)}</dd>
            </>
          )}
        </dl>
        <Field
          label={t("New price{per}", { per })}
          error={!valid && price !== "" ? t("Enter an amount, e.g. 4.50") : underCost && !belowCost ? t("Below cost: you need the “Sell below cost” permission") : undefined}
          hint={underCost && belowCost ? t("This is below cost.") : t("VAT and discounts apply to the new price as usual.")}
        >
          <Input autoFocus inputMode="decimal" value={price} onChange={(e) => setPrice(e.target.value.replace(/[^\d.]/g, ""))} />
        </Field>
        <Field label={t("Reason")} hint={t("Required. Saved on the order and in the audit log.")}>
          <Input value={reason} maxLength={255} onChange={(e) => setReason(e.target.value)} disabled={unchanged} />
        </Field>
        <div className="flex flex-wrap gap-1.5">
          {REASONS.map((r) => (
            <button
              key={r}
              type="button"
              disabled={unchanged}
              onClick={() => setReason(t(r))}
              className="rounded-full border border-border px-2.5 py-1 text-xs hover:bg-surface-2 disabled:opacity-40"
            >
              {t(r)}
            </button>
          ))}
        </div>
        <div className="flex justify-between gap-2">
          {line.price != null ? (
            <Button type="button" variant="ghost" onClick={() => onApply(null, "")}>
              {t("Use catalog price")}
            </Button>
          ) : (
            <span />
          )}
          <div className="flex gap-2">
            <Button type="button" variant="secondary" onClick={onClose}>
              {t("Cancel")}
            </Button>
            <Button type="submit" disabled={blocked}>
              {unchanged ? t("Keep catalog price") : t("Apply price")}
            </Button>
          </div>
        </div>
      </form>
    </Modal>
  );
}

/**
 * A % or amount off one cart line. Needs "Discount items"; the role's maximum % applies to both
 * kinds (an amount is checked as a % of the line). The order discount, if any, comes on top.
 */
export function ItemDiscountDialog({
  line,
  gross,
  currency,
  maxPct,
  onClose,
  onApply,
}: {
  line: CartLine;
  gross: number;
  currency: string;
  maxPct: number | null;
  onClose: () => void;
  onApply: (type: DiscountType | null, value: number) => void;
}) {
  const t = useT();
  const [type, setType] = useState<DiscountType>(line.discountType ?? "PERCENT");
  const [value, setValue] = useState(line.discountValue ? String(line.discountValue) : "");
  const v = Number(value) || 0;
  const off = type === "PERCENT" ? Math.round(gross * Math.min(v, 100)) / 100 : Math.min(v, gross);
  // A % discount counts as the % entered; an amount as the % of the line it takes off (like the server).
  const pct = type === "PERCENT" ? v : gross > 0 ? (off / gross) * 100 : 0;
  const tooMuch = maxPct != null && pct > maxPct + 1e-9;
  const invalid = value !== "" && (!/^\d+(\.\d{1,2})?$/.test(value) || (type === "PERCENT" && v > 100));
  return (
    <Modal open onClose={onClose} title={t("Discount: {name}", { name: line.offering.name })}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (!invalid && !tooMuch) onApply(v > 0 ? type : null, v);
        }}
      >
        <p className="text-sm text-muted">
          {t("Line total {gross}", { gross: money(gross, currency) })}
          {maxPct != null && t(" · your role can take up to {maxPct}% off", { maxPct })}
        </p>
        <div className="flex gap-2">
          <div className="flex overflow-hidden rounded-lg border border-border" role="group" aria-label={t("Discount type")}>
            {(["PERCENT", "AMOUNT"] as const).map((x) => (
              <button
                key={x}
                type="button"
                onClick={() => setType(x)}
                aria-pressed={type === x}
                className={cx("h-10 w-12 text-sm", type === x ? "bg-primary text-on-primary" : "hover:bg-surface-2")}
              >
                {x === "PERCENT" ? "%" : "$"}
              </button>
            ))}
          </div>
          <Input
            autoFocus
            inputMode="decimal"
            aria-label={t("Discount")}
            placeholder="0"
            value={value}
            onChange={(e) => setValue(e.target.value.replace(/[^\d.]/g, ""))}
            invalid={invalid || tooMuch}
            className="h-10 flex-1 text-right tabular-nums"
          />
        </div>
        <div className="flex flex-wrap gap-1.5">
          {[5, 10, 15, 20, 50].filter((p) => maxPct == null || p <= maxPct).map((p) => (
            <button
              key={p}
              type="button"
              onClick={() => {
                setType("PERCENT");
                setValue(String(p));
              }}
              className="rounded-full border border-border px-2.5 py-1 text-xs hover:bg-surface-2"
            >
              {p}%
            </button>
          ))}
        </div>
        <p className={cx("text-sm", tooMuch ? "text-danger" : "")}>
          {tooMuch
            ? t("That is {Number}% off; your role allows up to {maxPct}%.", { Number: Number(pct.toFixed(1)), maxPct })
            : off > 0
              ? t("Takes {off} off ({Number}%); the line becomes {gross}. VAT is worked out after it.", { off: money(off, currency), Number: Number(pct.toFixed(1)), gross: money(gross - off, currency) })
              : t("No discount.")}
        </p>
        <div className="flex justify-between gap-2">
          {line.discountValue ? (
            <Button type="button" variant="ghost" onClick={() => onApply(null, 0)}>
              {t("Remove discount")}
            </Button>
          ) : (
            <span />
          )}
          <div className="flex gap-2">
            <Button type="button" variant="secondary" onClick={onClose}>
              {t("Cancel")}
            </Button>
            <Button type="submit" disabled={invalid || tooMuch}>
              {t("Apply")}
            </Button>
          </div>
        </div>
      </form>
    </Modal>
  );
}
