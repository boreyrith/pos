"use client";

import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight } from "lucide-react";
import { useSearchParams } from "next/navigation";
import { Fragment, useState } from "react";

import { Badge, Card, ErrorNote, Input, Pagination, Select, Spinner } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { dateTime } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { AuditCategory, AuditEntry, Page, User } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

const CATEGORIES: Record<AuditCategory, { label: string; tone: "neutral" | "primary" | "warning" | "success" | "danger" }> = {
  AUTH: { label: msg("Sign-in"), tone: "neutral" },
  USERS: { label: msg("Users"), tone: "primary" },
  SETTINGS: { label: msg("Settings"), tone: "primary" },
  CATALOG: { label: msg("Catalog"), tone: "success" },
  INVENTORY: { label: msg("Inventory"), tone: "success" },
  PURCHASING: { label: msg("Purchasing"), tone: "success" },
  SALES: { label: msg("Sales"), tone: "warning" },
};

/** Actions worth drawing the eye to. */
const ALERTS = new Set(["LOGIN_FAILED", "ACCOUNT_LOCKED", "SESSION_REUSE_DETECTED"]);

/** What each audit action is called (marked for translation). */
const ACTION_LABELS: Record<string, string> = {
  LOGIN: msg("Signed in"),
  LOGIN_FAILED: msg("Sign-in failed"),
  ACCOUNT_LOCKED: msg("Account locked"),
  LOGOUT: msg("Signed out"),
  PASSWORD_CHANGED: msg("Password changed"),
  SESSION_REUSE_DETECTED: msg("Session reuse detected"),
  USER_CREATED: msg("User created"),
  USER_UPDATED: msg("User updated"),
  USER_PASSWORD_RESET: msg("User password reset"),
  USER_UNLOCKED: msg("User unlocked"),
  USER_SESSIONS_REVOKED: msg("User signed out everywhere"),
  ROLE_CREATED: msg("Role created"),
  ROLE_UPDATED: msg("Role updated"),
  ROLE_DELETED: msg("Role deleted"),
  SETTINGS_UPDATED: msg("Settings updated"),
  LOGO_CHANGED: msg("Logo changed"),
  TAX_RATE_CREATED: msg("Tax rate created"),
  TAX_RATE_UPDATED: msg("Tax rate updated"),
  CURRENCY_CREATED: msg("Currency created"),
  CURRENCY_UPDATED: msg("Currency updated"),
  EXCHANGE_RATE_SET: msg("Exchange rate set"),
  BRANCH_CREATED: msg("Branch created"),
  BRANCH_UPDATED: msg("Branch updated"),
  STORAGE_CREATED: msg("Storage created"),
  STORAGE_UPDATED: msg("Storage updated"),
  UNIT_CREATED: msg("Unit created"),
  UNIT_UPDATED: msg("Unit updated"),
  UNIT_DELETED: msg("Unit deleted"),
  CATEGORY_CREATED: msg("Category created"),
  CATEGORY_UPDATED: msg("Category updated"),
  CATEGORY_DELETED: msg("Category deleted"),
  OFFERING_CREATED: msg("Catalog entry created"),
  OFFERING_UPDATED: msg("Catalog entry updated"),
  STOCK_CHANGED: msg("Stock changed"),
  STOCK_ADJUSTED: msg("Stock adjusted"),
  STOCK_COUNTED: msg("Stock counted"),
  TRANSFER_CREATED: msg("Transfer created"),
  TRANSFER_RECEIVED: msg("Transfer received"),
  TRANSFER_CANCELLED: msg("Transfer cancelled"),
  ORDER_CANCELLED: msg("Order cancelled"),
  ORDER_REFUNDED: msg("Order refunded"),
  ORDER_RETURNED: msg("Items returned"),
  PRICE_OVERRIDDEN: msg("Price changed"),
  ITEM_DISCOUNTED: msg("Item discounted"),
  SALE_HELD: msg("Sale held"),
  HELD_SALE_RESUMED: msg("Held sale resumed"),
  HELD_SALE_VOIDED: msg("Held sale voided"),
  HELD_SALE_EXPIRED: msg("Held sale expired"),
  CUSTOMER_CREATED: msg("Customer created"),
  CUSTOMER_UPDATED: msg("Customer updated"),
  VENDOR_CREATED: msg("Vendor created"),
  VENDOR_UPDATED: msg("Vendor updated"),
  PO_CREATED: msg("Purchase order created"),
  PO_UPDATED: msg("Purchase order updated"),
  PO_ORDERED: msg("Purchase order placed"),
  PO_CANCELLED: msg("Purchase order cancelled"),
  PO_CLOSED: msg("Purchase order closed"),
  GOODS_RECEIVED: msg("Goods received"),
  VENDOR_INVOICE_RECORDED: msg("Vendor invoice recorded"),
  VENDOR_INVOICE_VOIDED: msg("Vendor invoice voided"),
  VENDOR_PAYMENT_RECORDED: msg("Vendor payment recorded"),
};

/** An action without a label (added later): its code in words. */
const humanize = (action: string) => action.charAt(0) + action.slice(1).toLowerCase().replaceAll("_", " ");

export function AuditLogTab() {
  const t = useT();
  const store = useStoreSettings();
  const params = useSearchParams();
  const [userId, setUserId] = useState(params.get("userId") ?? "");
  const [category, setCategory] = useState<AuditCategory | "">("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<number | null>(null);

  const users = useQuery({ queryKey: ["users"], queryFn: () => api<User[]>("/users") });
  const entries = useQuery({
    queryKey: ["audit", { userId, category, from, to, q, page }],
    queryFn: () => api<Page<AuditEntry>>(`/audit-logs${queryString({ userId, category, from, to, q: q.trim(), page })}`),
    placeholderData: (previous) => previous,
  });
  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    setPage(0);
  };

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Input placeholder={t("Search what happened")} aria-label={t("Search the audit log")} value={q} onChange={(e) => reset(setQ)(e.target.value)} className="w-56" />
        <Select aria-label={t("User")} value={userId} onChange={(e) => reset(setUserId)(e.target.value)}>
          <option value="">{t("Everyone")}</option>
          {users.data?.map((u) => (
            <option key={u.id} value={u.id}>
              {u.fullName} ({u.username})
            </option>
          ))}
        </Select>
        <Select aria-label={t("Category")} value={category} onChange={(e) => reset(setCategory)(e.target.value as AuditCategory | "")}>
          <option value="">{t("All areas")}</option>
          {Object.entries(CATEGORIES).map(([k, v]) => (
            <option key={k} value={k}>
              {t(v.label)}
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
        {entries.isPending ? (
          <Spinner />
        ) : entries.error ? (
          <div className="p-4">
            <ErrorNote error={entries.error} />
          </div>
        ) : entries.data.items.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("Nothing recorded for these filters.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="w-8 px-2 py-3" aria-label={t("Expand")} />
                <th className="px-3 py-3 font-medium">{t("When")}</th>
                <th className="px-3 py-3 font-medium">{t("Who")}</th>
                <th className="px-3 py-3 font-medium">{t("What happened")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {entries.data.items.map((a) => {
                const expanded = open === a.id;
                const details = parse(a.details);
                return (
                  <Fragment key={a.id}>
                    <tr className={ALERTS.has(a.action) ? "bg-warning-soft/40" : "hover:bg-surface-2"}>
                      <td className="px-2 py-2.5 align-top">
                        <button
                          onClick={() => setOpen(expanded ? null : a.id)}
                          className="rounded p-1 text-muted hover:bg-surface"
                          aria-label={expanded ? t("Hide details") : t("Show details")}
                          aria-expanded={expanded}
                        >
                          {expanded ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
                        </button>
                      </td>
                      <td className="px-3 py-2.5 align-top whitespace-nowrap text-muted">{dateTime(a.createdAt, store.data?.timezone)}</td>
                      <td className="px-3 py-2.5 align-top whitespace-nowrap">{a.username ?? <span className="text-muted">{t("System")}</span>}</td>
                      <td className="px-3 py-2.5 align-top">
                        <div className="mb-0.5 flex flex-wrap items-center gap-1.5">
                          <Badge tone={CATEGORIES[a.category].tone}>{t(CATEGORIES[a.category].label)}</Badge>
                          <span className="text-xs text-muted">{ACTION_LABELS[a.action] ? t(ACTION_LABELS[a.action]) : humanize(a.action)}</span>
                        </div>
                        <div>{t(a.summary)}</div>
                      </td>
                    </tr>
                    {expanded && (
                      <tr className="bg-surface-2">
                        <td />
                        <td colSpan={3} className="px-3 py-3">
                          {details?.changes?.length ? (
                            <table className="mb-3 text-xs">
                              <thead className="text-left text-muted">
                                <tr>
                                  <th className="pr-6 pb-1 font-medium">{t("Field")}</th>
                                  <th className="pr-6 pb-1 font-medium">{t("Before")}</th>
                                  <th className="pb-1 font-medium">{t("After")}</th>
                                </tr>
                              </thead>
                              <tbody>
                                {details.changes.map((c, i) => (
                                  <tr key={i}>
                                    <td className="pr-6 font-medium">{c.field}</td>
                                    <td className="pr-6 text-danger">{c.from ?? "–"}</td>
                                    <td className="text-success">{c.to ?? "–"}</td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          ) : null}
                          <dl className="grid grid-cols-[100px_1fr] gap-x-3 gap-y-0.5 text-xs text-muted">
                            {a.entityType && (
                              <>
                                <dt>{t("Record")}</dt>
                                <dd>
                                  {a.entityType} {a.entityId}
                                </dd>
                              </>
                            )}
                            <dt>{t("IP address")}</dt>
                            <dd>{a.ip ?? "–"}</dd>
                            <dt>{t("Browser")}</dt>
                            <dd className="break-all">{a.userAgent ?? "–"}</dd>
                          </dl>
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
      {entries.data && <Pagination page={page} totalPages={entries.data.totalPages} onChange={setPage} />}
    </div>
  );
}

function parse(details: string | null): { changes?: { field: string; from: string | null; to: string | null }[] } | null {
  if (!details) return null;
  try {
    return JSON.parse(details);
  } catch {
    return null;
  }
}
