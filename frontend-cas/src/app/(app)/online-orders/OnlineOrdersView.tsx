"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bike, MapPin, Phone, ShoppingBag } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";

import { BranchFilter } from "@pos/shared/components/BranchFilter";
import { Badge, Button, Card, ErrorNote, PageHeader, Spinner, cx } from "@pos/shared/components/ui";
import { api } from "@pos/shared/api/client";
import { dateTime, money } from "@pos/shared/utils/format";
import type { OnlineOrder, OnlineStatus } from "@pos/shared/types";
import { msg, useT } from "@pos/shared/i18n";

const TABS = [
  { key: "NEW", label: msg("To prepare") },
  { key: "READY", label: msg("Ready & on the way") },
  { key: "DONE", label: msg("Done") },
  { key: "UNPAID", label: msg("Waiting for payment") },
] as const;

type Tab = (typeof TABS)[number]["key"];

/**
 * Orders from the online shop: paid orders to prepare, then ready to collect or out for delivery,
 * then done. Payment, cancelling and refunds are the order's own, under Sales.
 */
export function OnlineOrdersView() {
  const t = useT();
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const tab = (TABS.find((x) => x.key === params.get("tab"))?.key ?? "NEW") as Tab;
  const [branch, setBranch] = useState("");
  const list = useQuery({
    queryKey: ["online-orders", tab, branch],
    queryFn: () => api<OnlineOrder[]>(`/online-orders?view=${tab}${branch ? `&branchId=${branch}` : ""}`),
    refetchInterval: 20_000,
  });

  return (
    <div className="mx-auto max-w-5xl p-4 lg:p-8">
      <PageHeader title={t("Online orders")} actions={<BranchFilter value={branch} onChange={setBranch} />} />
      <div className="mb-4 flex gap-1 overflow-x-auto border-b border-border" role="tablist" aria-label={t("Online orders")}>
        {TABS.map((x) => (
          <button
            key={x.key}
            role="tab"
            aria-selected={tab === x.key}
            onClick={() => router.replace(`${pathname}?tab=${x.key}`)}
            className={cx(
              "-mb-px shrink-0 border-b-2 px-4 py-2 text-sm font-medium",
              tab === x.key ? "border-primary text-primary" : "border-transparent text-muted hover:text-text",
            )}
          >
            {t(x.label)}
          </button>
        ))}
      </div>
      {list.isPending ? (
        <Spinner />
      ) : list.error ? (
        <ErrorNote error={list.error} />
      ) : list.data.length === 0 ? (
        <p className="rounded-lg border border-dashed border-border p-8 text-center text-sm text-muted">{t("No online orders here.")}</p>
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {list.data.map((o) => (
            <OrderCard key={o.orderId} order={o} />
          ))}
        </div>
      )}
    </div>
  );
}

/** What can happen next to an order, by how it reaches the customer. */
function nextSteps(o: OnlineOrder): { status: OnlineStatus; label: string }[] {
  if (!o.paid || o.status === "DONE") return [];
  if (o.status === "NEW") {
    return o.fulfilment === "PICKUP"
      ? [{ status: "READY", label: msg("Ready to collect") }, { status: "DONE", label: msg("Collected") }]
      : [{ status: "OUT_FOR_DELIVERY", label: msg("Out for delivery") }];
  }
  return [{ status: "DONE", label: o.fulfilment === "PICKUP" ? msg("Collected") : msg("Delivered") }];
}

const STATUS_LABEL: Record<OnlineStatus, string> = {
  NEW: msg("To prepare"),
  READY: msg("Ready to collect"),
  OUT_FOR_DELIVERY: msg("Out for delivery"),
  DONE: msg("Done"),
};

function OrderCard({ order: o }: { order: OnlineOrder }) {
  const t = useT();
  const queryClient = useQueryClient();
  const move = useMutation({
    mutationFn: (status: OnlineStatus) => api<OnlineOrder>(`/online-orders/${o.orderId}/status`, { method: "POST", json: { status } }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["online-orders"] });
    },
  });
  const steps = nextSteps(o);
  return (
    <Card className="flex flex-col gap-3 p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Link href={`/orders/${o.orderId}`} className="font-mono text-sm font-semibold text-primary hover:underline">
          {o.orderNumber}
        </Link>
        <Badge tone={o.fulfilment === "PICKUP" ? "primary" : "warning"}>
          {o.fulfilment === "PICKUP" ? (
            <ShoppingBag className="mr-1 size-3.5" aria-hidden />
          ) : (
            <Bike className="mr-1 size-3.5" aria-hidden />
          )}
          {o.fulfilment === "PICKUP" ? t("Pickup") : t("Delivery")}
        </Badge>
        {o.paid ? <Badge tone="success">{t(STATUS_LABEL[o.status])}</Badge> : <Badge>{t("Waiting for payment")}</Badge>}
        <span className="ml-auto text-xs text-muted">{dateTime(o.createdAt)}</span>
      </div>
      <div className="text-sm">
        <div className="font-medium">{o.contactName}</div>
        <a href={`tel:${o.contactPhone}`} className="inline-flex items-center gap-1 text-muted hover:text-text">
          <Phone className="size-3.5" aria-hidden />
          {o.contactPhone}
        </a>
        {o.address && (
          <div className="mt-1 flex items-start gap-1 text-muted">
            <MapPin className="mt-0.5 size-3.5 shrink-0" aria-hidden />
            <span>{o.address}</span>
          </div>
        )}
        <div className="mt-1 text-xs text-muted">{o.branchName}</div>
      </div>
      <ul className="divide-y divide-border rounded-lg border border-border text-sm">
        {o.items.map((i, n) => (
          <li key={n} className="flex items-baseline gap-2 px-3 py-1.5">
            <span className="w-10 shrink-0 font-semibold tabular-nums">{i.quantity}×</span>
            <span className="min-w-0 flex-1">
              {i.name}
              {i.nameKm && <span lang="km" className="block text-xs text-muted">{i.nameKm}</span>}
            </span>
            {i.unit && <span className="text-xs text-muted">{i.unit}</span>}
          </li>
        ))}
      </ul>
      {o.note && <p className="rounded-lg bg-surface-2 px-3 py-2 text-sm">{o.note}</p>}
      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-auto font-semibold">{money(o.total, o.currency)}</span>
        {steps.map((s, n) => (
          <Button key={s.status} size="sm" variant={n === 0 ? "primary" : "secondary"} loading={move.isPending && move.variables === s.status}
            disabled={move.isPending} onClick={() => move.mutate(s.status)}>
            {t(s.label)}
          </Button>
        ))}
      </div>
      <ErrorNote error={move.error} />
    </Card>
  );
}
