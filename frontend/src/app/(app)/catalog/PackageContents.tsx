"use client";

import { useQueries, useQuery } from "@tanstack/react-query";
import { Search, Trash2 } from "lucide-react";
import { useState } from "react";

import { Badge, Input, Select, Spinner } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { TYPE_LABELS, money } from "@/lib/format";
import type { Offering, OfferingType, Page } from "@/lib/types";
import { useT } from "@/lib/i18n";

export interface ContentRow {
  componentId: number;
  name: string;
  type: OfferingType;
  quantity: string;
  /** A base or pack unit for products and items; null for services and packages. */
  unitId: number | null;
}

/** The units a component can be added in: base unit and packs for stocked entries. */
function unitOptions(o: Offering) {
  if (o.type !== "PRODUCT" && o.type !== "ITEM") return [];
  return [
    { id: o.unitId!, symbol: o.unit ?? "pcs", factor: 1 },
    ...(o.packs ?? []).map((p) => ({ id: p.unitId, symbol: p.symbol, factor: p.factor })),
  ];
}

/** Price of a row on its own: component price x quantity (x pack size). */
function rowValue(o: Offering | undefined, row: ContentRow) {
  if (!o) return 0;
  const factor = unitOptions(o).find((u) => u.id === row.unitId)?.factor ?? 1;
  return Math.round(o.price * (Number(row.quantity) || 0) * factor * 100) / 100;
}

/**
 * Edits what a package contains. Anything active can go in (products, items, services, other
 * packages), each with a quantity and, for stocked entries, a unit or pack size.
 */
export function PackageContents({
  packageId,
  rows,
  onChange,
  price,
}: {
  packageId: number | null;
  rows: ContentRow[];
  onChange: (rows: ContentRow[]) => void;
  price: number;
}) {
  const t = useT();
  const [search, setSearch] = useState("");
  const details = useQueries({
    queries: rows.map((r) => ({
      queryKey: ["offerings", "detail", r.componentId],
      queryFn: () => api<Offering>(`/offerings/${r.componentId}`),
      staleTime: 30_000,
    })),
  });
  const results = useQuery({
    queryKey: ["offerings", "package-search", search.trim()],
    queryFn: () => api<Page<Offering>>(`/offerings${queryString({ q: search.trim(), size: 8 })}`),
    enabled: search.trim().length > 0,
  });

  const separate = rows.reduce((sum, r, i) => sum + rowValue(details[i]?.data, r), 0);
  const update = (index: number, change: Partial<ContentRow>) => onChange(rows.map((r, i) => (i === index ? { ...r, ...change } : r)));

  return (
    <div>
      <div className="mb-1.5 text-sm font-medium">{t("Contents")}</div>
      <p className="mb-2 text-xs text-muted">
        {t("Add products, items, services or other packages. Selling the package takes their stock (and the parts their services use).")}
      </p>
      <div className="relative mb-2">
        <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted" aria-hidden />
        <Input placeholder={t("Search the catalog to add")} aria-label={t("Search the catalog to add")} value={search} onChange={(e) => setSearch(e.target.value)} className="pl-9" />
      </div>
      {search.trim() && (
        <div className="mb-2 rounded-lg border border-border">
          {results.isPending ? (
            <Spinner />
          ) : (
            <ul className="divide-y divide-border">
              {(results.data?.items ?? [])
                .filter((o) => o.id !== packageId)
                .map((o) => {
                  const added = rows.some((r) => r.componentId === o.id);
                  return (
                    <li key={o.id}>
                      <button
                        type="button"
                        disabled={added}
                        onClick={() => {
                          onChange([...rows, { componentId: o.id, name: o.name, type: o.type, quantity: "1", unitId: o.type === "PRODUCT" || o.type === "ITEM" ? o.unitId : null }]);
                          setSearch("");
                        }}
                        className="flex w-full items-center justify-between gap-3 px-3 py-2 text-left text-sm hover:bg-surface-2 disabled:opacity-50"
                      >
                        <span>
                          {o.name} <span className="text-xs text-muted">{o.sku}</span>
                        </span>
                        <span className="flex items-center gap-2">
                          <Badge>{t(TYPE_LABELS[o.type])}</Badge>
                          <span className="text-xs text-muted tabular-nums">{money(o.price)}</span>
                        </span>
                      </button>
                    </li>
                  );
                })}
              {results.data?.items.filter((o) => o.id !== packageId).length === 0 && <li className="p-3 text-sm text-muted">{t("Nothing found.")}</li>}
            </ul>
          )}
        </div>
      )}

      <div className="flex flex-col gap-2">
        {rows.length === 0 && <p className="rounded-lg border border-dashed border-border p-4 text-center text-sm text-muted">{t("Nothing in the package yet.")}</p>}
        {rows.map((row, index) => {
          const o = details[index]?.data;
          const options = o ? unitOptions(o) : [];
          const hourly = o?.pricing === "HOURLY";
          const qty = Number(row.quantity);
          const wholeOnly = row.type === "PACKAGE" || (row.type === "SERVICE" && !hourly);
          const invalid = !(qty > 0) || (wholeOnly && !Number.isInteger(qty));
          return (
            <div key={row.componentId} className="flex flex-wrap items-center gap-2 rounded-lg border border-border px-3 py-2 text-sm">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 truncate font-medium">
                  {row.name}
                  <Badge>{t(TYPE_LABELS[row.type])}</Badge>
                  {o && !o.active && <Badge tone="warning">{t("Hidden")}</Badge>}
                </div>
                <div className="text-xs text-muted tabular-nums">
                  {o
                    ? `${money(o.price)}${hourly ? ` / ${t("h")}` : options.length ? ` / ${o.unit}` : ""} · ${t("on its own {value}", { value: money(rowValue(o, row)) })}`
                    : "…"}
                </div>
              </div>
              <div className="w-20 shrink-0">
                <Input
                  aria-label={t("Quantity of {name}", { name: row.name })}
                  inputMode="decimal"
                  value={row.quantity}
                  onChange={(e) => update(index, { quantity: e.target.value.replace(/[^\d.]/g, "") })}
                  invalid={invalid}
                  className="text-right tabular-nums"
                />
              </div>
              {options.length > 0 ? (
                <Select
                  aria-label={t("Unit for {name}", { name: row.name })}
                  value={row.unitId ?? options[0].id}
                  onChange={(e) => update(index, { unitId: Number(e.target.value) })}
                  className="w-28"
                >
                  {options.map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.symbol}
                      {u.factor > 1 ? ` (${u.factor} ${o?.unit})` : ""}
                    </option>
                  ))}
                </Select>
              ) : (
                <span className="w-28 text-xs text-muted">{row.type === "SERVICE" ? (hourly ? t("hours") : t("jobs")) : "×"}</span>
              )}
              <button
                type="button"
                onClick={() => onChange(rows.filter((_, i) => i !== index))}
                className="rounded-md p-1.5 text-muted hover:bg-danger-soft hover:text-danger"
                aria-label={t("Remove {name}", { name: row.name })}
              >
                <Trash2 className="size-4" />
              </button>
            </div>
          );
        })}
      </div>
      {rows.length > 0 && (
        <div className="mt-2 flex flex-wrap justify-between gap-2 rounded-lg bg-surface-2 px-3 py-2 text-sm">
          <span className="text-muted">
            {t("Bought separately:")}{" "}<span className="font-medium text-text tabular-nums">{money(separate)}</span>
          </span>
          {price > 0 && separate > 0 && (
            <span className={price < separate ? "text-success" : "text-muted"}>
              {price < separate
                ? t("Customer saves {separate} ({round}%)", { separate: money(separate - price), round: Math.round(((separate - price) / separate) * 100) })
                : price > separate
                  ? t("Package costs {price} more than buying separately", { price: money(price - separate) })
                  : t("Same as buying separately")}
            </span>
          )}
        </div>
      )}
    </div>
  );
}
