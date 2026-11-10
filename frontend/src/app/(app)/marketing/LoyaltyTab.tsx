"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Coins, PlusCircle } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { CustomerPicker, type PickedCustomer } from "@/components/customers/CustomerPicker";
import { TierBadge } from "@/components/TierBadge";
import { Button, ErrorNote, Field, Input, Modal, Select, Spinner, cx } from "@/components/ui";
import { api } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { dateTime, money } from "@/lib/format";
import { msg, useT } from "@/lib/i18n";
import { useStoreSettings } from "@/lib/queries";
import type { LoyaltyOverview, LoyaltyRules, LoyaltyTransaction, LoyaltyType, Tier } from "@/lib/types";

export const loyaltyKey = ["loyalty"] as const;

export const LOYALTY_TYPES: Record<LoyaltyType, string> = {
  EARN: msg("Earned"),
  REDEEM: msg("Used"),
  RESTORE: msg("Given back"),
  REVERSE: msg("Taken back"),
  ADJUST: msg("Adjusted"),
};

/** Loyalty points: the rules, how the programme is doing, top members and the latest changes. */
export function LoyaltyTab() {
  const t = useT();
  const manage = useCan()("LOYALTY_MANAGE");
  const store = useStoreSettings().data;
  const currency = store?.currency;
  const overview = useQuery({ queryKey: [...loyaltyKey, "overview"], queryFn: () => api<LoyaltyOverview>("/loyalty/overview") });
  const activity = useQuery({ queryKey: [...loyaltyKey, "activity"], queryFn: () => api<LoyaltyTransaction[]>("/loyalty/activity?limit=25") });
  const [adjusting, setAdjusting] = useState(false);

  if (overview.isPending) return <Spinner />;
  if (overview.error) return <ErrorNote error={overview.error} />;
  const o = overview.data;

  return (
    <div className="grid gap-4 lg:grid-cols-3">
      <RulesCard rules={o.rules} manage={manage} currency={currency} />

      <div className="grid content-start gap-3 sm:grid-cols-2 lg:col-span-2">
        <Stat label={t("Members with points")} value={o.members.toLocaleString()} />
        <Stat label={t("Points outstanding")} value={o.outstanding.toLocaleString()} sub={t("worth {amount} if all used", { amount: money(o.liability, currency) })} />
        <Stat label={t("Earned, last 30 days")} value={o.earned30.toLocaleString()} />
        <Stat label={t("Used, last 30 days")} value={o.redeemed30.toLocaleString()} />

        <section className="rounded-[20px] border border-border bg-surface p-5 sm:col-span-2">
          <div className="mb-3 flex items-center justify-between gap-2">
            <h2 className="font-semibold tracking-tight">{t("Top members")}</h2>
            {manage && (
              <Button size="sm" variant="secondary" onClick={() => setAdjusting(true)}>
                <PlusCircle className="size-4" aria-hidden /> {t("Adjust points")}
              </Button>
            )}
          </div>
          {o.top.length === 0 ? (
            <p className="text-sm text-muted">{o.rules.enabled ? t("No one has points yet.") : t("Turn loyalty on to start giving points.")}</p>
          ) : (
            <ul className="-mx-2">
              {o.top.map((m, n) => (
                <li key={m.customerId}>
                  <Link href={`/customers/${m.customerId}`} className="flex items-center gap-3 rounded-xl px-2 py-2 text-sm hover:bg-surface-2">
                    <span className="w-4 text-xs font-semibold text-muted tabular-nums">{n + 1}</span>
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-2">
                        <span className="truncate font-medium">{m.name}</span>
                        <TierBadge tier={m.tier} />
                      </span>
                      <span className="text-xs text-muted">{[m.code, m.phone].filter(Boolean).join(" · ")}</span>
                    </span>
                    <span className="font-medium tabular-nums">{m.points.toLocaleString()}</span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <TiersCard rules={o.rules} counts={o.tierCounts} manage={manage} currency={currency} />

      <section className="rounded-[20px] border border-border bg-surface p-5 lg:col-span-3">
        <h2 className="mb-3 font-semibold tracking-tight">{t("Latest changes")}</h2>
        {activity.isPending ? (
          <Spinner />
        ) : activity.error ? (
          <ErrorNote error={activity.error} />
        ) : activity.data.length === 0 ? (
          <p className="text-sm text-muted">{t("Nothing yet.")}</p>
        ) : (
          <LedgerTable rows={activity.data} showCustomer />
        )}
      </section>

      {adjusting && <AdjustPointsDialog onClose={() => setAdjusting(false)} />}
    </div>
  );
}

/** The rules, with a plain example; editable with "Manage loyalty". */
function RulesCard({ rules, manage, currency }: { rules: LoyaltyRules; manage: boolean; currency: string | undefined }) {
  const t = useT();
  const queryClient = useQueryClient();
  const [enabled, setEnabled] = useState(rules.enabled);
  const [perUnit, setPerUnit] = useState(String(rules.pointsPerUnit));
  const [value, setValue] = useState(String(rules.pointValue));
  const [min, setMin] = useState(String(rules.minRedeem));
  const [pct, setPct] = useState(String(rules.maxRedeemPct));
  const save = useMutation({
    mutationFn: () =>
      api<LoyaltyRules>("/loyalty/rules", {
        method: "PUT",
        // The tier settings are saved from their own card: send them unchanged.
        json: {
          enabled,
          pointsPerUnit: Number(perUnit),
          pointValue: Number(value),
          minRedeem: Number(min),
          maxRedeemPct: Number(pct),
          tierMonths: rules.tierMonths,
        },
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: loyaltyKey }),
  });
  const n = Number(perUnit) || 0;
  const v = Number(value) || 0;
  return (
    <section className="rounded-[20px] border border-border bg-surface p-5">
      <div className="mb-4 flex items-center gap-3">
        <span className={cx("grid size-10 place-items-center rounded-xl", enabled ? "bg-warning-soft text-warning" : "bg-surface-2 text-muted")}>
          <Coins className="size-5" aria-hidden />
        </span>
        <div className="flex-1">
          <h2 className="font-semibold tracking-tight">{t("Loyalty points")}</h2>
          <p className="text-xs text-muted">{enabled ? t("Customers on file earn and use points") : t("Off")}</p>
        </div>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={enabled} disabled={!manage} onChange={(e) => setEnabled(e.target.checked)} />
          {t("On")}
        </label>
      </div>
      <form
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <fieldset disabled={!manage} className="grid grid-cols-2 gap-3">
          <Field label={t("Points per {amount} paid", { amount: money(1, currency) })}>
            <Input type="number" min={0} step="0.0001" value={perUnit} onChange={(e) => setPerUnit(e.target.value)} required />
          </Field>
          <Field label={t("One point is worth")}>
            <Input type="number" min={0.0001} step="0.0001" value={value} onChange={(e) => setValue(e.target.value)} required />
          </Field>
          <Field label={t("Fewest points to use")}>
            <Input type="number" min={1} value={min} onChange={(e) => setMin(e.target.value)} required />
          </Field>
          <Field label={t("Most of a sale (%)")}>
            <Input type="number" min={1} max={100} step="0.01" value={pct} onChange={(e) => setPct(e.target.value)} required />
          </Field>
        </fieldset>
        <p className="rounded-lg bg-surface-2 px-3 py-2 text-sm">
          {t("Spend {spend} → {earn} points · {points} points = {worth}", {
            spend: money(10, currency),
            earn: Math.floor(10 * n),
            points: 100,
            worth: money(Math.round(100 * v * 100) / 100, currency),
          })}
        </p>
        <ErrorNote error={save.error} />
        {manage && (
          <Button type="submit" loading={save.isPending} className="self-start">
            {t("Save rules")}
          </Button>
        )}
      </form>
    </section>
  );
}

/** Bronze to Platinum: what spending reaches each tier, how much faster it earns, and who is in it now. */
function TiersCard({ rules, counts, manage, currency }: { rules: LoyaltyRules; counts: Record<Tier, number>; manage: boolean; currency: string | undefined }) {
  const t = useT();
  const queryClient = useQueryClient();
  const [months, setMonths] = useState(rules.tierMonths == null ? "" : String(rules.tierMonths));
  const [tiers, setTiers] = useState(rules.tiers.map((x) => ({ code: x.code, minSpend: String(x.minSpend), pointsMultiplier: String(x.pointsMultiplier) })));
  const save = useMutation({
    mutationFn: () =>
      api<LoyaltyRules>("/loyalty/rules", {
        method: "PUT",
        json: {
          enabled: rules.enabled,
          pointsPerUnit: rules.pointsPerUnit,
          pointValue: rules.pointValue,
          minRedeem: rules.minRedeem,
          maxRedeemPct: rules.maxRedeemPct,
          tierMonths: months ? Number(months) : null,
          tiers: tiers.map((x) => ({ code: x.code, minSpend: Number(x.minSpend), pointsMultiplier: Number(x.pointsMultiplier) })),
        },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: loyaltyKey });
      queryClient.invalidateQueries({ queryKey: ["customers"] });
    },
  });
  const total = Object.values(counts).reduce((n, x) => n + x, 0);
  const set = (i: number, field: "minSpend" | "pointsMultiplier", v: string) => setTiers((list) => list.map((x, j) => (j === i ? { ...x, [field]: v } : x)));
  return (
    <section className="rounded-[20px] border border-border bg-surface p-5 lg:col-span-3">
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <h2 className="font-semibold tracking-tight">{t("Member tiers")}</h2>
        <p className="text-sm text-muted">{t("Customers move up as they spend, and earn points faster.")}</p>
        <label className="ml-auto flex items-center gap-2 text-sm">
          {t("Spending counted over")}
          <Select value={months} onChange={(e) => setMonths(e.target.value)} disabled={!manage} className="h-9">
            <option value="12">{t("the last 12 months")}</option>
            <option value="6">{t("the last 6 months")}</option>
            <option value="24">{t("the last 24 months")}</option>
            <option value="">{t("all time")}</option>
          </Select>
        </label>
      </div>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {tiers.map((x, i) => {
            const share = total ? Math.round((counts[x.code] / total) * 100) : 0;
            return (
              <div key={x.code} className="rounded-xl border border-border p-4">
                <div className="flex items-center justify-between gap-2">
                  <TierBadge tier={x.code} />
                  <span className="text-sm tabular-nums">
                    <span className="font-semibold">{counts[x.code].toLocaleString()}</span> <span className="text-muted">· {share}%</span>
                  </span>
                </div>
                <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-surface-2" aria-hidden>
                  <div className="h-full rounded-full bg-chart-1" style={{ width: `${share}%` }} />
                </div>
                <fieldset disabled={!manage} className="mt-3 grid grid-cols-2 gap-2">
                  <Field label={t("From spend")}>
                    <Input
                      type="number"
                      min={0}
                      step="0.01"
                      value={x.minSpend}
                      onChange={(e) => set(i, "minSpend", e.target.value)}
                      disabled={x.code === "BRONZE"}
                      required
                    />
                  </Field>
                  <Field label={t("Points ×")}>
                    <Input type="number" min={0.01} max={10} step="0.01" value={x.pointsMultiplier} onChange={(e) => set(i, "pointsMultiplier", e.target.value)} required />
                  </Field>
                </fieldset>
                <p className="mt-2 text-xs text-muted">
                  {x.code === "BRONZE"
                    ? t("Every customer on file")
                    : t("{amount} or more", { amount: money(Number(x.minSpend) || 0, currency) })}
                </p>
              </div>
            );
          })}
        </div>
        <ErrorNote error={save.error} />
        {manage && (
          <Button type="submit" loading={save.isPending} className="mt-4">
            {t("Save tiers")}
          </Button>
        )}
      </form>
    </section>
  );
}

/** Add or take away points for a customer, with a reason; recorded in their history and the audit log. */
export function AdjustPointsDialog({ customer, onClose }: { customer?: PickedCustomer; onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const [who, setWho] = useState<PickedCustomer | null>(customer ?? null);
  const [points, setPoints] = useState("");
  const [note, setNote] = useState("");
  const save = useMutation({
    mutationFn: () => api(`/loyalty/customers/${who!.id}/adjust`, { method: "POST", json: { points: Number(points), note: note.trim() } }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: loyaltyKey });
      queryClient.invalidateQueries({ queryKey: ["customers"] });
      onClose();
    },
  });
  return (
    <Modal open onClose={onClose} title={t("Adjust points")}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        {!customer && <CustomerPicker label={t("Customer")} value={who} onChange={setWho} />}
        <Field label={t("Points")} hint={t("A minus number takes points away")}>
          <Input type="number" step={1} value={points} onChange={(e) => setPoints(e.target.value)} required />
        </Field>
        <Field label={t("Reason")}>
          <Input value={note} onChange={(e) => setNote(e.target.value)} minLength={3} maxLength={255} required placeholder={t("e.g. Birthday gift")} />
        </Field>
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" loading={save.isPending} disabled={!who || !Number(points)}>
            {t("Save")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/** Points changes: when, what, how many, the balance after, and the sale they belong to. */
export function LedgerTable({ rows, showCustomer }: { rows: LoyaltyTransaction[]; showCustomer?: boolean }) {
  const t = useT();
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="text-left text-xs text-muted">
          <tr>
            <th className="py-2 font-medium">{t("When")}</th>
            {showCustomer && <th className="py-2 font-medium">{t("Customer")}</th>}
            <th className="py-2 font-medium">{t("What")}</th>
            <th className="py-2 text-right font-medium">{t("Points")}</th>
            <th className="py-2 text-right font-medium">{t("Balance")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id} className="border-t border-border">
              <td className="py-2 whitespace-nowrap text-muted">{dateTime(r.createdAt)}</td>
              {showCustomer && (
                <td className="py-2">
                  <Link href={`/customers/${r.customerId}`} className="text-primary hover:underline">
                    {r.customerName}
                  </Link>
                </td>
              )}
              <td className="py-2">
                <span className="font-medium">{t(LOYALTY_TYPES[r.type])}</span>
                {r.orderId ? (
                  <Link href={`/orders/${r.orderId}`} className="ml-2 text-xs text-primary hover:underline">
                    {r.note}
                  </Link>
                ) : (
                  r.note && <span className="ml-2 text-xs text-muted">{r.note}</span>
                )}
                {r.createdBy && <span className="ml-2 text-xs text-muted">· {r.createdBy}</span>}
              </td>
              <td className={cx("py-2 text-right font-medium tabular-nums", r.points > 0 ? "text-success" : "text-danger")}>
                {r.points > 0 ? `+${r.points.toLocaleString()}` : r.points.toLocaleString()}
              </td>
              <td className="py-2 text-right tabular-nums">{r.balanceAfter.toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Stat({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="rounded-[20px] border border-border bg-surface p-4">
      <div className="text-sm text-muted">{label}</div>
      <div className="mt-1 text-2xl font-semibold tracking-tight tabular-nums">{value}</div>
      {sub && <div className="mt-0.5 text-xs text-muted">{sub}</div>}
    </div>
  );
}
