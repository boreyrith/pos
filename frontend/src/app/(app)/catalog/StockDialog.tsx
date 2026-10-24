"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import { Button, ErrorNote, Field, Input, Modal, Select, Spinner, cx } from "@/components/ui";
import { api } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { useBranch } from "@/lib/branch";
import { useAdjustmentReasons } from "@/lib/queries";
import type { AdjustmentReason, Offering, StockLevel } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

type Operation = "RECEIVE" | "REMOVE" | "COUNT";

const OPERATIONS: { value: Operation; label: string; hint: string }[] = [
  { value: "RECEIVE", label: msg("Add"), hint: msg("Goods arrived, or stock was found. This is added.") },
  { value: "REMOVE", label: msg("Remove"), hint: msg("Damaged, expired, lost or used in-house. This is taken away.") },
  { value: "COUNT", label: msg("Count"), hint: msg("After counting the shelf. The total becomes the stock.") },
];

/**
 * Stock by location, and receive / remove / count in one storage in any of the entry's units.
 * Managers can change their own branch's storages; admins any.
 */
export function StockDialog({ product, onClose }: { product: Offering; onClose: () => void }) {
  const t = useT();
  const detail = useQuery({
    queryKey: ["offerings", "detail", product.id],
    queryFn: () => api<Offering>(`/offerings/${product.id}`),
  });
  const levels = useQuery({
    queryKey: ["offerings", "levels", product.id],
    queryFn: () => api<StockLevel[]>(`/offerings/${product.id}/stock-levels`),
  });

  return (
    <Modal open onClose={onClose} title={t("Stock · {name}", { name: product.name })} wide>
      {detail.isPending || levels.isPending ? (
        <Spinner />
      ) : detail.error || levels.error ? (
        <ErrorNote error={detail.error ?? levels.error} />
      ) : (
        <StockForm offering={detail.data} levels={levels.data} onClose={onClose} />
      )}
    </Modal>
  );
}

function StockForm({ offering, levels, onClose }: { offering: Offering; levels: StockLevel[]; onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const user = useUser();
  const { current, sellingStorage } = useBranch();
  const editable = levels.filter((l) => l.storageActive && (hasPermission(user, "ALL_BRANCHES") || l.branchId === user.branchId));
  const [storageId, setStorageId] = useState<number | null>(
    editable.find((l) => l.storageId === sellingStorage?.id)?.storageId ??
      editable.find((l) => l.branchId === current?.id)?.storageId ??
      editable[0]?.storageId ??
      null,
  );
  const [operation, setOperation] = useState<Operation>("RECEIVE");
  const [amounts, setAmounts] = useState<Record<number, string>>({});
  const [note, setNote] = useState("");
  const [reason, setReason] = useState<AdjustmentReason | "">("RECEIVED");
  const reasons = useAdjustmentReasons();
  const reasonOptions = (reasons.data ?? []).filter(
    (r) => r.code !== "COUNT" && (operation === "RECEIVE" ? r.increase : r.decrease),
  );

  const base = offering.unit ?? "pcs";
  // Largest pack first, base unit last: how people count (3 carton, 2 box, 7 pcs).
  const units = [
    ...[...(offering.packs ?? [])].sort((a, b) => b.factor - a.factor).map((p) => ({ id: p.unitId, symbol: p.symbol, factor: p.factor })),
    { id: offering.unitId!, symbol: base, factor: 1 },
  ];
  const stock = levels.find((l) => l.storageId === storageId)?.quantity ?? 0;
  const total = units.reduce((sum, u) => sum + (Number(amounts[u.id]) || 0) * u.factor, 0);
  const after = operation === "RECEIVE" ? stock + total : operation === "REMOVE" ? stock - total : total;
  const touched = units.some((u) => amounts[u.id] !== undefined && amounts[u.id] !== "");
  const needsReason = operation !== "COUNT";
  const otherNeedsNote = reason === "OTHER" && !note.trim();
  const valid =
    storageId !== null && touched && (operation === "COUNT" ? true : total > 0) && after >= 0 && (!needsReason || (reason !== "" && !otherNeedsNote));
  const grandTotal = levels.reduce((sum, l) => sum + l.quantity, 0);

  const save = useMutation({
    mutationFn: () =>
      api<Offering>(`/offerings/${offering.id}/stock`, {
        method: "POST",
        json: {
          storageId,
          operation,
          note: note || null,
          reason: operation === "COUNT" ? null : reason || null,
          quantities: units.map((u) => ({ unitId: u.id, quantity: Number(amounts[u.id]) || 0 })),
        },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["offerings"] });
      queryClient.invalidateQueries({ queryKey: ["units"] });
      queryClient.invalidateQueries({ queryKey: ["movements"] });
      onClose();
    },
  });

  return (
    <div className="grid gap-6 md:grid-cols-[1fr_1.2fr]">
      <div>
        <h3 className="mb-2 text-sm font-semibold">{t("Stock by location")}</h3>
        <table className="w-full text-sm">
          <tbody className="divide-y divide-border">
            {levels.map((l) => (
              <tr key={l.storageId} className={cx(!l.storageActive && "text-muted")}>
                <td className="py-1.5">
                  <div className="text-xs text-muted">{l.branchName}</div>
                  {l.storageName}
                  {l.selling && <span className="text-xs text-muted">{" "}{t("· selling")}</span>}
                  {!l.storageActive && <span className="text-xs">{" "}{t("· inactive")}</span>}
                </td>
                <td className="py-1.5 text-right tabular-nums">
                  {l.quantity} <span className="text-xs text-muted">{base}</span>
                </td>
              </tr>
            ))}
          </tbody>
          <tfoot className="border-t border-border font-medium">
            <tr>
              <td className="py-1.5">{t("All locations")}</td>
              <td className="py-1.5 text-right tabular-nums">
                {grandTotal} <span className="text-xs text-muted">{base}</span>
              </td>
            </tr>
          </tfoot>
        </table>
        {offering.packs?.length ? (
          <p className="mt-2 text-xs text-muted">{offering.packs.map((p) => `1 ${p.symbol} = ${p.factor} ${base}`).join(" · ")}</p>
        ) : null}
      </div>

      {editable.length === 0 ? (
        <p className="text-sm text-muted">{t("You can only change stock in your own branch's storages.")}</p>
      ) : (
        <form
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (valid) save.mutate();
          }}
        >
          <Field label={t("Storage")}>
            <Select value={storageId ?? ""} onChange={(e) => setStorageId(Number(e.target.value))}>
              {editable.map((l) => (
                <option key={l.storageId} value={l.storageId}>
                  {l.branchName} / {l.storageName} ({l.quantity} {base})
                </option>
              ))}
            </Select>
          </Field>
          <div>
            <div className="grid grid-cols-3 overflow-hidden rounded-lg border border-border text-sm" role="group" aria-label={t("What happened")}>
              {OPERATIONS.map((op) => (
                <button
                  type="button"
                  key={op.value}
                  onClick={() => {
                setOperation(op.value);
                setReason(op.value === "RECEIVE" ? "RECEIVED" : "");
              }}
                  aria-pressed={operation === op.value}
                  className={cx("h-10", operation === op.value ? "bg-primary text-on-primary" : "hover:bg-surface-2")}
                >
                  {t(op.label)}
                </button>
              ))}
            </div>
            <p className="mt-1.5 text-xs text-muted">{t(OPERATIONS.find((o) => o.value === operation)!.hint)}</p>
          </div>

          <div className="flex flex-col gap-2">
            {units.map((u, i) => (
              <label key={u.id} className="flex items-center gap-3 text-sm">
                <div className="w-28 shrink-0">
                  <Input
                    autoFocus={i === 0}
                    inputMode="numeric"
                    placeholder="0"
                    aria-label={t("Quantity in {symbol}", { symbol: u.symbol })}
                    value={amounts[u.id] ?? ""}
                    onChange={(e) => setAmounts((a) => ({ ...a, [u.id]: e.target.value.replace(/\D/g, "") }))}
                    className="text-right tabular-nums"
                  />
                </div>
                <span className="font-medium">{u.symbol}</span>
                {u.factor > 1 && <span className="text-xs text-muted">× {u.factor} {base}</span>}
              </label>
            ))}
          </div>

          {needsReason && (
            <Field label={t("Reason")} error={otherNeedsNote ? t("Add a note for “Other”") : undefined}>
              <Select value={reason} onChange={(e) => setReason(e.target.value as AdjustmentReason)}>
                <option value="">{t("Choose a reason…")}</option>
                {reasonOptions.map((r) => (
                  <option key={r.code} value={r.code}>
                    {t(r.label)}
                  </option>
                ))}
              </Select>
            </Field>
          )}

          <Field label={t("Note")}>
            <Input placeholder={t("Optional, e.g. supplier invoice number")} value={note} onChange={(e) => setNote(e.target.value)} maxLength={255} />
          </Field>

          <div className="rounded-lg bg-surface-2 px-4 py-3 text-sm">
            <div className="flex justify-between">
              <span className="text-muted">{operation === "COUNT" ? t("Counted") : operation === "RECEIVE" ? t("Adding") : t("Removing")}</span>
              <span className="font-medium tabular-nums">
                {total} {base}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-muted">{t("Stock after, in this storage")}</span>
              <span className={cx("font-semibold tabular-nums", after < 0 && "text-danger")}>
                {after} {base}
              </span>
            </div>
            {after < 0 && <p className="mt-1 text-xs text-danger">{t("You can't remove more than is in this storage.")}</p>}
          </div>

          <ErrorNote error={save.error} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={onClose}>
              {t("Cancel")}
            </Button>
            <Button type="submit" disabled={!valid} loading={save.isPending}>
              {operation === "COUNT" ? t("Set stock") : operation === "RECEIVE" ? t("Add to stock") : t("Remove from stock")}
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}
