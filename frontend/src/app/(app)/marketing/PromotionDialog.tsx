"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Search, X } from "lucide-react";
import { useState } from "react";

import { TIERS } from "@/components/TierBadge";
import { Button, ErrorNote, Field, Input, Modal, Select, cx } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { useBranch } from "@/lib/branch";
import { money } from "@/lib/format";
import { msg, useT } from "@/lib/i18n";
import { useCategories, useCurrency } from "@/lib/queries";
import type { DiscountType, Offering, Page, Promotion, PromotionKind, PromotionScope, Tier } from "@/lib/types";

import { describeDeal, promotionsKey } from "./PromotionsTab";

const KINDS: { key: PromotionKind; label: string; hint: string }[] = [
  { key: "ITEM_DISCOUNT", label: msg("Item discount"), hint: msg("% or amount off each item that qualifies") },
  { key: "BUY_X_GET_Y", label: msg("Buy X get Y"), hint: msg("The cheapest units are free or discounted") },
  { key: "ORDER_DISCOUNT", label: msg("Order discount"), hint: msg("% or amount off the whole order") },
];

const DAYS = [msg("Mon"), msg("Tue"), msg("Wed"), msg("Thu"), msg("Fri"), msg("Sat"), msg("Sun")];

/** What the API takes to save a promotion, from one as listed (used to pause or resume it too). */
export function requestFrom(p: Promotion) {
  return {
    name: p.name,
    description: p.description,
    kind: p.kind,
    discountType: p.discountType,
    discountValue: p.discountValue,
    scope: p.scope,
    categoryIds: p.categoryIds,
    offeringIds: p.offeringIds,
    buyQty: p.buyQty,
    getQty: p.getQty,
    minSpend: p.minSpend,
    maxDiscount: p.maxDiscount,
    startsOn: p.startsOn,
    endsOn: p.endsOn,
    daysOfWeek: p.daysOfWeek,
    timeFrom: p.timeFrom,
    timeTo: p.timeTo,
    requiresCode: p.requiresCode,
    customersOnly: p.customersOnly,
    minTier: p.minTier,
    usageLimit: p.usageLimit,
    perCustomerLimit: p.perCustomerLimit,
    priority: p.priority,
    active: p.active,
    branchIds: p.branchIds,
  };
}

const num = (s: string) => (s.trim() === "" ? null : Number(s));
const str = (n: number | null | undefined) => (n == null ? "" : String(n));

/** Create or change a promotion. */
export function PromotionDialog({ promotion, onClose }: { promotion: Promotion | null; onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const currency = useCurrency();
  const categories = useCategories().data ?? [];
  const { branches } = useBranch();
  const p = promotion;

  const [name, setName] = useState(p?.name ?? "");
  const [description, setDescription] = useState(p?.description ?? "");
  const [kind, setKind] = useState<PromotionKind>(p?.kind ?? "ITEM_DISCOUNT");
  const [discountType, setDiscountType] = useState<DiscountType>(p?.discountType ?? "PERCENT");
  const [value, setValue] = useState(str(p?.discountValue ?? (p ? null : 10)));
  const [buyQty, setBuyQty] = useState(str(p?.buyQty ?? 2));
  const [getQty, setGetQty] = useState(str(p?.getQty ?? 1));
  const [scope, setScope] = useState<PromotionScope>(p?.scope ?? "ALL");
  const [categoryIds, setCategoryIds] = useState<number[]>(p?.categoryIds ?? []);
  // Picked entries; names of an existing promotion's entries come from the catalog below.
  const [offerings, setOfferings] = useState<{ id: number; name: string }[]>(p ? p.offeringIds.map((id) => ({ id, name: "" })) : []);
  const [minSpend, setMinSpend] = useState(str(p?.minSpend));
  const [maxDiscount, setMaxDiscount] = useState(str(p?.maxDiscount));
  const [startsOn, setStartsOn] = useState(p?.startsOn ?? "");
  const [endsOn, setEndsOn] = useState(p?.endsOn ?? "");
  const [days, setDays] = useState<number[]>(p?.daysOfWeek ?? []);
  const [timeFrom, setTimeFrom] = useState(p?.timeFrom?.slice(0, 5) ?? "");
  const [timeTo, setTimeTo] = useState(p?.timeTo?.slice(0, 5) ?? "");
  const [requiresCode, setRequiresCode] = useState(p?.requiresCode ?? false);
  const [customersOnly, setCustomersOnly] = useState(p?.customersOnly ?? false);
  const [minTier, setMinTier] = useState<Tier | "">(p?.minTier ?? "");
  const [usageLimit, setUsageLimit] = useState(str(p?.usageLimit));
  const [perCustomerLimit, setPerCustomerLimit] = useState(str(p?.perCustomerLimit));
  const [priority, setPriority] = useState(str(p?.priority ?? 0));
  const [branchIds, setBranchIds] = useState<number[]>(p?.branchIds ?? []);
  const [active, setActive] = useState(p?.active ?? true);

  const known = useQuery({
    queryKey: ["offerings", "by-ids", p?.offeringIds],
    enabled: !!p && p.offeringIds.length > 0,
    queryFn: () => api<Page<Offering>>(`/offerings${queryString({ ids: p!.offeringIds.join(","), size: 200 })}`),
  });
  const named = offerings.map((o) => ({ ...o, name: o.name || known.data?.items.find((x) => x.id === o.id)?.name || `#${o.id}` }));

  const bxgy = kind === "BUY_X_GET_Y";
  const orderLevel = kind === "ORDER_DISCOUNT";
  const effectiveType: DiscountType = bxgy ? "PERCENT" : discountType;
  const effectiveScope: PromotionScope = orderLevel ? "ALL" : scope;

  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: name.trim(),
        description: description.trim() || null,
        kind,
        discountType: effectiveType,
        discountValue: num(value),
        scope: effectiveScope,
        categoryIds: effectiveScope === "CATEGORIES" ? categoryIds : [],
        offeringIds: effectiveScope === "OFFERINGS" ? offerings.map((o) => o.id) : [],
        buyQty: bxgy ? num(buyQty) : null,
        getQty: bxgy ? num(getQty) : null,
        minSpend: num(minSpend),
        maxDiscount: orderLevel && effectiveType === "PERCENT" ? num(maxDiscount) : null,
        startsOn: startsOn || null,
        endsOn: endsOn || null,
        daysOfWeek: days,
        timeFrom: timeFrom || null,
        timeTo: timeTo || null,
        requiresCode,
        customersOnly,
        minTier: minTier || null,
        usageLimit: num(usageLimit),
        perCustomerLimit: num(perCustomerLimit),
        priority: num(priority) ?? 0,
        active,
        branchIds,
      };
      return p ? api<Promotion>(`/promotions/${p.id}`, { method: "PUT", json: body }) : api<Promotion>("/promotions", { method: "POST", json: body });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: promotionsKey });
      onClose();
    },
  });

  const preview =
    num(value) != null && num(value)! > 0
      ? describeDeal(
          {
            kind,
            discountType: effectiveType,
            discountValue: num(value)!,
            buyQty: num(buyQty),
            getQty: num(getQty),
            minSpend: num(minSpend),
            maxDiscount: num(maxDiscount),
            scope: effectiveScope,
          },
          currency,
        )
      : "";

  return (
    <Modal open onClose={onClose} title={p ? t("Edit promotion") : t("New promotion")} wide>
      <form
        className="flex flex-col gap-6"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <Section title={t("The deal")}>
          <Field label={t("Name")}>
            <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={120} required placeholder={t("e.g. Weekend diaper sale")} />
          </Field>
          <div role="radiogroup" aria-label={t("Kind")} className="grid gap-2 sm:grid-cols-3">
            {KINDS.map((k) => (
              <button
                key={k.key}
                type="button"
                role="radio"
                aria-checked={kind === k.key}
                onClick={() => setKind(k.key)}
                className={cx(
                  "rounded-xl border p-3 text-left transition-colors",
                  kind === k.key ? "border-primary bg-primary-soft" : "border-border hover:bg-surface-2",
                )}
              >
                <span className={cx("block text-sm font-medium", kind === k.key && "text-primary")}>{t(k.label)}</span>
                <span className="mt-0.5 block text-xs text-muted">{t(k.hint)}</span>
              </button>
            ))}
          </div>
          {bxgy ? (
            <div className="grid grid-cols-3 gap-3">
              <Field label={t("Buy")}>
                <Input type="number" min={1} value={buyQty} onChange={(e) => setBuyQty(e.target.value)} required />
              </Field>
              <Field label={t("Get")}>
                <Input type="number" min={1} value={getQty} onChange={(e) => setGetQty(e.target.value)} required />
              </Field>
              <Field label={t("% off what they get")} hint={t("100 = free")}>
                <Input type="number" min={1} max={100} step="0.01" value={value} onChange={(e) => setValue(e.target.value)} required />
              </Field>
            </div>
          ) : (
            <div className="flex items-end gap-2">
              <div className="flex overflow-hidden rounded-lg border border-border" role="group" aria-label={t("Discount type")}>
                {(["PERCENT", "AMOUNT"] as const).map((x) => (
                  <button
                    key={x}
                    type="button"
                    onClick={() => setDiscountType(x)}
                    aria-pressed={discountType === x}
                    className={cx("h-10 w-11 text-sm", discountType === x ? "bg-primary text-on-primary" : "hover:bg-surface-2")}
                  >
                    {x === "PERCENT" ? "%" : "$"}
                  </button>
                ))}
              </div>
              <Field label={discountType === "PERCENT" ? t("Percent off") : kind === "ITEM_DISCOUNT" ? t("Amount off each unit") : t("Amount off")}>
                <Input
                  type="number"
                  min={0.01}
                  max={discountType === "PERCENT" ? 100 : undefined}
                  step="0.01"
                  value={value}
                  onChange={(e) => setValue(e.target.value)}
                  required
                />
              </Field>
              {orderLevel && discountType === "PERCENT" && (
                <Field label={t("Up to (optional)")}>
                  <Input type="number" min={0.01} step="0.01" value={maxDiscount} onChange={(e) => setMaxDiscount(e.target.value)} />
                </Field>
              )}
            </div>
          )}
          {preview && <p className="rounded-lg bg-surface-2 px-3 py-2 text-sm">{preview}</p>}
          <Field label={t("Description (optional)")}>
            <Input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} />
          </Field>
        </Section>

        {!orderLevel && (
          <Section title={t("What it applies to")}>
            <Segmented
              value={scope}
              onChange={setScope}
              options={[
                { key: "ALL", label: t("Everything") },
                { key: "CATEGORIES", label: t("Categories") },
                { key: "OFFERINGS", label: t("Specific items") },
              ]}
            />
            {scope === "CATEGORIES" && (
              <div className="flex flex-wrap gap-2">
                {categories.map((c) => (
                  <Chip
                    key={c.id}
                    on={categoryIds.includes(c.id)}
                    onClick={() => setCategoryIds((ids) => (ids.includes(c.id) ? ids.filter((x) => x !== c.id) : [...ids, c.id]))}
                  >
                    {c.name}
                  </Chip>
                ))}
              </div>
            )}
            {scope === "OFFERINGS" && <OfferingPicker picked={named} onChange={setOfferings} currency={currency} />}
            <p className="text-xs text-muted">{t("Lines whose price the cashier changed don't get promotions. Each line gets one promotion at most.")}</p>
          </Section>
        )}

        <Section title={t("When it runs")}>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("From (optional)")}>
              <Input type="date" value={startsOn} onChange={(e) => setStartsOn(e.target.value)} />
            </Field>
            <Field label={t("Until (optional)")}>
              <Input type="date" value={endsOn} min={startsOn || undefined} onChange={(e) => setEndsOn(e.target.value)} />
            </Field>
          </div>
          <div>
            <span className="mb-1.5 block text-sm font-medium">{t("Days")}</span>
            <div className="flex flex-wrap gap-1.5">
              {DAYS.map((d, i) => (
                <Chip key={d} on={days.includes(i + 1)} onClick={() => setDays((x) => (x.includes(i + 1) ? x.filter((y) => y !== i + 1) : [...x, i + 1]))}>
                  {t(d)}
                </Chip>
              ))}
            </div>
            <p className="mt-1 text-xs text-muted">{t("None picked: every day.")}</p>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("From time (optional)")} hint={t("e.g. a happy hour, in the store's time")}>
              <Input type="time" value={timeFrom} onChange={(e) => setTimeFrom(e.target.value)} />
            </Field>
            <Field label={t("To time")}>
              <Input type="time" value={timeTo} onChange={(e) => setTimeTo(e.target.value)} />
            </Field>
          </div>
        </Section>

        <Section title={t("Who can use it")}>
          {branches.length > 1 && (
            <div>
              <span className="mb-1.5 block text-sm font-medium">{t("Branches")}</span>
              <div className="flex flex-wrap gap-1.5">
                {branches
                  .filter((b) => b.active)
                  .map((b) => (
                    <Chip key={b.id} on={branchIds.includes(b.id)} onClick={() => setBranchIds((x) => (x.includes(b.id) ? x.filter((y) => y !== b.id) : [...x, b.id]))}>
                      {b.name}
                    </Chip>
                  ))}
              </div>
              <p className="mt-1 text-xs text-muted">{t("None picked: every branch.")}</p>
            </div>
          )}
          <Toggle checked={requiresCode} onChange={setRequiresCode} label={t("Needs a coupon code")} hint={t("Only applies when the cashier enters one of its codes")} />
          <Toggle checked={customersOnly} onChange={setCustomersOnly} label={t("Customers on file only")} hint={t("Not for the Walk-in Customer")} />
          <Field label={t("Member tier")} hint={t("Only customers in this tier or above")}>
            <Select value={minTier} onChange={(e) => setMinTier(e.target.value as Tier | "")}>
              <option value="">{t("Any customer")}</option>
              {TIERS.map((x) => (
                <option key={x.code} value={x.code}>
                  {t("{tier} and above", { tier: t(x.label) })}
                </option>
              ))}
            </Select>
          </Field>
          <Field label={t("Minimum spend (optional)")} hint={orderLevel ? t("After item promotions and item discounts") : t("The cart before discounts")}>
            <Input type="number" min={0.01} step="0.01" value={minSpend} onChange={(e) => setMinSpend(e.target.value)} />
          </Field>
        </Section>

        <Section title={t("Limits")}>
          <div className="grid grid-cols-3 gap-3">
            <Field label={t("Uses in total")} hint={t("Blank: no limit")}>
              <Input type="number" min={1} value={usageLimit} onChange={(e) => setUsageLimit(e.target.value)} />
            </Field>
            <Field label={t("Per customer")} hint={t("Blank: no limit")}>
              <Input type="number" min={1} value={perCustomerLimit} onChange={(e) => setPerCustomerLimit(e.target.value)} />
            </Field>
            <Field label={t("Priority")} hint={t("Higher goes first")}>
              <Input type="number" min={-100} max={100} value={priority} onChange={(e) => setPriority(e.target.value)} />
            </Field>
          </div>
          <Toggle checked={active} onChange={setActive} label={t("Active")} hint={t("Paused promotions don't apply")} />
        </Section>

        <ErrorNote error={save.error} />
        <div className="sticky bottom-0 -mx-5 -mb-4 flex justify-end gap-2 border-t border-border bg-surface px-5 py-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending}>
            {p ? t("Save") : t("Create promotion")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3">
      <h3 className="text-xs font-semibold tracking-wider text-muted uppercase">{title}</h3>
      {children}
    </section>
  );
}

function Segmented<T extends string>({ value, onChange, options }: { value: T; onChange: (v: T) => void; options: { key: T; label: string }[] }) {
  return (
    <div className="inline-flex self-start rounded-full bg-surface-2 p-1 ring-1 ring-border">
      {options.map((o) => (
        <button
          key={o.key}
          type="button"
          aria-pressed={value === o.key}
          onClick={() => onChange(o.key)}
          className={cx(
            "rounded-full px-3 py-1 text-sm font-medium transition-colors",
            value === o.key ? "bg-surface text-text shadow-sm ring-1 ring-border" : "text-muted hover:text-text",
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

function Chip({ on, onClick, children }: { on: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={on}
      onClick={onClick}
      className={cx(
        "rounded-full px-3 py-1 text-sm ring-1 transition-colors",
        on ? "bg-primary text-on-primary ring-primary" : "bg-surface text-text ring-border hover:bg-surface-2",
      )}
    >
      {children}
    </button>
  );
}

function Toggle({ checked, onChange, label, hint }: { checked: boolean; onChange: (v: boolean) => void; label: string; hint?: string }) {
  return (
    <label className="flex cursor-pointer items-start gap-3">
      <input type="checkbox" className="peer sr-only" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span
        aria-hidden
        className={cx(
          "relative mt-0.5 h-6 w-10 shrink-0 rounded-full transition-colors peer-focus-visible:ring-2 peer-focus-visible:ring-primary",
          checked ? "bg-primary" : "bg-border",
        )}
      >
        <span className={cx("absolute top-0.5 size-5 rounded-full bg-white shadow transition-[left]", checked ? "left-[18px]" : "left-0.5")} />
      </span>
      <span>
        <span className="block text-sm font-medium">{label}</span>
        {hint && <span className="block text-xs text-muted">{hint}</span>}
      </span>
    </label>
  );
}

/** Search the catalog and pick entries for a promotion. */
function OfferingPicker({
  picked,
  onChange,
  currency,
}: {
  picked: { id: number; name: string }[];
  onChange: (x: { id: number; name: string }[]) => void;
  currency: string | undefined;
}) {
  const t = useT();
  const [search, setSearch] = useState("");
  const results = useQuery({
    queryKey: ["offerings", "promotion-picker", search],
    enabled: search.trim().length >= 2,
    queryFn: () => api<Page<Offering>>(`/offerings${queryString({ q: search.trim(), size: 8, forSale: true })}`),
  });
  return (
    <div className="flex flex-col gap-2">
      <div className="relative">
        <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted" aria-hidden />
        <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t("Search name or SKU")} className="pl-9" />
      </div>
      {results.data && search.trim().length >= 2 && (
        <ul className="max-h-48 overflow-y-auto rounded-lg border border-border">
          {results.data.items.map((o) => {
            const on = picked.some((x) => x.id === o.id);
            return (
              <li key={o.id}>
                <button
                  type="button"
                  onClick={() => onChange(on ? picked.filter((x) => x.id !== o.id) : [...picked, { id: o.id, name: o.name }])}
                  className={cx("flex w-full justify-between gap-3 px-3 py-2 text-left text-sm hover:bg-surface-2", on && "bg-primary-soft text-primary")}
                >
                  <span className="truncate">{o.name}</span>
                  <span className="shrink-0 text-muted tabular-nums">{money(o.price, currency)}</span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {picked.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {picked.map((o) => (
            <span key={o.id} className="inline-flex items-center gap-1 rounded-full bg-primary-soft py-1 pr-1 pl-3 text-sm text-primary">
              {o.name}
              <button type="button" onClick={() => onChange(picked.filter((x) => x.id !== o.id))} aria-label={t("Remove {name}", { name: o.name })} className="rounded-full p-0.5 hover:bg-primary/10">
                <X className="size-3.5" aria-hidden />
              </button>
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
