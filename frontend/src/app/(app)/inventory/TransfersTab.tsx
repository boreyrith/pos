"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, ChevronDown, ChevronRight, Plus } from "lucide-react";
import { Fragment, useState } from "react";

import { Badge, Button, Card, ErrorNote, Pagination, Select, Spinner } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { dateTime } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { Page, Transfer, TransferStatus } from "@/lib/types";
import { msg, translate, useT } from "@/lib/i18n";

import { BranchFilter } from "./BranchFilter";
import { NewTransferDialog } from "./NewTransferDialog";

const STATUS: Record<TransferStatus, { label: string; tone: "warning" | "success" | "neutral" }> = {
  IN_TRANSIT: { label: msg("In transit"), tone: "warning" },
  RECEIVED: { label: msg("Received"), tone: "success" },
  CANCELLED: { label: msg("Cancelled"), tone: "neutral" },
};

export function TransfersTab() {
  const t = useT();
  const user = useUser();
  const store = useStoreSettings();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<TransferStatus | "">("");
  const [branchId, setBranchId] = useState("");
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);

  const transfers = useQuery({
    queryKey: ["transfers", { status, branchId, page }],
    queryFn: () => api<Page<Transfer>>(`/transfers${queryString({ status, branchId, page })}`),
    placeholderData: (previous) => previous,
  });

  const act = useMutation({
    mutationFn: ({ id, action }: { id: number; action: "receive" | "cancel" }) =>
      api<Transfer>(`/transfers/${id}/${action}`, { method: "POST" }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["transfers"] });
      queryClient.invalidateQueries({ queryKey: ["offerings"] });
      queryClient.invalidateQueries({ queryKey: ["movements"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard"] });
    },
  });
  const movesStock = hasPermission(user, "STOCK_TRANSFER");
  const mine = (branch: number) => movesStock && (hasPermission(user, "ALL_BRANCHES") || user.branchId === branch);

  return (
    <div>
      <div className="flex flex-wrap items-center justify-end gap-2">
          <>
            <BranchFilter
              value={branchId}
              onChange={(v) => {
                setBranchId(v);
                setPage(0);
              }}
            />
            <Select
              aria-label={t("Status")}
              value={status}
              onChange={(e) => {
                setStatus(e.target.value as TransferStatus | "");
                setPage(0);
              }}
            >
              <option value="">{t("All statuses")}</option>
              <option value="IN_TRANSIT">{t("In transit")}</option>
              <option value="RECEIVED">{t("Received")}</option>
              <option value="CANCELLED">{t("Cancelled")}</option>
            </Select>
            {movesStock && (
              <Button onClick={() => setCreating(true)}>
                <Plus className="size-4" aria-hidden />{" "}{t("New transfer")}
              </Button>
            )}
          </>
      </div>
      <ErrorNote error={act.error} />
      <Card className="mt-3 overflow-x-auto">
        {transfers.isPending ? (
          <Spinner />
        ) : transfers.error ? (
          <div className="p-4">
            <ErrorNote error={transfers.error} />
          </div>
        ) : transfers.data.items.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("No transfers yet.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="w-8 px-2 py-3" aria-label={t("Expand")} />
                <th className="px-3 py-3 font-medium">{t("Transfer")}</th>
                <th className="px-3 py-3 font-medium">{t("From → to")}</th>
                <th className="px-3 py-3 font-medium">{t("Status")}</th>
                <th className="px-3 py-3 text-right font-medium" aria-label={t("Actions")} />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {transfers.data.items.map((x) => {
                const open = expanded === x.id;
                return (
                  <Fragment key={x.id}>
                    <tr className="hover:bg-surface-2">
                      <td className="px-2 py-3">
                        <button
                          onClick={() => setExpanded(open ? null : x.id)}
                          className="rounded p-1 text-muted hover:bg-surface"
                          aria-label={open ? t("Hide items") : t("Show items")}
                          aria-expanded={open}
                        >
                          {open ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
                        </button>
                      </td>
                      <td className="px-3 py-3">
                        <div className="font-medium">{x.transferNumber}</div>
                        <div className="text-xs text-muted">
                          {`${dateTime(x.createdAt, store.data?.timezone)} · ${x.createdBy} · ${x.lines.length === 1 ? t("1 item") : t("{n} items", { n: x.lines.length })}`}
                        </div>
                      </td>
                      <td className="px-3 py-3">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <span>
                            {x.from.branchName} / {x.from.storageName}
                          </span>
                          <ArrowRight className="size-3.5 text-muted" aria-label={t("to")} />
                          <span>
                            {x.to.branchName} / {x.to.storageName}
                          </span>
                        </div>
                        {x.note && <div className="text-xs text-muted">{x.note}</div>}
                      </td>
                      <td className="px-3 py-3">
                        <Badge tone={STATUS[x.status].tone}>{t(STATUS[x.status].label)}</Badge>
                        {x.closedBy && (
                          <div className="mt-1 text-xs text-muted">
                            {x.closedBy} · {x.closedAt && dateTime(x.closedAt, store.data?.timezone)}
                          </div>
                        )}
                      </td>
                      <td className="px-3 py-3 text-right whitespace-nowrap">
                        {x.status === "IN_TRANSIT" && mine(x.to.branchId) && (
                          <Button size="sm" onClick={() => act.mutate({ id: x.id, action: "receive" })} loading={act.isPending}>
                            {t("Receive")}
                          </Button>
                        )}
                        {x.status === "IN_TRANSIT" && mine(x.from.branchId) && (
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => {
                              if (confirm(translate("Cancel {transferNumber}? The stock goes back to {storageName}.", { transferNumber: x.transferNumber, storageName: x.from.storageName })))
                                act.mutate({ id: x.id, action: "cancel" });
                            }}
                          >
                            {t("Cancel")}
                          </Button>
                        )}
                      </td>
                    </tr>
                    {open && (
                      <tr className="bg-surface-2">
                        <td />
                        <td colSpan={4} className="px-3 py-2">
                          <ul className="flex flex-col gap-1 text-sm">
                            {x.lines.map((l) => (
                              <li key={l.offeringId} className="flex justify-between gap-4">
                                <span>
                                  {l.name} <span className="text-xs text-muted">{l.sku}</span>
                                </span>
                                <span className="tabular-nums">
                                  {l.quantity} {l.unit ?? ""}
                                </span>
                              </li>
                            ))}
                          </ul>
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
      {transfers.data && <Pagination page={page} totalPages={transfers.data.totalPages} onChange={setPage} />}
      {creating && <NewTransferDialog onClose={() => setCreating(false)} />}
    </div>
  );
}
