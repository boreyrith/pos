"use client";

import { useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { Badge, Button, Card, ErrorNote, Input, Pagination, Select, Spinner } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { dateTime, money, shortDate } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { Page, PurchaseOrderSummary, PurchaseStatus } from "@/lib/types";
import { useT } from "@/lib/i18n";

import { BranchFilter } from "../inventory/BranchFilter";
import { PurchaseOrderDialog } from "./PurchaseOrderDialog";
import { PO_STATUS, useVendors } from "./shared";

export function OrdersTab() {
  const t = useT();
  const can = useCan();
  const router = useRouter();
  const store = useStoreSettings();
  const vendors = useVendors();
  const [status, setStatus] = useState<PurchaseStatus | "">("");
  const [vendorId, setVendorId] = useState("");
  const [branchId, setBranchId] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const orders = useQuery({
    queryKey: ["purchase-orders", { status, vendorId, branchId, q, page }],
    queryFn: () => api<Page<PurchaseOrderSummary>>(`/purchase-orders${queryString({ status, vendorId, branchId, q: q.trim(), page })}`),
    placeholderData: (previous) => previous,
  });
  const reset = <T,>(setter: (v: T) => void) => (v: T) => {
    setter(v);
    setPage(0);
  };
  const currency = store.data?.currency ?? "USD";

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Input placeholder={t("PO number, vendor or reference")} aria-label={t("Search orders")} value={q} onChange={(e) => reset(setQ)(e.target.value)} className="w-64" />
        <Select aria-label={t("Status")} value={status} onChange={(e) => reset(setStatus)(e.target.value as PurchaseStatus | "")}>
          <option value="">{t("Any status")}</option>
          {Object.entries(PO_STATUS).map(([k, v]) => (
            <option key={k} value={k}>
              {t(v.label)}
            </option>
          ))}
        </Select>
        <Select aria-label={t("Vendor")} value={vendorId} onChange={(e) => reset(setVendorId)(e.target.value)}>
          <option value="">{t("All vendors")}</option>
          {vendors.data?.map((v) => (
            <option key={v.id} value={v.id}>
              {v.name}
            </option>
          ))}
        </Select>
        <BranchFilter value={branchId} onChange={reset(setBranchId)} />
        {can("PURCHASE_ORDER") && (
          <Button className="ml-auto" onClick={() => setCreating(true)}>
            <Plus className="size-4" aria-hidden />{" "}{t("New purchase order")}
          </Button>
        )}
      </div>
      <Card className="overflow-x-auto">
        {orders.isPending ? (
          <Spinner />
        ) : orders.error ? (
          <div className="p-4">
            <ErrorNote error={orders.error} />
          </div>
        ) : orders.data.items.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("No purchase orders match.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="px-4 py-3 font-medium">{t("Order")}</th>
                <th className="px-4 py-3 font-medium">{t("Vendor")}</th>
                <th className="px-4 py-3 font-medium">{t("Status")}</th>
                <th className="px-4 py-3 font-medium">{t("Expected")}</th>
                <th className="px-4 py-3 text-right font-medium">{t("Total")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {orders.data.items.map((o) => {
                const s = PO_STATUS[o.status];
                return (
                  <tr key={o.id} className="hover:bg-surface-2">
                    <td className="px-4 py-3">
                      <Link href={`/purchasing/orders/${o.id}`} className="font-medium text-primary hover:underline">
                        {o.poNumber}
                      </Link>
                      <div className="text-xs text-muted">
                        {dateTime(o.createdAt, store.data?.timezone)} · {o.branchName}
                      </div>
                    </td>
                    <td className="px-4 py-3">{o.vendorName}</td>
                    <td className="px-4 py-3">
                      <Badge tone={s.tone}>{t(s.label)}</Badge>
                      {o.status === "PARTIALLY_RECEIVED" && (
                        <span className="ml-1 text-xs text-muted">
                          {t("{linesOpen} of {lines} lines to come", { linesOpen: o.linesOpen, lines: o.lines })}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-muted">{o.expectedDate ? shortDate(o.expectedDate) : "–"}</td>
                    <td className="px-4 py-3 text-right tabular-nums">{money(o.total, currency)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>
      {orders.data && <Pagination page={page} totalPages={orders.data.totalPages} onChange={setPage} />}
      {creating && <PurchaseOrderDialog onClose={() => setCreating(false)} onSaved={(po) => router.push(`/purchasing/orders/${po.id}`)} />}
    </div>
  );
}
