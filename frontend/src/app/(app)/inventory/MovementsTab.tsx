"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";

import { Card, ErrorNote, Input, Pagination, Select, Spinner, cx } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { useBranch } from "@/lib/branch";
import { MOVEMENT_LABELS, dateTime } from "@/lib/format";
import { useAdjustmentReasons, useStoreSettings } from "@/lib/queries";
import type { MovementType, Page, StockMovement } from "@/lib/types";
import { translateServer, useT } from "@/lib/i18n";

import { BranchFilter } from "./BranchFilter";

export function MovementsTab() {
  const t = useT();
  const user = useUser();
  const store = useStoreSettings();
  const { branches } = useBranch();
  const [branchId, setBranchId] = useState("");
  const [storageId, setStorageId] = useState("");
  const [type, setType] = useState<MovementType | "">("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);
  const reasonLabels = Object.fromEntries((useAdjustmentReasons().data ?? []).map((r) => [r.code, r.label]));

  const visibleBranches = branches.filter((b) =>
    hasPermission(user, "ALL_BRANCHES") ? !branchId || String(b.id) === branchId : b.id === user.branchId,
  );

  const movements = useQuery({
    queryKey: ["movements", { branchId, storageId, type, from, to, page }],
    queryFn: () => api<Page<StockMovement>>(`/stock-movements${queryString({ branchId, storageId, type, from, to, page })}`),
    placeholderData: (previous) => previous,
  });
  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    setPage(0);
  };

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <BranchFilter
          value={branchId}
          onChange={(v) => {
            setBranchId(v);
            setStorageId("");
            setPage(0);
          }}
        />
        <Select aria-label={t("Storage")} value={storageId} onChange={(e) => reset(setStorageId)(e.target.value)}>
          <option value="">{t("All storages")}</option>
          {visibleBranches.map((b) => (
            <optgroup key={b.id} label={b.name}>
              {b.storages.map((s) => (
                <option key={s.id} value={s.id}>
                  {b.name} / {s.name}
                </option>
              ))}
            </optgroup>
          ))}
        </Select>
        <Select aria-label={t("Type")} value={type} onChange={(e) => reset(setType)(e.target.value as MovementType | "")}>
          <option value="">{t("All changes")}</option>
          {Object.entries(MOVEMENT_LABELS).map(([k, label]) => (
            <option key={k} value={k}>
              {label}
            </option>
          ))}
        </Select>
        <label className="flex items-center gap-2 text-sm text-muted">
          {t("From")}
          <Input type="date" value={from} onChange={(e) => reset(setFrom)(e.target.value)} className="w-40" />
        </label>
        <label className="flex items-center gap-2 text-sm text-muted">
          {t("To")}
          <Input type="date" value={to} onChange={(e) => reset(setTo)(e.target.value)} className="w-40" />
        </label>
      </div>

      <Card className="overflow-x-auto">
        {movements.isPending ? (
          <Spinner />
        ) : movements.error ? (
          <div className="p-4">
            <ErrorNote error={movements.error} />
          </div>
        ) : movements.data.items.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("No stock changes match these filters.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="px-4 py-3 font-medium">{t("When")}</th>
                <th className="px-4 py-3 font-medium">{t("Entry")}</th>
                <th className="px-4 py-3 font-medium">{t("Where")}</th>
                <th className="px-4 py-3 font-medium">{t("What")}</th>
                <th className="px-4 py-3 text-right font-medium">{t("Change||quantity")}</th>
                <th className="px-4 py-3 text-right font-medium">{t("Balance")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {movements.data.items.map((m) => (
                <tr key={m.id}>
                  <td className="px-4 py-2.5 whitespace-nowrap text-muted">
                    {dateTime(m.createdAt, store.data?.timezone)}
                    <div className="text-xs">{m.userName ?? t("System")}</div>
                  </td>
                  <td className="px-4 py-2.5">
                    <div className="font-medium">{m.offeringName}</div>
                    <div className="text-xs text-muted">{m.sku}</div>
                  </td>
                  <td className="px-4 py-2.5">
                    <div className="text-xs text-muted">{m.place.branchName}</div>
                    {m.place.storageName}
                  </td>
                  <td className="px-4 py-2.5">
                    {t(MOVEMENT_LABELS[m.type])}
                    {m.reason && m.reason !== "COUNT" && <span className="text-muted"> · {translateServer(reasonLabels[m.reason] ?? m.reason)}</span>}
                    {m.adjustmentNumber && <div className="text-xs text-muted">{m.adjustmentNumber}</div>}
                    {m.receiptNumber && m.purchaseOrderId && (
                      <Link href={`/purchasing/orders/${m.purchaseOrderId}`} className="text-xs text-primary hover:underline">
                        {m.receiptNumber}
                      </Link>
                    )}
                    {m.orderId && (
                      <Link href={`/orders/${m.orderId}`} className="block text-xs text-primary hover:underline">
                        {t("Order #{orderId}", { orderId: m.orderId })}
                      </Link>
                    )}
                    {m.transferId && <div className="text-xs text-muted">{t("Transfer TR-{padStart}", { padStart: String(m.transferId).padStart(5, "0") })}</div>}
                    {m.note && <div className="text-xs text-muted">{m.note}</div>}
                  </td>
                  <td className={cx("px-4 py-2.5 text-right font-medium tabular-nums", m.quantity < 0 ? "text-danger" : "text-success")}>
                    {m.quantity > 0 ? "+" : ""}
                    {m.quantity}
                  </td>
                  <td className="px-4 py-2.5 text-right tabular-nums">
                    {m.balance} <span className="text-xs text-muted">{m.unit ?? ""}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      {movements.data && <Pagination page={page} totalPages={movements.data.totalPages} onChange={setPage} />}
    </div>
  );
}
