"use client";

import { useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { CustomerDialog } from "@/components/customers/CustomerDialog";
import { Badge, Button, Card, ErrorNote, Input, Pagination, Spinner } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { dateTime } from "@/lib/format";
import { useStoreSettings } from "@/lib/queries";
import type { Customer, Page } from "@/lib/types";
import { useT } from "@/lib/i18n";

/** Sales > Customers: search by name, phone (any format), code or email. The Walk-in Customer is listed first. */
export function CustomersView() {
  const t = useT();
  const can = useCan();
  const store = useStoreSettings();
  const [q, setQ] = useState("");
  const [activeOnly, setActiveOnly] = useState(true);
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const customers = useQuery({
    queryKey: ["customers", "list", q.trim(), activeOnly, page],
    queryFn: () => api<Page<Customer>>(`/customers${queryString({ q: q.trim(), activeOnly, page })}`),
    placeholderData: (previous) => previous,
  });

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <Input
          placeholder={t("Name, phone, code or email")}
          aria-label={t("Search customers")}
          value={q}
          onChange={(e) => {
            setQ(e.target.value);
            setPage(0);
          }}
          className="w-72"
        />
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={activeOnly}
            onChange={(e) => {
              setActiveOnly(e.target.checked);
              setPage(0);
            }}
          />
          {t("Active only")}
        </label>
        {can("CUSTOMERS_MANAGE") && (
          <Button className="ml-auto" onClick={() => setAdding(true)}>
            <Plus className="size-4" aria-hidden />{" "}{t("New customer")}
          </Button>
        )}
      </div>
      <Card className="overflow-x-auto">
        {customers.isPending ? (
          <Spinner />
        ) : customers.error ? (
          <div className="p-4">
            <ErrorNote error={customers.error} />
          </div>
        ) : customers.data.items.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("No customers match.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="px-4 py-3 font-medium">{t("Customer")}</th>
                <th className="px-4 py-3 font-medium">{t("Phone")}</th>
                <th className="px-4 py-3 font-medium">{t("Email")}</th>
                <th className="px-4 py-3 font-medium">{t("Added")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {customers.data.items.map((c) => (
                <tr key={c.id} className={c.active ? "hover:bg-surface-2" : "text-muted hover:bg-surface-2"}>
                  <td className="px-4 py-3">
                    <Link href={`/customers/${c.id}`} className="font-medium text-primary hover:underline">
                      {c.name}
                    </Link>
                    <span className="ml-2 inline-flex gap-1 align-middle">
                      {c.walkIn && <Badge tone="primary">{t("Default")}</Badge>}
                      {!c.active && <Badge>{t("Inactive")}</Badge>}
                    </span>
                    <div className="font-mono text-xs text-muted">
                      {c.code}
                      {c.taxId && ` · TIN ${c.taxId}`}
                    </div>
                  </td>
                  <td className="px-4 py-3 tabular-nums">{c.phone ?? "–"}</td>
                  <td className="px-4 py-3">{c.email ?? "–"}</td>
                  <td className="px-4 py-3 text-muted">{dateTime(c.createdAt, store.data?.timezone)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      {customers.data && <Pagination page={page} totalPages={customers.data.totalPages} onChange={setPage} />}
      {adding && <CustomerDialog onClose={() => setAdding(false)} />}
    </div>
  );
}
