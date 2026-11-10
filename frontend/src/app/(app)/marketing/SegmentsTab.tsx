"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, Pencil, Plus, Trash2, Users } from "lucide-react";
import { useEffect, useState } from "react";

import { TIERS, tierLabel } from "@/components/TierBadge";
import { Button, ErrorNote, Field, Input, Modal, Select, Spinner, cx } from "@/components/ui";
import { api, download } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { money, shortDate } from "@/lib/format";
import { msg, translate, useT } from "@/lib/i18n";
import { useCategories, useCurrency } from "@/lib/queries";
import type { Segment, SegmentMember, SegmentRules, Tier } from "@/lib/types";

export const segmentsKey = ["segments"] as const;

/** Ready-made starting points. */
const PRESETS: { name: string; description: string; rules: SegmentRules }[] = [
  { name: msg("VIP customers"), description: msg("Spent 200 or more"), rules: { minSpent: 200 } },
  { name: msg("Lapsed customers"), description: msg("Bought before, but not in the last 60 days"), rules: { notBoughtForDays: 60 } },
  { name: msg("New customers"), description: msg("Added in the last 30 days"), rules: { joinedWithinDays: 30 } },
  { name: msg("Regulars"), description: msg("5 or more orders"), rules: { minOrders: 5 } },
  { name: msg("Reachable by email"), description: msg("Have an email address"), rules: { hasEmail: true } },
  { name: msg("Gold and Platinum"), description: msg("Your best customers by tier"), rules: { minTier: "GOLD" } },
];

/** Customer segments: saved groups, kept current, for campaigns and exports. */
export function SegmentsTab() {
  const t = useT();
  const manage = useCan()("CAMPAIGNS_MANAGE");
  const currency = useCurrency();
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: segmentsKey, queryFn: () => api<Segment[]>("/segments") });
  const [editing, setEditing] = useState<Segment | { preset: (typeof PRESETS)[number] | null } | null>(null);
  const remove = useMutation({
    mutationFn: (s: Segment) => api<void>(`/segments/${s.id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: segmentsKey }),
  });

  if (list.isPending) return <Spinner />;
  if (list.error) return <ErrorNote error={list.error} />;

  return (
    <div className="flex flex-col gap-4">
      {manage && (
        <section className="rounded-[20px] border border-border bg-surface p-5">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <h2 className="font-semibold tracking-tight">{t("Start from")}</h2>
            <Button onClick={() => setEditing({ preset: null })}>
              <Plus className="size-4" aria-hidden /> {t("New segment")}
            </Button>
          </div>
          <div className="flex flex-wrap gap-2">
            {PRESETS.map((p) => (
              <button
                key={p.name}
                type="button"
                onClick={() => setEditing({ preset: p })}
                className="rounded-xl px-3 py-2 text-left ring-1 ring-border transition-colors hover:bg-surface-2"
              >
                <span className="block text-sm font-medium">{t(p.name)}</span>
                <span className="block text-xs text-muted">{t(p.description)}</span>
              </button>
            ))}
          </div>
        </section>
      )}

      <ErrorNote error={remove.error} />
      {list.data.length === 0 ? (
        <div className="grid place-items-center gap-2 rounded-[20px] border border-dashed border-border px-6 py-14 text-center">
          <Users className="size-9 text-muted" aria-hidden />
          <p className="font-medium">{t("No segments yet")}</p>
          <p className="max-w-sm text-sm text-muted">{t("Group customers by what they spend, how often and how recently they buy, and use the groups for campaigns.")}</p>
        </div>
      ) : (
        <ul className="grid gap-3 lg:grid-cols-2">
          {list.data.map((s) => (
            <li key={s.id} className="flex flex-col rounded-[20px] border border-border bg-surface p-5">
              <div className="flex items-start gap-3">
                <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-primary-soft text-primary">
                  <Users className="size-5" aria-hidden />
                </span>
                <div className="min-w-0 flex-1">
                  <h3 className="font-semibold tracking-tight">{s.name}</h3>
                  {s.description && <p className="text-sm text-muted">{s.description}</p>}
                </div>
                <div className="text-right">
                  <div className="text-2xl font-semibold tracking-tight tabular-nums">{s.members.toLocaleString()}</div>
                  <div className="text-xs text-muted">{t("customers")}</div>
                </div>
              </div>
              <RuleChips rules={s.rules} currency={currency} />
              {manage && (
                <div className="mt-3 flex flex-wrap gap-2 border-t border-border pt-3">
                  <Button size="sm" variant="ghost" onClick={() => setEditing(s)}>
                    <Pencil className="size-4" aria-hidden /> {t("Edit")}
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => download(`/segments/${s.id}/members.csv`, `segment-${s.id}.csv`)}>
                    <Download className="size-4" aria-hidden /> {t("Export CSV")}
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    className="text-danger"
                    onClick={() => window.confirm(translate("Delete {name}? This can't be undone.", { name: s.name })) && remove.mutate(s)}
                  >
                    <Trash2 className="size-4" aria-hidden /> {t("Delete")}
                  </Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {editing && <SegmentDialog segment={"id" in editing ? editing : null} preset={"preset" in editing ? editing.preset : null} onClose={() => setEditing(null)} />}
    </div>
  );
}

/** The rules as short phrases: "Spent ≥ $200", "No purchase in 60 days"… */
export function ruleText(r: SegmentRules, currency: string | undefined, categoryName: (id: number) => string = (id) => `#${id}`): string[] {
  const out: string[] = [];
  if (r.minSpent != null) out.push(translate("Spent at least {amount}", { amount: money(r.minSpent, currency) }));
  if (r.maxSpent != null) out.push(translate("Spent at most {amount}", { amount: money(r.maxSpent, currency) }));
  if (r.minOrders != null) out.push(translate("At least {n} orders", { n: r.minOrders }));
  if (r.maxOrders != null) out.push(translate("At most {n} orders", { n: r.maxOrders }));
  if (r.boughtWithinDays != null) out.push(translate("Bought in the last {n} days", { n: r.boughtWithinDays }));
  if (r.notBoughtForDays != null) out.push(translate("No purchase in {n} days", { n: r.notBoughtForDays }));
  if (r.joinedWithinDays != null) out.push(translate("Added in the last {n} days", { n: r.joinedWithinDays }));
  if (r.minPoints != null) out.push(translate("At least {n} points", { n: r.minPoints }));
  if (r.hasPhone != null) out.push(r.hasPhone ? translate("Has a phone number") : translate("No phone number"));
  if (r.hasEmail != null) out.push(r.hasEmail ? translate("Has an email") : translate("No email"));
  if (r.categoryIds?.length) out.push(translate("Bought from {categories}", { categories: r.categoryIds.map(categoryName).join(", ") }));
  if (r.minTier) out.push(translate("{tier} and above", { tier: translate(tierLabel(r.minTier)) }));
  return out;
}

function RuleChips({ rules, currency }: { rules: SegmentRules; currency: string | undefined }) {
  const t = useT();
  const categories = useCategories().data ?? [];
  const chips = ruleText(rules, currency, (id) => categories.find((c) => c.id === id)?.name ?? `#${id}`);
  return (
    <div className="mt-3 flex flex-wrap gap-1.5">
      {chips.length === 0 ? (
        <span className="rounded-full bg-surface-2 px-2.5 py-1 text-xs text-muted">{t("Every customer on file")}</span>
      ) : (
        chips.map((c) => (
          <span key={c} className="rounded-full bg-surface-2 px-2.5 py-1 text-xs">
            {c}
          </span>
        ))
      )}
    </div>
  );
}

const n = (s: string) => (s.trim() === "" ? null : Number(s));
const s = (v: number | null | undefined) => (v == null ? "" : String(v));

/** Create or change a segment, with a live count and sample of who is in it. */
function SegmentDialog({ segment, preset, onClose }: { segment: Segment | null; preset: (typeof PRESETS)[number] | null; onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const currency = useCurrency();
  const categories = useCategories().data ?? [];
  const start = segment?.rules ?? preset?.rules ?? {};
  const [name, setName] = useState(segment?.name ?? (preset ? translate(preset.name) : ""));
  const [description, setDescription] = useState(segment?.description ?? (preset ? translate(preset.description) : ""));
  const [minSpent, setMinSpent] = useState(s(start.minSpent));
  const [maxSpent, setMaxSpent] = useState(s(start.maxSpent));
  const [minOrders, setMinOrders] = useState(s(start.minOrders));
  const [maxOrders, setMaxOrders] = useState(s(start.maxOrders));
  const [boughtWithin, setBoughtWithin] = useState(s(start.boughtWithinDays));
  const [notBoughtFor, setNotBoughtFor] = useState(s(start.notBoughtForDays));
  const [joinedWithin, setJoinedWithin] = useState(s(start.joinedWithinDays));
  const [minPoints, setMinPoints] = useState(s(start.minPoints));
  const [hasPhone, setHasPhone] = useState<boolean | null>(start.hasPhone ?? null);
  const [hasEmail, setHasEmail] = useState<boolean | null>(start.hasEmail ?? null);
  const [categoryIds, setCategoryIds] = useState<number[]>(start.categoryIds ?? []);
  const [minTier, setMinTier] = useState<Tier | "">(start.minTier ?? "");

  const rules: SegmentRules = {
    minSpent: n(minSpent),
    maxSpent: n(maxSpent),
    minOrders: n(minOrders),
    maxOrders: n(maxOrders),
    boughtWithinDays: n(boughtWithin),
    notBoughtForDays: n(notBoughtFor),
    joinedWithinDays: n(joinedWithin),
    minPoints: n(minPoints),
    hasPhone,
    hasEmail,
    categoryIds: categoryIds.length ? categoryIds : null,
    minTier: minTier || null,
  };
  const key = JSON.stringify(rules);
  const [settled, setSettled] = useState(key);
  useEffect(() => {
    const id = setTimeout(() => setSettled(key), 300);
    return () => clearTimeout(id);
  }, [key]);
  const preview = useQuery({
    queryKey: [...segmentsKey, "preview", settled],
    queryFn: () => api<{ count: number; sample: SegmentMember[] }>("/segments/preview", { method: "POST", json: JSON.parse(settled) }),
    placeholderData: (previous) => previous,
  });

  const save = useMutation({
    mutationFn: () => {
      const body = { name: name.trim(), description: description.trim() || null, rules };
      return segment ? api<Segment>(`/segments/${segment.id}`, { method: "PUT", json: body }) : api<Segment>("/segments", { method: "POST", json: body });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: segmentsKey });
      onClose();
    },
  });

  const tri = (value: boolean | null, set: (v: boolean | null) => void, yes: string, no: string) => (
    <div className="inline-flex rounded-full bg-surface-2 p-0.5 ring-1 ring-border">
      {([null, true, false] as const).map((v) => (
        <button
          key={String(v)}
          type="button"
          aria-pressed={value === v}
          onClick={() => set(v)}
          className={cx(
            "rounded-full px-2.5 py-1 text-xs font-medium",
            value === v ? "bg-surface text-text shadow-sm ring-1 ring-border" : "text-muted hover:text-text",
          )}
        >
          {v === null ? t("Any") : v ? yes : no}
        </button>
      ))}
    </div>
  );

  return (
    <Modal open onClose={onClose} title={segment ? t("Edit segment") : t("New segment")} wide>
      <form
        className="flex flex-col gap-5"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={t("Name")}>
            <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} required />
          </Field>
          <Field label={t("Description (optional)")}>
            <Input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} />
          </Field>
        </div>

        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={t("Spent at least")}>
            <Input type="number" min={0} step="0.01" value={minSpent} onChange={(e) => setMinSpent(e.target.value)} />
          </Field>
          <Field label={t("Spent at most")}>
            <Input type="number" min={0} step="0.01" value={maxSpent} onChange={(e) => setMaxSpent(e.target.value)} />
          </Field>
          <Field label={t("At least … orders")}>
            <Input type="number" min={0} value={minOrders} onChange={(e) => setMinOrders(e.target.value)} />
          </Field>
          <Field label={t("At most … orders")}>
            <Input type="number" min={0} value={maxOrders} onChange={(e) => setMaxOrders(e.target.value)} />
          </Field>
          <Field label={t("Bought in the last … days")}>
            <Input type="number" min={1} max={3650} value={boughtWithin} onChange={(e) => setBoughtWithin(e.target.value)} />
          </Field>
          <Field label={t("No purchase in … days")} hint={t("Bought before, but not since")}>
            <Input type="number" min={1} max={3650} value={notBoughtFor} onChange={(e) => setNotBoughtFor(e.target.value)} />
          </Field>
          <Field label={t("Added in the last … days")}>
            <Input type="number" min={1} max={3650} value={joinedWithin} onChange={(e) => setJoinedWithin(e.target.value)} />
          </Field>
          <Field label={t("At least … points")}>
            <Input type="number" value={minPoints} onChange={(e) => setMinPoints(e.target.value)} />
          </Field>
          <Field label={t("Member tier")}>
            <Select value={minTier} onChange={(e) => setMinTier(e.target.value as Tier | "")}>
              <option value="">{t("Any customer")}</option>
              {TIERS.map((x) => (
                <option key={x.code} value={x.code}>
                  {t("{tier} and above", { tier: t(x.label) })}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-sm">
          <span className="flex items-center gap-2">
            {t("Phone")} {tri(hasPhone, setHasPhone, t("Has one"), t("None"))}
          </span>
          <span className="flex items-center gap-2">
            {t("Email")} {tri(hasEmail, setHasEmail, t("Has one"), t("None"))}
          </span>
        </div>
        <div>
          <span className="mb-1.5 block text-sm font-medium">{t("Bought from (any of)")}</span>
          <div className="flex flex-wrap gap-1.5">
            {categories.map((c) => {
              const on = categoryIds.includes(c.id);
              return (
                <button
                  key={c.id}
                  type="button"
                  aria-pressed={on}
                  onClick={() => setCategoryIds((ids) => (on ? ids.filter((x) => x !== c.id) : [...ids, c.id]))}
                  className={cx(
                    "rounded-full px-3 py-1 text-sm ring-1",
                    on ? "bg-primary text-on-primary ring-primary" : "ring-border hover:bg-surface-2",
                  )}
                >
                  {c.name}
                </button>
              );
            })}
          </div>
        </div>

        <section className="rounded-xl bg-surface-2 p-4" aria-live="polite">
          <div className="flex items-baseline justify-between gap-2">
            <span className="text-sm font-medium">{t("Who's in it now")}</span>
            <span className="text-2xl font-semibold tracking-tight tabular-nums">
              {preview.data ? preview.data.count.toLocaleString() : "…"}
              <span className="ml-1 text-sm font-normal text-muted">{t("customers")}</span>
            </span>
          </div>
          {preview.error && <ErrorNote error={preview.error} />}
          {preview.data && preview.data.sample.length > 0 && (
            <table className="mt-2 w-full text-sm">
              <tbody>
                {preview.data.sample.map((m) => (
                  <tr key={m.id} className="border-t border-border">
                    <td className="py-1.5">{m.name}</td>
                    <td className="py-1.5 text-right text-muted tabular-nums">{t("{n} orders", { n: m.orders })}</td>
                    <td className="py-1.5 text-right tabular-nums">{money(m.spent, currency)}</td>
                    <td className="hidden py-1.5 text-right text-muted sm:table-cell">{m.lastVisit ? shortDate(m.lastVisit.slice(0, 10)) : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending}>
            {segment ? t("Save") : t("Create segment")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
