"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Search, Trash2 } from "lucide-react";
import { useState } from "react";

import { Button, ErrorNote, Field, Input, Modal, Select, Spinner } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { qty } from "@/lib/format";
import { hasPermission, useUser } from "@/lib/auth";
import { useBranch } from "@/lib/branch";
import type { Offering, Page, Transfer } from "@/lib/types";
import { translate, useT } from "@/lib/i18n";

interface Line {
  offering: Offering;
  amounts: Record<number, string>;
}

const unitsOf = (o: Offering) => [
  ...[...(o.packs ?? [])].sort((a, b) => b.factor - a.factor).map((p) => ({ id: p.unitId, symbol: p.symbol, factor: p.factor })),
  { id: o.unitId!, symbol: o.unit ?? "pcs", factor: 1 },
];
const lineTotal = (l: Line) => unitsOf(l.offering).reduce((sum, u) => sum + (Number(l.amounts[u.id]) || 0) * u.factor, 0);

/**
 * Send stock from one storage to another. Within a branch it lands at once; to another branch it
 * waits in transit until that branch receives it.
 */
export function NewTransferDialog({ onClose }: { onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const user = useUser();
  const { branches, current } = useBranch();
  const active = branches.filter((b) => b.active).map((b) => ({ ...b, storages: b.storages.filter((s) => s.active) }));
  const sources = active.filter((b) => hasPermission(user, "ALL_BRANCHES") || b.id === user.branchId);
  const defaultFrom = sources.find((b) => b.id === current?.id) ?? sources[0];

  const [fromId, setFromId] = useState<number | null>(defaultFrom?.storages.find((s) => !s.selling)?.id ?? defaultFrom?.storages[0]?.id ?? null);
  const [toId, setToId] = useState<number | null>(null);
  const [note, setNote] = useState("");
  const [search, setSearch] = useState("");
  const [lines, setLines] = useState<Line[]>([]);

  const fromBranch = active.find((b) => b.storages.some((s) => s.id === fromId));
  const toBranch = active.find((b) => b.storages.some((s) => s.id === toId));
  const crossBranch = fromBranch && toBranch && fromBranch.id !== toBranch.id;

  // Stock shown is what's in the source storage.
  const results = useQuery({
    queryKey: ["offerings", "transfer-search", fromId, search.trim()],
    queryFn: () =>
      api<Page<Offering>>(`/offerings${queryString({ q: search.trim(), storageId: fromId, size: 8 })}&type=PRODUCT&type=ITEM`),
    enabled: fromId !== null && search.trim().length > 0,
  });

  const add = async (o: Offering) => {
    if (lines.some((l) => l.offering.id === o.id)) return;
    const detail = await api<Offering>(`/offerings/${o.id}?storageId=${fromId}`);
    setLines((ls) => [...ls, { offering: detail, amounts: {} }]);
    setSearch("");
  };

  const problems = lines.map((l) => {
    const total = lineTotal(l);
    if (total <= 0) return translate("Enter an amount");
    if (total > (l.offering.stock ?? 0)) return translate("Only {n} in this storage", { n: qty(l.offering.stock ?? 0, l.offering.unit) });
    return null;
  });
  const valid = fromId !== null && toId !== null && fromId !== toId && lines.length > 0 && problems.every((p) => p === null);

  const save = useMutation({
    mutationFn: () =>
      api<Transfer>("/transfers", {
        method: "POST",
        json: {
          fromStorageId: fromId,
          toStorageId: toId,
          note: note || null,
          lines: lines.map((l) => ({
            offeringId: l.offering.id,
            quantities: unitsOf(l.offering).map((u) => ({ unitId: u.id, quantity: Number(l.amounts[u.id]) || 0 })),
          })),
        },
      }),
    onSuccess: () => {
      for (const key of ["transfers", "offerings", "movements", "dashboard"]) queryClient.invalidateQueries({ queryKey: [key] });
      onClose();
    },
  });

  return (
    <Modal open onClose={onClose} title={t("New transfer")} wide>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (valid) save.mutate();
        }}
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t("From")}>
            <Select
              value={fromId ?? ""}
              onChange={(e) => {
                setFromId(Number(e.target.value));
                setLines([]); // available stock depends on the source
              }}
            >
              {sources.map((b) => (
                <optgroup key={b.id} label={b.name}>
                  {b.storages.map((s) => (
                    <option key={s.id} value={s.id}>
                      {b.name} / {s.name}
                    </option>
                  ))}
                </optgroup>
              ))}
            </Select>
          </Field>
          <Field
            label={t("To")}
            hint={
              toId === null
                ? undefined
                : crossBranch
                  ? t("In transit until {name} receives it", { name: toBranch?.name })
                  : t("Same branch: lands straight away")
            }
          >
            <Select value={toId ?? ""} onChange={(e) => setToId(e.target.value ? Number(e.target.value) : null)}>
              <option value="">{t("Choose a storage…")}</option>
              {active.map((b) => (
                <optgroup key={b.id} label={b.name}>
                  {b.storages
                    .filter((s) => s.id !== fromId)
                    .map((s) => (
                      <option key={s.id} value={s.id}>
                        {b.name} / {s.name}
                      </option>
                    ))}
                </optgroup>
              ))}
            </Select>
          </Field>
        </div>

        <div>
          <div className="mb-1.5 text-sm font-medium">{t("Items")}</div>
          <div className="relative">
            <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted" aria-hidden />
            <Input
              placeholder={t("Search products and items to add")}
              aria-label={t("Search products and items to add")}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="pl-9"
            />
          </div>
          {search.trim() && (
            <div className="mt-1 rounded-lg border border-border">
              {results.isPending ? (
                <Spinner />
              ) : results.data?.items.length === 0 ? (
                <p className="p-3 text-sm text-muted">{t("Nothing found.")}</p>
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
                </ul>
              )}
            </div>
          )}

          <div className="mt-3 flex flex-col gap-2">
            {lines.length === 0 && <p className="text-sm text-muted">{t("No items yet.")}</p>}
            {lines.map((line, index) => {
              const base = line.offering.unit ?? "pcs";
              return (
                <div key={line.offering.id} className="rounded-lg border border-border px-3 py-2">
                  <div className="mb-2 flex items-center justify-between gap-2 text-sm">
                    <div>
                      <span className="font-medium">{line.offering.name}</span>{" "}
                      <span className="text-xs text-muted">
                        {line.offering.stock ?? 0} {t("{base} in this storage", { base })}
                      </span>
                    </div>
                    <button
                      type="button"
                      onClick={() => setLines((ls) => ls.filter((_, i) => i !== index))}
                      className="rounded-md p-1.5 text-muted hover:bg-danger-soft hover:text-danger"
                      aria-label={t("Remove {name}", { name: line.offering.name })}
                    >
                      <Trash2 className="size-4" />
                    </button>
                  </div>
                  <div className="flex flex-wrap items-center gap-3 text-sm">
                    {unitsOf(line.offering).map((u) => (
                      <label key={u.id} className="flex items-center gap-1.5">
                        <div className="w-20">
                          <Input
                            inputMode="numeric"
                            placeholder="0"
                            aria-label={t("{name} in {symbol}", { name: line.offering.name, symbol: u.symbol })}
                            value={line.amounts[u.id] ?? ""}
                            onChange={(e) =>
                              setLines((ls) =>
                                ls.map((l, i) =>
                                  i === index ? { ...l, amounts: { ...l.amounts, [u.id]: e.target.value.replace(/\D/g, "") } } : l,
                                ),
                              )
                            }
                            className="h-9 text-right tabular-nums"
                          />
                        </div>
                        {u.symbol}
                      </label>
                    ))}
                    <span className="ml-auto text-xs tabular-nums">
                      = {lineTotal(line)} {base}
                    </span>
                  </div>
                  {problems[index] && lineTotal(line) > 0 && <p className="mt-1 text-xs text-danger">{problems[index]}</p>}
                </div>
              );
            })}
          </div>
        </div>

        <Field label={t("Note")}>
          <Input placeholder={t("Optional, e.g. reason or driver")} value={note} onChange={(e) => setNote(e.target.value)} maxLength={255} />
        </Field>

        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button type="submit" disabled={!valid} loading={save.isPending}>
            {crossBranch ? t("Send transfer") : t("Move stock")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
