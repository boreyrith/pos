"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { PageLink } from "@pos/shared/components/PageLink";
import { useState } from "react";

import { CustomerDialog } from "@pos/shared/components/customers/CustomerDialog";
import { StatusBadge } from "@pos/shared/components/StatusBadge";
import { TierBadge } from "@pos/shared/components/TierBadge";
import { Badge, Button, Card, ErrorNote, Pagination, Spinner } from "@pos/shared/components/ui";
import { api, queryString } from "@pos/shared/api/client";
import { useCan } from "@pos/shared/auth/auth";
import { dateTime, money } from "@pos/shared/utils/format";
import { useStoreSettings } from "@pos/shared/api/queries";
import type { CustomerDetail, CustomerOnlineAccount, LoyaltyRules, LoyaltyTransaction, OrderSummary, Page } from "@pos/shared/types";
import { translate, useT } from "@pos/shared/i18n";

import { AdjustPointsDialog, LedgerTable } from "@/app/(app)/marketing/LoyaltyTab";

/** A customer's details, what they have spent, and their orders. */
export function CustomerView({ id }: { id: number }) {
  const t = useT();
  const can = useCan();
  const store = useStoreSettings();
  const [editing, setEditing] = useState(false);
  const [page, setPage] = useState(0);
  const detail = useQuery({ queryKey: ["customers", id], queryFn: () => api<CustomerDetail>(`/customers/${id}`) });
  const orders = useQuery({
    queryKey: ["orders", { customerId: id, page }],
    queryFn: () => api<Page<OrderSummary>>(`/orders${queryString({ customerId: id, page })}`),
    enabled: can("ORDERS_VIEW"),
    placeholderData: (previous) => previous,
  });

  if (detail.isPending || !store.data) return <Spinner />;
  if (detail.error) return <div className="p-8"><ErrorNote error={detail.error} /></div>;
  const { customer: c, stats } = detail.data;
  const currency = store.data.currency;
  const tz = store.data.timezone;

  return (
    <div className="mx-auto max-w-5xl p-4 lg:p-8">
      <PageLink href="/customers" className="mb-4 inline-flex items-center gap-1 text-sm text-muted hover:text-text">
        <ArrowLeft className="size-4" aria-hidden />{" "}{t("Customers")}
      </PageLink>
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-semibold">{c.name}</h1>
        <TierBadge tier={c.tier} />
        <span className="font-mono text-sm text-muted">{c.code}</span>
        {c.walkIn && <Badge tone="primary">{t("Default for walk-in sales")}</Badge>}
        {!c.active && <Badge>{t("Inactive")}</Badge>}
        {can("CUSTOMERS_MANAGE") && (
          <Button variant="secondary" className="ml-auto" onClick={() => setEditing(true)}>
            {t("Edit")}
          </Button>
        )}
      </div>

      <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label={t("Orders")} value={String(stats.orders)} />
        <Stat label={t("Spent (after refunds)")} value={money(stats.spent, currency)} />
        <Stat label={t("Refunded")} value={money(stats.refunded, currency)} />
        <Stat label={t("Last visit")} value={stats.lastVisit ? dateTime(stats.lastVisit, tz) : "–"} />
      </div>

      <div className="grid gap-5 lg:grid-cols-[1fr_280px]">
        <Card className="overflow-x-auto">
          <h2 className="px-4 pt-4 text-sm font-semibold">{t("Orders")}</h2>
          {!can("ORDERS_VIEW") ? (
            <p className="p-4 text-sm text-muted">{t("Your role can't see orders.")}</p>
          ) : orders.isPending ? (
            <Spinner />
          ) : orders.data && orders.data.items.length > 0 ? (
            <table className="mt-2 w-full text-sm">
              <thead className="border-b border-border text-left text-xs text-muted">
                <tr>
                  <th className="px-4 py-2 font-medium">{t("Order")}</th>
                  <th className="px-4 py-2 font-medium">{t("Date")}</th>
                  <th className="px-4 py-2 font-medium">{t("Status")}</th>
                  <th className="px-4 py-2 text-right font-medium">{t("Total")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {orders.data.items.map((o) => (
                  <tr key={o.id}>
                    <td className="px-4 py-2">
                      <PageLink href={`/orders/${o.id}`} className="text-primary hover:underline">
                        {o.orderNumber}
                      </PageLink>
                      {c.walkIn && o.customerName && <div className="text-xs text-muted">{o.customerName}</div>}
                    </td>
                    <td className="px-4 py-2 text-muted">
                      {dateTime(o.createdAt, tz)} · {o.branchName}
                    </td>
                    <td className="px-4 py-2">
                      <StatusBadge status={o.status} />
                    </td>
                    <td className="px-4 py-2 text-right tabular-nums">{money(o.total, o.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className="p-4 text-sm text-muted">{t("No orders yet.")}</p>
          )}
          {orders.data && orders.data.totalPages > 1 && (
            <div className="px-4 pb-3">
              <Pagination page={page} totalPages={orders.data.totalPages} onChange={setPage} />
            </div>
          )}
        </Card>

        <div className="flex h-fit flex-col gap-5">
        <dl className="grid h-fit grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted">{t("Phone")}</dt>
          <dd className="tabular-nums">{c.phone ?? "–"}</dd>
          <dt className="text-muted">{t("Email")}</dt>
          <dd className="break-all">{c.email ?? "–"}</dd>
          <dt className="text-muted">{t("Address")}</dt>
          <dd>{c.address ?? "–"}</dd>
          <dt className="text-muted">{t("VAT TIN")}</dt>
          <dd>{c.taxId ?? "–"}</dd>
          {c.note && (
            <>
              <dt className="text-muted">{t("Note")}</dt>
              <dd>{c.note}</dd>
            </>
          )}
          <dt className="text-muted">{t("First visit")}</dt>
          <dd>{stats.firstVisit ? dateTime(stats.firstVisit, tz) : "–"}</dd>
          <dt className="text-muted">{t("Added")}</dt>
          <dd>{dateTime(c.createdAt, tz)}</dd>
        </dl>
        {!c.walkIn && <OnlineAccountSection customerId={c.id} tz={tz} />}
        </div>
      </div>
      {!c.walkIn && <PointsSection customerId={c.id} name={c.name} balance={c.pointsBalance} currency={currency} />}
      {editing && <CustomerDialog customer={c} onClose={() => setEditing(false)} />}
    </div>
  );
}

/** The customer's loyalty points: balance, every change, and adjusting them ("Manage loyalty"). */
function PointsSection({ customerId, name, balance, currency }: { customerId: number; name: string; balance: number; currency: string }) {
  const t = useT();
  const can = useCan();
  const [adjusting, setAdjusting] = useState(false);
  const rules = useQuery({ queryKey: ["loyalty", "rules"], queryFn: () => api<LoyaltyRules>("/loyalty/rules") });
  const history = useQuery({
    queryKey: ["loyalty", "history", customerId],
    queryFn: () => api<LoyaltyTransaction[]>(`/loyalty/customers/${customerId}/history`),
  });
  if (!rules.data?.enabled && balance === 0 && !history.data?.length) return null;
  return (
    <Card className="mt-5 p-4">
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <h2 className="text-sm font-semibold">{t("Loyalty points")}</h2>
        <span className="text-lg font-semibold tabular-nums">{balance.toLocaleString()}</span>
        {rules.data && balance > 0 && (
          <span className="text-sm text-muted">
            {t("worth {amount}", { amount: money(Math.round(balance * rules.data.pointValue * 100) / 100, currency) })}
          </span>
        )}
        {can("LOYALTY_MANAGE") && (
          <Button size="sm" variant="secondary" className="ml-auto" onClick={() => setAdjusting(true)}>
            {t("Adjust points")}
          </Button>
        )}
      </div>
      {history.isPending ? (
        <Spinner />
      ) : history.error ? (
        <ErrorNote error={history.error} />
      ) : history.data.length === 0 ? (
        <p className="text-sm text-muted">{t("No points yet.")}</p>
      ) : (
        <LedgerTable rows={history.data} />
      )}
      {adjusting && <AdjustPointsDialog customer={{ id: customerId, code: null, name, phone: null }} onClose={() => setAdjusting(false)} />}
    </Card>
  );
}

/**
 * The customer's online shop account, if they have one. Staff confirm it once they have checked the
 * phone is the customer's (an account made with a phone already on file isn't trusted until then), and
 * remove it when the customer forgot the password, so they can create it again.
 */
function OnlineAccountSection({ customerId, tz }: { customerId: number; tz: string }) {
  const t = useT();
  const can = useCan();
  const queryClient = useQueryClient();
  const key = ["customers", customerId, "online-account"];
  const account = useQuery({
    queryKey: key,
    queryFn: async () => (await api<CustomerOnlineAccount | undefined>(`/customers/${customerId}/online-account`)) ?? null,
  });
  const done = () => {
    queryClient.invalidateQueries({ queryKey: key });
    queryClient.invalidateQueries({ queryKey: ["orders"] });
  };
  const verify = useMutation({ mutationFn: () => api<CustomerOnlineAccount>(`/customers/${customerId}/online-account/verify`, { method: "POST" }), onSuccess: done });
  const remove = useMutation({ mutationFn: () => api<void>(`/customers/${customerId}/online-account`, { method: "DELETE" }), onSuccess: done });
  if (!account.data) return account.error ? <ErrorNote error={account.error} /> : null;
  const a = account.data;
  return (
    <Card className="flex flex-col gap-2 p-4 text-sm">
      <div className="flex items-center gap-2">
        <h2 className="font-semibold">{t("Online shop account")}</h2>
        {a.verified ? <Badge tone="success">{t("Confirmed")}</Badge> : <Badge tone="warning">{t("Not confirmed")}</Badge>}
      </div>
      <div className="text-muted">
        {a.phone} · {t("{n} online orders", { n: a.orders })}
      </div>
      <div className="text-xs text-muted">
        {t("Created {date}", { date: dateTime(a.createdAt, tz) })}
        {a.lastLoginAt && ` · ${t("last signed in {date}", { date: dateTime(a.lastLoginAt, tz) })}`}
      </div>
      {!a.verified && (
        <p className="text-xs text-muted">
          {t("Made with a phone number already on file. Confirm it once the customer shows you this phone; until then its orders aren't on this customer's history.")}
        </p>
      )}
      <ErrorNote error={verify.error ?? remove.error} />
      {can("CUSTOMERS_MANAGE") && (
        <div className="flex flex-wrap gap-2">
          {!a.verified && (
            <Button size="sm" loading={verify.isPending} onClick={() => verify.mutate()}>
              {t("Confirm")}
            </Button>
          )}
          <Button
            size="sm"
            variant="secondary"
            loading={remove.isPending}
            onClick={() => confirm(translate("Remove the online account {phone}? It is signed out, and its orders stay as guest orders.", { phone: a.phone })) && remove.mutate()}
          >
            {t("Remove account")}
          </Button>
        </div>
      )}
    </Card>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Card className="p-4">
      <div className="text-xs text-muted">{label}</div>
      <div className="mt-1 text-lg font-semibold tabular-nums">{value}</div>
    </Card>
  );
}
