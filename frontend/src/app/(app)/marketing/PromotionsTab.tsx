"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BadgePercent, CalendarClock, Gift, Pause, Pencil, Play, Plus, ShoppingBag, TicketPercent, Trash2 } from "lucide-react";
import { useState } from "react";

import { Badge, Button, ErrorNote, Spinner, cx } from "@/components/ui";
import { api } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { money, shortDate } from "@/lib/format";
import { msg, translate, useT } from "@/lib/i18n";
import { useCurrency } from "@/lib/queries";
import { tierLabel } from "@/components/TierBadge";
import type { Promotion, PromotionStatus } from "@/lib/types";

import { CouponsDialog } from "./CouponsDialog";
import { PromotionDialog, requestFrom } from "./PromotionDialog";

export const promotionsKey = ["promotions"] as const;

const STATUS: Record<PromotionStatus, { label: string; tone: "success" | "primary" | "neutral" | "warning" }> = {
  RUNNING: { label: msg("Running"), tone: "success" },
  SCHEDULED: { label: msg("Scheduled"), tone: "primary" },
  PAUSED: { label: msg("Paused"), tone: "neutral" },
  ENDED: { label: msg("Ended"), tone: "neutral" },
  USED_UP: { label: msg("Used up"), tone: "warning" },
};

const FILTERS: { key: PromotionStatus | "ALL"; label: string }[] = [
  { key: "ALL", label: msg("All") },
  { key: "RUNNING", label: msg("Running") },
  { key: "SCHEDULED", label: msg("Scheduled") },
  { key: "PAUSED", label: msg("Paused") },
  { key: "ENDED", label: msg("Ended") },
];

const KIND_ICON = { ITEM_DISCOUNT: BadgePercent, BUY_X_GET_Y: Gift, ORDER_DISCOUNT: ShoppingBag };

/** Promotions: how each is doing, and creating, changing, pausing and deleting them. */
export function PromotionsTab() {
  const t = useT();
  const can = useCan();
  const manage = can("PROMOTIONS_MANAGE");
  const currency = useCurrency();
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<PromotionStatus | "ALL">("ALL");
  const [editing, setEditing] = useState<Promotion | "new" | null>(null);
  const [codesFor, setCodesFor] = useState<Promotion | null>(null);
  const list = useQuery({ queryKey: promotionsKey, queryFn: () => api<Promotion[]>("/promotions") });

  const toggle = useMutation({
    mutationFn: (p: Promotion) => api<Promotion>(`/promotions/${p.id}`, { method: "PUT", json: { ...requestFrom(p), active: !p.active } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: promotionsKey }),
  });
  const remove = useMutation({
    mutationFn: (p: Promotion) => api<void>(`/promotions/${p.id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: promotionsKey }),
  });

  if (list.isPending) return <Spinner />;
  if (list.error) return <ErrorNote error={list.error} />;
  const all = list.data;
  const shown = filter === "ALL" ? all : all.filter((p) => p.status === filter);
  const running = all.filter((p) => p.status === "RUNNING").length;
  const given = all.reduce((n, p) => n + p.discountGiven, 0);
  const sales = all.reduce((n, p) => n + p.orders, 0);

  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <Stat label={t("Running now")} value={String(running)} />
        <Stat label={t("Discount given")} value={money(given, currency)} />
        <Stat label={t("Sales with a promotion")} value={String(sales)} />
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <div role="group" aria-label={t("Show")} className="inline-flex rounded-full bg-surface-2 p-1 ring-1 ring-border">
          {FILTERS.map((f) => (
            <button
              key={f.key}
              type="button"
              aria-pressed={filter === f.key}
              onClick={() => setFilter(f.key)}
              className={cx(
                "rounded-full px-3 py-1 text-sm font-medium whitespace-nowrap transition-colors",
                filter === f.key ? "bg-surface text-text shadow-sm ring-1 ring-border" : "text-muted hover:text-text",
              )}
            >
              {t(f.label)}
            </button>
          ))}
        </div>
        {manage && (
          <Button className="ml-auto" onClick={() => setEditing("new")}>
            <Plus className="size-4" aria-hidden /> {t("New promotion")}
          </Button>
        )}
      </div>

      {(toggle.error || remove.error) && <ErrorNote error={toggle.error ?? remove.error} />}

      {shown.length === 0 ? (
        <div className="grid place-items-center gap-2 rounded-[20px] border border-dashed border-border px-6 py-14 text-center">
          <BadgePercent className="size-9 text-muted" aria-hidden />
          <p className="font-medium">{all.length === 0 ? t("No promotions yet") : t("Nothing here")}</p>
          <p className="max-w-sm text-sm text-muted">
            {all.length === 0
              ? t("Create a % off, buy X get Y or order discount. It applies at checkout on its own, or with a coupon code.")
              : t("No promotions match this filter.")}
          </p>
        </div>
      ) : (
        <ul className="grid gap-3 lg:grid-cols-2">
          {shown.map((p) => {
            const Icon = KIND_ICON[p.kind];
            const status = STATUS[p.status];
            return (
              <li key={p.id} className="flex flex-col rounded-[20px] border border-border bg-surface p-5">
                <div className="flex items-start gap-3">
                  <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-primary-soft text-primary">
                    <Icon className="size-5" aria-hidden />
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="font-semibold tracking-tight">{p.name}</h3>
                      <Badge tone={status.tone}>{t(status.label)}</Badge>
                      {p.requiresCode && (
                        <Badge tone="primary">
                          <TicketPercent className="mr-1 size-3" aria-hidden />
                          {t("Coupon")}
                        </Badge>
                      )}
                    </div>
                    <p className="mt-0.5 text-sm">{describeDeal(p, currency)}</p>
                    <p className="mt-1 flex items-center gap-1.5 text-xs text-muted">
                      <CalendarClock className="size-3.5 shrink-0" aria-hidden />
                      {describeWhen(p)}
                    </p>
                  </div>
                </div>
                <dl className="mt-4 grid grid-cols-3 gap-3 border-t border-border pt-3 text-sm">
                  <div>
                    <dt className="text-xs text-muted">{t("Uses")}</dt>
                    <dd className="font-medium tabular-nums">{p.usageLimit ? `${p.usedCount} / ${p.usageLimit}` : p.usedCount}</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-muted">{t("Discount given")}</dt>
                    <dd className="font-medium tabular-nums">{money(p.discountGiven, currency)}</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-muted">{t("Sales")}</dt>
                    <dd className="font-medium tabular-nums">{p.orders}</dd>
                  </div>
                </dl>
                <div className="mt-3 flex flex-wrap gap-2">
                  {p.requiresCode && (
                    <Button size="sm" variant="secondary" onClick={() => setCodesFor(p)}>
                      <TicketPercent className="size-4" aria-hidden />
                      {t("Codes ({n})", { n: p.codes })}
                    </Button>
                  )}
                  {manage && (
                    <>
                      <Button size="sm" variant="ghost" onClick={() => setEditing(p)}>
                        <Pencil className="size-4" aria-hidden /> {t("Edit")}
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => toggle.mutate(p)} loading={toggle.isPending && toggle.variables?.id === p.id}>
                        {p.active ? <Pause className="size-4" aria-hidden /> : <Play className="size-4" aria-hidden />}
                        {p.active ? t("Pause") : t("Resume")}
                      </Button>
                      {p.orders === 0 && (
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-danger"
                          onClick={() => window.confirm(translate("Delete {name}? This can't be undone.", { name: p.name })) && remove.mutate(p)}
                        >
                          <Trash2 className="size-4" aria-hidden /> {t("Delete")}
                        </Button>
                      )}
                    </>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}

      {editing && <PromotionDialog promotion={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
      {codesFor && <CouponsDialog promotion={codesFor} onClose={() => setCodesFor(null)} />}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[20px] border border-border bg-surface p-4">
      <div className="text-sm text-muted">{label}</div>
      <div className="mt-1 text-2xl font-semibold tracking-tight tabular-nums">{value}</div>
    </div>
  );
}

const pct = (v: number) => `${Number(v.toFixed(2))}%`;

/** "20% off · Diapers", "Buy 2 get 1 free", "$5.00 off orders over $50.00". */
export function describeDeal(p: Pick<Promotion, "kind" | "discountType" | "discountValue" | "buyQty" | "getQty" | "minSpend" | "maxDiscount" | "scope">, currency: string | undefined) {
  const value = p.discountType === "PERCENT" ? pct(p.discountValue) : money(p.discountValue, currency);
  let deal: string;
  if (p.kind === "BUY_X_GET_Y") {
    deal =
      p.discountValue >= 100
        ? translate("Buy {buy} get {get} free", { buy: p.buyQty, get: p.getQty })
        : translate("Buy {buy} get {get} at {value} off", { buy: p.buyQty, get: p.getQty, value });
  } else if (p.kind === "ORDER_DISCOUNT") {
    deal = translate("{value} off the order", { value });
    if (p.maxDiscount) deal += ` (${translate("up to {amount}", { amount: money(p.maxDiscount, currency) })})`;
  } else {
    deal = p.discountType === "AMOUNT" ? translate("{value} off each", { value }) : translate("{value} off", { value });
  }
  const what = p.kind === "ORDER_DISCOUNT" ? null : p.scope === "CATEGORIES" ? translate("selected categories") : p.scope === "OFFERINGS" ? translate("selected items") : null;
  const min = p.minSpend ? translate("min. spend {amount}", { amount: money(p.minSpend, currency) }) : null;
  return [deal, what, min].filter(Boolean).join(" · ");
}

const DAYS = [msg("Mon"), msg("Tue"), msg("Wed"), msg("Thu"), msg("Fri"), msg("Sat"), msg("Sun")];

/** "1 Nov – 30 Nov · Sat, Sun · 17:00–19:00 · customers on file". */
function describeWhen(p: Promotion) {
  const parts: string[] = [];
  if (p.startsOn || p.endsOn) parts.push(`${p.startsOn ? shortDate(p.startsOn) : "…"} – ${p.endsOn ? shortDate(p.endsOn) : "…"}`);
  else parts.push(translate("Always"));
  if (p.daysOfWeek.length) parts.push(p.daysOfWeek.map((d) => translate(DAYS[d - 1])).join(", "));
  if (p.timeFrom && p.timeTo) parts.push(`${p.timeFrom.slice(0, 5)}–${p.timeTo.slice(0, 5)}`);
  if (p.customersOnly) parts.push(translate("customers on file"));
  if (p.minTier) parts.push(translate("{tier} and above", { tier: translate(tierLabel(p.minTier)) }));
  if (p.perCustomerLimit) parts.push(translate("{n} per customer", { n: p.perCustomerLimit }));
  return parts.join(" · ");
}
