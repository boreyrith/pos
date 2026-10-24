"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";

import { Button, ErrorNote, Field, Input, Modal, Select, Spinner, cx } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { money } from "@/lib/format";
import { useCategories, useUnits } from "@/lib/queries";
import type { CountSheetRow, OfferingType, StockAdjustment } from "@/lib/types";
import { translate, useT } from "@/lib/i18n";

import { useEditableStorages } from "./useEditableStorages";

/**
 * Reconcile a physical count: load what the system has in a storage, type what was counted
 * (in base units), review the differences and apply. Rows left blank aren't changed.
 */
export function StockCountDialog({ onClose }: { onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const categories = useCategories();
  const units = useUnits();
  const { storages, defaultId } = useEditableStorages();
  const [storageId, setStorageId] = useState<number | null>(defaultId);
  const [categoryId, setCategoryId] = useState("");
  const [type, setType] = useState<OfferingType | "">("");
  const [filter, setFilter] = useState("");
  const [counted, setCounted] = useState<Record<number, string>>({});
  const [note, setNote] = useState("");
  const [onlyDiffs, setOnlyDiffs] = useState(false);

  const sheet = useQuery({
    queryKey: ["count-sheet", storageId, categoryId, type],
    queryFn: () => api<CountSheetRow[]>(`/stock-adjustments/count-sheet${queryString({ storageId, categoryId, type })}`),
    enabled: storageId !== null,
    staleTime: Infinity, // keep the numbers the counters are comparing against
    refetchOnWindowFocus: false,
  });
  const unitIdOf = (symbol: string | null) => units.data?.find((u) => u.symbol === (symbol ?? "pcs"))?.id;

  const rows = useMemo(() => {
    const term = filter.trim().toLowerCase();
    return (sheet.data ?? []).filter((r) => {
      if (term && ![r.name, r.nameKm ?? "", r.sku].some((v) => v.toLowerCase().includes(term))) return false;
      if (onlyDiffs) {
        const c = counted[r.offeringId];
        return c !== undefined && c !== "" && Number(c) !== r.systemQty;
      }
      return true;
    });
  }, [sheet.data, filter, onlyDiffs, counted]);

  const entered = (sheet.data ?? []).filter((r) => counted[r.offeringId] !== undefined && counted[r.offeringId] !== "");
  const differing = entered.filter((r) => Number(counted[r.offeringId]) !== r.systemQty);
  const value = differing.reduce((sum, r) => sum + (Number(counted[r.offeringId]) - r.systemQty) * r.unitCost, 0);

  const apply = useMutation({
    mutationFn: () =>
      api<StockAdjustment>("/stock-adjustments", {
        method: "POST",
        json: {
          storageId,
          kind: "COUNT",
          note: note || null,
          lines: entered.map((r) => ({
            offeringId: r.offeringId,
            operation: "SET",
            quantities: [{ unitId: unitIdOf(r.unit), quantity: Number(counted[r.offeringId]) }],
            expected: r.systemQty,
          })),
        },
      }),
    onSuccess: () => {
      for (const key of ["adjustments", "offerings", "movements", "dashboard", "audit", "count-sheet"]) queryClient.invalidateQueries({ queryKey: [key] });
      onClose();
    },
  });

  return (
    <Modal open onClose={onClose} title={t("Stock count")} wide>
      <div className="flex flex-col gap-4">
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label={t("Storage")}>
            <Select value={storageId ?? ""} onChange={(e) => { setStorageId(Number(e.target.value)); setCounted({}); }}>
              {storages.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.branchName} / {s.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label={t("Category")}>
            <Select value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
              <option value="">{t("All categories")}</option>
              {categories.data?.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label={t("Type")}>
            <Select value={type} onChange={(e) => setType(e.target.value as OfferingType | "")}>
              <option value="">{t("Products and items")}</option>
              <option value="PRODUCT">{t("Products")}</option>
              <option value="ITEM">{t("Items")}</option>
            </Select>
          </Field>
        </div>
        <p className="text-xs text-muted">
          {t("Type what you counted, in each entry's base unit. Blank rows are left as they are. Sales during the count are fine: the counted number is applied and the difference from the sheet is recorded.")}
        </p>
        <div className="flex flex-wrap items-center gap-3">
          <Input placeholder={t("Find on the sheet")} aria-label={t("Find on the sheet")} value={filter} onChange={(e) => setFilter(e.target.value)} className="w-56" />
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={onlyDiffs} onChange={(e) => setOnlyDiffs(e.target.checked)} />
            {t("Only differences")}
          </label>
          <Button type="button" variant="ghost" size="sm" onClick={() => sheet.refetch()}>
            {t("Reload sheet")}
          </Button>
        </div>

        <div className="max-h-[45vh] overflow-y-auto rounded-lg border border-border">
          {sheet.isPending ? (
            <Spinner />
          ) : sheet.error ? (
            <div className="p-3">
              <ErrorNote error={sheet.error} />
            </div>
          ) : rows.length === 0 ? (
            <p className="p-6 text-center text-sm text-muted">{t("Nothing to show.")}</p>
          ) : (
            <table className="w-full text-sm">
              <thead className="sticky top-0 bg-surface text-left text-xs text-muted">
                <tr>
                  <th className="px-3 py-2 font-medium">{t("Item")}</th>
                  <th className="px-3 py-2 text-right font-medium">{t("System")}</th>
                  <th className="px-3 py-2 text-right font-medium">{t("Counted")}</th>
                  <th className="px-3 py-2 text-right font-medium">{t("Difference")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {rows.map((r) => {
                  const c = counted[r.offeringId];
                  const diff = c === undefined || c === "" ? null : Number(c) - r.systemQty;
                  return (
                    <tr key={r.offeringId}>
                      <td className="px-3 py-1.5">
                        <div className="font-medium">{r.name}</div>
                        <div className="text-xs text-muted">
                          {r.sku}
                          {r.nameKm && <span lang="km"> · {r.nameKm}</span>}
                        </div>
                      </td>
                      <td className="px-3 py-1.5 text-right tabular-nums">
                        {r.systemQty} <span className="text-xs text-muted">{r.unit}</span>
                      </td>
                      <td className="px-3 py-1.5 text-right">
                        <div className="ml-auto w-24">
                          <Input
                            inputMode="numeric"
                            aria-label={t("Counted {name}", { name: r.name })}
                            value={c ?? ""}
                            onChange={(e) => setCounted((m) => ({ ...m, [r.offeringId]: e.target.value.replace(/\D/g, "") }))}
                            className="h-9 text-right tabular-nums"
                          />
                        </div>
                      </td>
                      <td className={cx("px-3 py-1.5 text-right font-medium tabular-nums", diff === null ? "text-muted" : diff < 0 ? "text-danger" : diff > 0 ? "text-success" : "text-muted")}>
                        {diff === null ? "—" : diff === 0 ? "0" : `${diff > 0 ? "+" : ""}${diff}`}
                        {diff !== null && diff !== 0 && <div className="text-xs font-normal">{money(diff * r.unitCost)}</div>}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-surface-2 px-3 py-2 text-sm">
          <span>
            {t("{n} counted", { n: entered.length })} ·{" "}<span className={differing.length ? "font-medium" : ""}>{t("{n} different", { n: differing.length })}</span>
          </span>
          <span className={cx("tabular-nums", value < 0 ? "text-danger" : value > 0 ? "text-success" : "text-muted")}>
            {t("Value of differences {value}", { value: value > 0 ? "+" : "" })}
            {money(value)}
          </span>
        </div>
        <Field label={t("Note")}>
          <Input value={note} onChange={(e) => setNote(e.target.value)} maxLength={255} placeholder={t("e.g. Monthly count, counted by Dara")} />
        </Field>
        <ErrorNote error={apply.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button
            type="button"
            disabled={entered.length === 0 || apply.isPending}
            loading={apply.isPending}
            onClick={() => {
              if (confirm(translate("Apply the count? {length} of {length2} counted items will change (value {value}).", { length: differing.length, length2: entered.length, value: money(value) }))) apply.mutate();
            }}
          >
            {t("Apply count")}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
