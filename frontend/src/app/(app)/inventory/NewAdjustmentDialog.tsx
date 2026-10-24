"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Search, Trash2 } from "lucide-react";
import { useState } from "react";

import { Button, ErrorNote, Field, Input, Modal, Select, Spinner, cx } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { qty } from "@/lib/format";
import { useAdjustmentReasons } from "@/lib/queries";
import type { AdjustmentReason, Offering, Page, StockAdjustment } from "@/lib/types";
import { msg, translate, translateServer, useT } from "@/lib/i18n";

import { useEditableStorages } from "./useEditableStorages";

type Operation = "INCREASE" | "DECREASE" | "SET";

interface Line {
  offering: Offering;
  operation: Operation;
  amounts: Record<number, string>;
  reason: AdjustmentReason | "";
  note: string;
}

const OPS: { value: Operation; label: string }[] = [
  { value: "DECREASE", label: msg("Decrease by") },
  { value: "INCREASE", label: msg("Increase by") },
  { value: "SET", label: msg("Set to") },
];

const unitsOf = (o: Offering) => [
  ...[...(o.packs ?? [])].sort((a, b) => b.factor - a.factor).map((p) => ({ id: p.unitId, symbol: p.symbol, factor: p.factor })),
  { id: o.unitId!, symbol: o.unit ?? "pcs", factor: 1 },
];
const amountOf = (l: Line) => unitsOf(l.offering).reduce((sum, u) => sum + (Number(l.amounts[u.id]) || 0) * u.factor, 0);
const touched = (l: Line) => Object.values(l.amounts).some((v) => v !== "");

/** Adjust several products and items in one storage at once, each with a reason. Applies immediately. */
export function NewAdjustmentDialog({ onClose }: { onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const reasons = useAdjustmentReasons();
  const { storages, defaultId } = useEditableStorages();
  const [storageId, setStorageId] = useState<number | null>(defaultId);
  const [note, setNote] = useState("");
  const [search, setSearch] = useState("");
  const [lines, setLines] = useState<Line[]>([]);

  const results = useQuery({
    queryKey: ["offerings", "adjust-search", storageId, search.trim()],
    queryFn: () => api<Page<Offering>>(`/offerings${queryString({ q: search.trim(), storageId, size: 8, activeOnly: false })}&type=PRODUCT&type=ITEM`),
    enabled: storageId !== null && search.trim().length > 0,
  });

  const add = async (o: Offering) => {
    if (lines.some((l) => l.offering.id === o.id)) return;
    const detail = await api<Offering>(`/offerings/${o.id}?storageId=${storageId}`);
    setLines((ls) => [...ls, { offering: detail, operation: "DECREASE", amounts: {}, reason: "", note: "" }]);
    setSearch("");
  };
  const update = (i: number, change: Partial<Line>) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...change } : l)));

  const problems = lines.map((l) => {
    const current = l.offering.stock ?? 0;
    const amount = amountOf(l);
    const after = l.operation === "INCREASE" ? current + amount : l.operation === "DECREASE" ? current - amount : amount;
    const change = after - current;
    const reason = reasons.data?.find((r) => r.code === l.reason);
    if (!touched(l) || (l.operation !== "SET" && amount <= 0)) return { after, message: translate("Enter an amount") };
    if (after < 0) return { after, message: translate("Only {n} in this storage", { n: current }) };
    if (!l.reason) return { after, message: translate("Choose a reason") };
    if (reason && change !== 0 && !(change > 0 ? reason.increase : reason.decrease)) {
      return {
        after,
        message:
          change > 0
            ? translate("“{reason}” doesn't fit an increase", { reason: translateServer(reason.label) })
            : translate("“{reason}” doesn't fit a decrease", { reason: translateServer(reason.label) }),
      };
    }
    if (l.reason === "OTHER" && !l.note.trim() && !note.trim()) return { after, message: translate("Add a note for “Other”") };
    return { after, message: null };
  });
  const valid = storageId !== null && lines.length > 0 && problems.every((p) => p.message === null);

  const save = useMutation({
    mutationFn: () =>
      api<StockAdjustment>("/stock-adjustments", {
        method: "POST",
        json: {
          storageId,
          kind: "ADJUSTMENT",
          note: note || null,
          lines: lines.map((l) => ({
            offeringId: l.offering.id,
            operation: l.operation,
            quantities: unitsOf(l.offering).map((u) => ({ unitId: u.id, quantity: Number(l.amounts[u.id]) || 0 })),
            reason: l.reason,
            note: l.note || null,
          })),
        },
      }),
    onSuccess: () => {
      for (const key of ["adjustments", "offerings", "movements", "dashboard", "audit"]) queryClient.invalidateQueries({ queryKey: [key] });
      onClose();
    },
  });

  return (
    <Modal open onClose={onClose} title={t("New stock adjustment")} wide>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (valid) save.mutate();
        }}
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t("Storage")}>
            <Select
              value={storageId ?? ""}
              onChange={(e) => {
                setStorageId(Number(e.target.value));
                setLines([]); // quantities depend on the storage
              }}
            >
              {storages.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.branchName} / {s.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label={t("Note")} hint={t("Optional, applies to the whole adjustment")}>
            <Input value={note} onChange={(e) => setNote(e.target.value)} maxLength={255} placeholder={t("e.g. Morning shelf check")} />
          </Field>
        </div>

        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted" aria-hidden />
          <Input placeholder={t("Search products and items to adjust")} aria-label={t("Search products and items to adjust")} value={search} onChange={(e) => setSearch(e.target.value)} className="pl-9" />
        </div>
        {search.trim() && (
          <div className="-mt-2 rounded-lg border border-border">
            {results.isPending ? (
              <Spinner />
            ) : (
              <ul className="divide-y divide-border">
                {results.data?.items.map((o) => (
                  <li key={o.id}>
                    <button
                      type="button"
                      onClick={() => add(o)}
                      disabled={lines.some((l) => l.offering.id === o.id)}
                      className="flex w-full items-center justify-between gap-3 px-3 py-2 text-left text-sm hover:bg-surface-2 disabled:opacity-50"
                    >
                      <span>
                        {o.name} <span className="text-xs text-muted">{o.sku}</span>
                      </span>
                      <span className="text-xs text-muted tabular-nums">
                        {t("{qty} here", { qty: qty(o.stock ?? 0, o.unit) })}
                      </span>
                    </button>
                  </li>
                ))}
                {results.data?.items.length === 0 && <li className="p-3 text-sm text-muted">{t("Nothing found.")}</li>}
              </ul>
            )}
          </div>
        )}

        <div className="flex flex-col gap-2">
          {lines.length === 0 && <p className="rounded-lg border border-dashed border-border p-4 text-center text-sm text-muted">{t("Add the items whose stock is wrong.")}</p>}
          {lines.map((l, i) => {
            const base = l.offering.unit ?? "pcs";
            const current = l.offering.stock ?? 0;
            const p = problems[i];
            const change = p.after - current;
            const fitting = (reasons.data ?? []).filter(
              (r) => r.code !== "COUNT" && (l.operation === "SET" ? true : l.operation === "INCREASE" ? r.increase : r.decrease),
            );
            return (
              <div key={l.offering.id} className="rounded-lg border border-border p-3 text-sm">
                <div className="mb-2 flex items-center justify-between gap-2">
                  <div>
                    <span className="font-medium">{l.offering.name}</span>{" "}
                    <span className="text-xs text-muted">
                      {current} {t("{base} now", { base })}
                    </span>
                  </div>
                  <button
                    type="button"
                    onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}
                    className="rounded-md p-1.5 text-muted hover:bg-danger-soft hover:text-danger"
                    aria-label={t("Remove {name}", { name: l.offering.name })}
                  >
                    <Trash2 className="size-4" />
                  </button>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <Select aria-label={t("Change||quantity")} value={l.operation} onChange={(e) => update(i, { operation: e.target.value as Operation, reason: "" })} className="w-36">
                    {OPS.map((o) => (
                      <option key={o.value} value={o.value}>
                        {t(o.label)}
                      </option>
                    ))}
                  </Select>
                  {unitsOf(l.offering).map((u) => (
                    <label key={u.id} className="flex items-center gap-1.5">
                      <div className="w-20">
                        <Input
                          inputMode="numeric"
                          placeholder="0"
                          aria-label={t("{name} in {symbol}", { name: l.offering.name, symbol: u.symbol })}
                          value={l.amounts[u.id] ?? ""}
                          onChange={(e) => update(i, { amounts: { ...l.amounts, [u.id]: e.target.value.replace(/\D/g, "") } })}
                          className="h-9 text-right tabular-nums"
                        />
                      </div>
                      {u.symbol}
                    </label>
                  ))}
                  <Select aria-label={t("Reason")} value={l.reason} onChange={(e) => update(i, { reason: e.target.value as AdjustmentReason })} className="w-40">
                    <option value="">{t("Reason…")}</option>
                    {fitting.map((r) => (
                      <option key={r.code} value={r.code}>
                        {t(r.label)}
                      </option>
                    ))}
                  </Select>
                  <span className={cx("ml-auto text-xs tabular-nums", change < 0 ? "text-danger" : change > 0 ? "text-success" : "text-muted")}>
                    {current} → {Math.max(p.after, 0)} {base}
                  </span>
                </div>
                <Input value={l.note} onChange={(e) => update(i, { note: e.target.value })} maxLength={255} placeholder={t("Line note (optional)")} className="mt-2 h-9" />
                {p.message && touched(l) && <p className="mt-1 text-xs text-danger">{p.message}</p>}
              </div>
            );
          })}
        </div>

        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" disabled={!valid} loading={save.isPending}>
            {t("Apply adjustment")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
