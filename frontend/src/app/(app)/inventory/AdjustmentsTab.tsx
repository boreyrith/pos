"use client";

import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, ClipboardCheck, Plus } from "lucide-react";
import { Fragment, useState } from "react";

import { Badge, Button, Card, ErrorNote, Input, Pagination, Select, Spinner, cx } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { dateTime, money } from "@/lib/format";
import { useAdjustmentReasons, useStoreSettings } from "@/lib/queries";
import type { AdjustmentKind, AdjustmentReason, Page, StockAdjustment } from "@/lib/types";
import { translateServer, useT } from "@/lib/i18n";

import { BranchFilter } from "./BranchFilter";
import { NewAdjustmentDialog } from "./NewAdjustmentDialog";
import { StockCountDialog } from "./StockCountDialog";

/** Applied stock adjustments and counts, newest first, with their lines on demand. */
export function AdjustmentsTab() {
  const t = useT();
  const can = useCan();
  const store = useStoreSettings();
  const reasons = useAdjustmentReasons();
  const [branchId, setBranchId] = useState("");
  const [kind, setKind] = useState<AdjustmentKind | "">("");
  const [reason, setReason] = useState<AdjustmentReason | "">("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<number | null>(null);
  const [dialog, setDialog] = useState<"adjust" | "count" | null>(null);

  const list = useQuery({
    queryKey: ["adjustments", { branchId, kind, reason, from, to, page }],
    queryFn: () => api<Page<StockAdjustment>>(`/stock-adjustments${queryString({ branchId, kind, reason, from, to, page })}`),
    placeholderData: (previous) => previous,
  });
  const detail = useQuery({
    queryKey: ["adjustments", "detail", open],
    queryFn: () => api<StockAdjustment>(`/stock-adjustments/${open}`),
    enabled: open !== null,
  });
  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    setPage(0);
  };

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <BranchFilter value={branchId} onChange={reset(setBranchId)} />
        <Select aria-label={t("Kind")} value={kind} onChange={(e) => reset(setKind)(e.target.value as AdjustmentKind | "")}>
          <option value="">{t("Adjustments and counts")}</option>
          <option value="ADJUSTMENT">{t("Adjustments")}</option>
          <option value="COUNT">{t("Stock counts")}</option>
        </Select>
        <Select aria-label={t("Reason")} value={reason} onChange={(e) => reset(setReason)(e.target.value as AdjustmentReason | "")}>
          <option value="">{t("Any reason")}</option>
          {reasons.data?.map((r) => (
            <option key={r.code} value={r.code}>
              {t(r.label)}
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
        <div className="ml-auto flex gap-2">
          {can("STOCK_COUNT") && (
            <Button variant="secondary" onClick={() => setDialog("count")}>
              <ClipboardCheck className="size-4" aria-hidden />{" "}{t("Stock count")}
            </Button>
          )}
          {can("STOCK_ADJUST") && (
            <Button onClick={() => setDialog("adjust")}>
              <Plus className="size-4" aria-hidden />{" "}{t("New adjustment")}
            </Button>
          )}
        </div>
      </div>

      <Card className="overflow-x-auto">
        {list.isPending ? (
          <Spinner />
        ) : list.error ? (
          <div className="p-4">
            <ErrorNote error={list.error} />
          </div>
        ) : list.data.items.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("No adjustments yet.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="w-8 px-2 py-3" aria-label={t("Expand")} />
                <th className="px-3 py-3 font-medium">{t("Adjustment")}</th>
                <th className="px-3 py-3 font-medium">{t("Where")}</th>
                <th className="px-3 py-3 text-right font-medium">{t("Lines")}</th>
                <th className="px-3 py-3 text-right font-medium">{t("Value")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {list.data.items.map((a) => {
                const expanded = open === a.id;
                return (
                  <Fragment key={a.id}>
                    <tr className="hover:bg-surface-2">
                      <td className="px-2 py-3 align-top">
                        <button
                          onClick={() => setOpen(expanded ? null : a.id)}
                          className="rounded p-1 text-muted hover:bg-surface"
                          aria-label={expanded ? t("Hide lines") : t("Show lines")}
                          aria-expanded={expanded}
                        >
                          {expanded ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
                        </button>
                      </td>
                      <td className="px-3 py-3">
                        <div className="flex items-center gap-2 font-medium">
                          {a.adjustmentNumber}
                          <Badge tone={a.kind === "COUNT" ? "primary" : "neutral"}>{a.kind === "COUNT" ? t("Count") : t("Adjustment")}</Badge>
                        </div>
                        <div className="text-xs text-muted">
                          {dateTime(a.createdAt, store.data?.timezone)} · {a.createdBy}
                          {a.note && ` · ${a.note}`}
                        </div>
                      </td>
                      <td className="px-3 py-3">
                        <div className="text-xs text-muted">{a.place.branchName}</div>
                        {a.place.storageName}
                      </td>
                      <td className="px-3 py-3 text-right tabular-nums">{a.lineCount}</td>
                      <td className={cx("px-3 py-3 text-right font-medium tabular-nums", a.valueChange < 0 ? "text-danger" : a.valueChange > 0 ? "text-success" : "")}>
                        {a.valueChange > 0 ? "+" : ""}
                        {money(a.valueChange)}
                      </td>
                    </tr>
                    {expanded && (
                      <tr className="bg-surface-2">
                        <td />
                        <td colSpan={4} className="px-3 py-3">
                          {detail.isPending ? (
                            <Spinner />
                          ) : (
                            <table className="w-full text-xs">
                              <thead className="text-left text-muted">
                                <tr>
                                  <th className="pb-1 font-medium">{t("Item")}</th>
                                  <th className="pb-1 font-medium">{t("Reason")}</th>
                                  <th className="pb-1 text-right font-medium">{t("Before")}</th>
                                  <th className="pb-1 text-right font-medium">{t("Change||quantity")}</th>
                                  <th className="pb-1 text-right font-medium">{t("After")}</th>
                                  <th className="pb-1 text-right font-medium">{t("Value")}</th>
                                </tr>
                              </thead>
                              <tbody>
                                {detail.data?.lines?.map((l) => (
                                  <tr key={l.offeringId} className="border-t border-border">
                                    <td className="py-1.5">
                                      {l.name} <span className="text-muted">{l.sku}</span>
                                      {l.note && <div className="text-muted">{l.note}</div>}
                                      {l.expectedQty !== null && l.expectedQty !== l.beforeQty && (
                                        <div className="text-warning">
                                          {t("Sheet showed {expectedQty}; stock moved to {beforeQty} while counting", { expectedQty: l.expectedQty, beforeQty: l.beforeQty })}
                                        </div>
                                      )}
                                    </td>
                                    <td className="py-1.5">{translateServer(l.reasonLabel)}</td>
                                    <td className="py-1.5 text-right tabular-nums">{l.beforeQty}</td>
                                    <td className={cx("py-1.5 text-right font-medium tabular-nums", l.changeQty < 0 ? "text-danger" : l.changeQty > 0 ? "text-success" : "text-muted")}>
                                      {l.changeQty > 0 ? "+" : ""}
                                      {l.changeQty}
                                    </td>
                                    <td className="py-1.5 text-right tabular-nums">
                                      {l.afterQty} <span className="text-muted">{l.unit}</span>
                                    </td>
                                    <td className="py-1.5 text-right tabular-nums">{money(l.valueChange)}</td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>
      {list.data && <Pagination page={page} totalPages={list.data.totalPages} onChange={setPage} />}
      {dialog === "adjust" && <NewAdjustmentDialog onClose={() => setDialog(null)} />}
      {dialog === "count" && <StockCountDialog onClose={() => setDialog(null)} />}
    </div>
  );
}
