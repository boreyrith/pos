"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { Search, Trash2 } from "lucide-react";
import { useState } from "react";

import { Button, ErrorNote, Field, Input, Modal, Select } from "@/components/ui";
import { ApiError, api, queryString } from "@/lib/api";
import { money } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { Offering, Page, PurchaseOrder } from "@/lib/types";
import { useT } from "@/lib/i18n";

import { useEditableStorages } from "../inventory/useEditableStorages";
import { useRefreshPurchasing, useVendors } from "./shared";

interface Unit {
  id: number | null;
  symbol: string;
  factor: number;
}

interface Row {
  offeringId: number;
  name: string;
  sku: string;
  units: Unit[];
  unitId: number | null;
  quantity: string;
  unitCost: string;
}

const unitsOf = (o: Offering): Unit[] => [
  { id: o.unitId, symbol: o.unit ?? "pcs", factor: 1 },
  ...[...(o.packs ?? [])].sort((a, b) => a.factor - b.factor).map((p) => ({ id: p.unitId, symbol: p.symbol, factor: p.factor })),
];

/** Drafts a purchase order, or edits a draft. Prices are the vendor's, per the unit chosen. */
export function PurchaseOrderDialog({ order, onClose, onSaved }: { order?: PurchaseOrder; onClose: () => void; onSaved: (po: PurchaseOrder) => void }) {
  const t = useT();
  const store = useStoreSettings();
  const vendors = useVendors();
  const { storages, defaultId } = useEditableStorages();
  const refresh = useRefreshPurchasing();
  const [vendorId, setVendorId] = useState(order ? String(order.vendorId) : "");
  const [storageId, setStorageId] = useState(order ? String(order.storageId) : defaultId ? String(defaultId) : "");
  const [expectedDate, setExpectedDate] = useState(order?.expectedDate ?? "");
  const [vendorRef, setVendorRef] = useState(order?.vendorRef ?? "");
  const [note, setNote] = useState(order?.note ?? "");
  const [rows, setRows] = useState<Row[]>(
    () =>
      order?.lines.map((l) => ({
        offeringId: l.offeringId,
        name: l.name,
        sku: l.sku,
        units: [{ id: l.unitId, symbol: l.unit ?? "pcs", factor: l.factor }],
        unitId: l.unitId,
        quantity: String(l.quantity),
        unitCost: String(l.unitCost),
      })) ?? [],
  );
  const [search, setSearch] = useState("");
  const results = useQuery({
    queryKey: ["offerings", "purchase-search", search.trim()],
    queryFn: () => api<Page<Offering>>(`/offerings${queryString({ q: search.trim(), size: 8 })}&type=PRODUCT&type=ITEM`),
    enabled: search.trim().length > 0,
  });
  const currency = store.data?.currency ?? "USD";

  // A draft being edited only knows each line's chosen unit; load the others from the catalog.
  const details = useQuery({
    queryKey: ["offerings", "purchase-units", order?.id],
    queryFn: () => Promise.all((order?.lines ?? []).map((l) => api<Offering>(`/offerings/${l.offeringId}`))),
    enabled: !!order,
  });
  const unitsFor = (r: Row) => {
    const d = details.data?.find((o) => o.id === r.offeringId);
    return d ? unitsOf(d) : r.units;
  };
  const add = async (o: Offering) => {
    setSearch("");
    if (rows.some((r) => r.offeringId === o.id)) return;
    const detail = await api<Offering>(`/offerings/${o.id}`);
    const units = unitsOf(detail);
    setRows((rs) => [
      ...rs,
      { offeringId: o.id, name: o.name, sku: o.sku, units, unitId: units[0].id, quantity: "1", unitCost: String(detail.cost ?? 0) },
    ]);
  };
  const update = (i: number, change: Partial<Row>) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...change } : r)));
  const total = rows.reduce((n, r) => n + Math.round((Number(r.quantity) || 0) * (Number(r.unitCost) || 0) * 100), 0) / 100;
  const invalid =
    !vendorId || !storageId || rows.length === 0 || rows.some((r) => !(Number(r.quantity) >= 1) || !Number.isInteger(Number(r.quantity)) || !(Number(r.unitCost) >= 0) || r.unitCost === "");

  const save = useMutation({
    mutationFn: () => {
      const body = {
        vendorId: Number(vendorId),
        storageId: Number(storageId),
        expectedDate: expectedDate || null,
        vendorRef: vendorRef.trim() || null,
        note: note.trim() || null,
        lines: rows.map((r) => ({ offeringId: r.offeringId, unitId: r.unitId, quantity: Number(r.quantity), unitCost: Number(r.unitCost) })),
      };
      return order
        ? api<PurchaseOrder>(`/purchase-orders/${order.id}`, { method: "PUT", json: body })
        : api<PurchaseOrder>("/purchase-orders", { method: "POST", json: body });
    },
    onSuccess: (po) => {
      refresh();
      onSaved(po);
    },
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};

  return (
    <Modal open onClose={onClose} title={order ? t("Edit {poNumber}", { poNumber: order.poNumber }) : t("New purchase order")} wide>
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (!invalid) save.mutate();
        }}
      >
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Field label={t("Vendor")} error={errors.vendorId}>
            <Select value={vendorId} required onChange={(e) => setVendorId(e.target.value)}>
              <option value="" disabled>
                {t("Choose a vendor")}
              </option>
              {vendors.data?.filter((v) => v.active || String(v.id) === vendorId).map((v) => (
                <option key={v.id} value={v.id}>
                  {v.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label={t("Deliver to")} error={errors.storageId}>
            <Select value={storageId} required onChange={(e) => setStorageId(e.target.value)}>
              {storages.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.branchName} / {s.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label={t("Expected on")}>
            <Input type="date" value={expectedDate} onChange={(e) => setExpectedDate(e.target.value)} />
          </Field>
          <Field label={t("Vendor reference")} hint={t("Their quote or order number")}>
            <Input maxLength={60} value={vendorRef} onChange={(e) => setVendorRef(e.target.value)} />
          </Field>
        </div>

        <div className="relative">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted" aria-hidden />
          <Input
            placeholder={t("Search products and items to order")}
            aria-label={t("Search products and items to order")}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-9"
          />
          {search.trim() && (
            <ul className="absolute z-10 mt-1 max-h-64 w-full overflow-y-auto rounded-lg border border-border bg-surface shadow-lg">
              {results.data?.items.length === 0 && <li className="px-3 py-2 text-sm text-muted">{t("Nothing found")}</li>}
              {results.data?.items.map((o) => (
                <li key={o.id}>
                  <button type="button" onClick={() => add(o)} className="flex w-full justify-between gap-3 px-3 py-2 text-left text-sm hover:bg-surface-2">
                    <span>
                      {o.name} <span className="font-mono text-xs text-muted">{o.sku}</span>
                    </span>
                    <span className="text-xs text-muted tabular-nums">
                      {t("cost {cost} · {o} in stock", { cost: money(o.cost, currency), o: o.stock ?? 0 })}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        {rows.length === 0 ? (
          <p className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted">{t("Search above to add what you are ordering.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted">
              <tr>
                <th className="py-1 font-medium">{t("Item")}</th>
                <th className="py-1 pl-2 font-medium">{t("Unit")}</th>
                <th className="py-1 pl-2 text-right font-medium">{t("Quantity")}</th>
                <th className="py-1 pl-2 text-right font-medium">{t("Cost per unit")}</th>
                <th className="py-1 pl-2 text-right font-medium">{t("Total")}</th>
                <th aria-label={t("Remove")} />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {rows.map((r, i) => {
                const units = unitsFor(r);
                const unit = units.find((u) => u.id === r.unitId) ?? units[0];
                return (
                  <tr key={r.offeringId}>
                    <td className="py-2">
                      {r.name}
                      <div className="font-mono text-xs text-muted">{r.sku}</div>
                    </td>
                    <td className="py-2 pl-2">
                      <Select
                        aria-label={t("Unit for {name}", { name: r.name })}
                        value={String(r.unitId)}
                        onChange={(e) => update(i, { unitId: e.target.value === "null" ? null : Number(e.target.value) })}
                        className="h-9 text-sm"
                      >
                        {units.map((u) => (
                          <option key={String(u.id)} value={String(u.id)}>
                            {u.symbol}
                            {u.factor > 1 ? ` (${u.factor})` : ""}
                          </option>
                        ))}
                      </Select>
                    </td>
                    <td className="w-24 py-2 pl-2">
                      <Input
                        inputMode="numeric"
                        aria-label={t("Quantity of {name}", { name: r.name })}
                        value={r.quantity}
                        onChange={(e) => update(i, { quantity: e.target.value.replace(/\D/g, "") })}
                        className="h-9 text-right tabular-nums"
                      />
                    </td>
                    <td className="w-32 py-2 pl-2">
                      <Input
                        inputMode="decimal"
                        aria-label={t("Cost per {symbol} of {name}", { symbol: unit.symbol, name: r.name })}
                        value={r.unitCost}
                        onChange={(e) => update(i, { unitCost: e.target.value.replace(/[^\d.]/g, "") })}
                        className="h-9 text-right tabular-nums"
                      />
                    </td>
                    <td className="py-2 pl-2 text-right tabular-nums">{money((Number(r.quantity) || 0) * (Number(r.unitCost) || 0), currency)}</td>
                    <td className="py-2 pl-2 text-right">
                      <button type="button" onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))} aria-label={t("Remove {name}", { name: r.name })} className="rounded p-1 hover:bg-surface-2">
                        <Trash2 className="size-4" />
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
        <Field label={t("Note")}>
          <Input maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
        </Field>
        <ErrorNote error={save.error} />
        <div className="flex items-center justify-between gap-2">
          <span className="text-sm">
            {t("Total")}{" "}<span className="font-semibold tabular-nums">{money(total, currency)}</span>
          </span>
          <div className="flex gap-2">
            <Button type="button" variant="secondary" onClick={onClose}>
              {t("Cancel")}
            </Button>
            <Button type="submit" loading={save.isPending} disabled={invalid}>
              {order ? t("Save draft") : t("Save as draft")}
            </Button>
          </div>
        </div>
      </form>
    </Modal>
  );
}
