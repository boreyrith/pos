"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { Download } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { Button, Card, ErrorNote, Field, Input, PageHeader, Spinner } from "@/components/ui";
import { api, download, queryString } from "@/lib/api";
import { money } from "@/lib/format";
import { taxLabel } from "@/lib/pricing";
import { useStoreSettings } from "@/lib/queries";
import type { TaxReport } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

import { BranchFilter } from "../../inventory/BranchFilter";

const MODE_LABELS = { NONE: msg("No VAT"), INCLUSIVE: msg("VAT included"), EXCLUSIVE: msg("VAT setup value") };

/** A store-local date as yyyy-mm-dd. */
function localDate(timeZone: string | undefined, d = new Date()) {
  return new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).format(d);
}

/**
 * Tax collected and refunded over a date range, from what each order and return saved when it was
 * made: changing the tax settings never changes these figures.
 */
export function TaxReportView() {
  const t = useT();
  const store = useStoreSettings();
  if (!store.data) return store.error ? <ErrorNote error={store.error} /> : <Spinner />;
  if (store.data.taxMode === "NONE") {
    return (
      <div className="mx-auto max-w-6xl p-4 lg:p-8">
        <PageHeader title={t("Tax report")} />
        <Card className="p-6 text-sm">
          <p className="font-medium">{t("The tax report is off while the store is set to No VAT.")}</p>
          <p className="mt-1 text-muted">
            {t("To see it, choose VAT included or VAT setup value under")}{" "}
            <Link href="/settings?tab=payments" className="text-primary hover:underline">
              {t("Settings › Taxes & payments")}
            </Link>
            .
          </p>
        </Card>
      </div>
    );
  }
  return <TaxReportBody timeZone={store.data.timezone} label={store.data.taxLabel} />;
}

function TaxReportBody({ timeZone, label }: { timeZone: string; label: string }) {
  const t = useT();
  const today = localDate(timeZone);
  const [from, setFrom] = useState(today.slice(0, 8) + "01");
  const [to, setTo] = useState(today);
  const [branchId, setBranchId] = useState("");
  const valid = from !== "" && to !== "" && from <= to;
  const params = queryString({ from, to, branchId });

  const report = useQuery({
    queryKey: ["reports", "tax", from, to, branchId],
    queryFn: () => api<TaxReport>(`/reports/tax${params}`),
    enabled: valid,
    placeholderData: (previous) => previous,
  });
  const exportCsv = useMutation({ mutationFn: () => download(`/reports/tax.csv${params}`, `tax-report-${from}-to-${to}.csv`) });

  return (
    <div className="mx-auto max-w-6xl p-4 lg:p-8">
      <PageHeader
        title={t("Tax report")}
        actions={
          <>
            <BranchFilter value={branchId} onChange={setBranchId} />
            <Button variant="secondary" onClick={() => exportCsv.mutate()} loading={exportCsv.isPending} disabled={!valid}>
              <Download className="size-4" aria-hidden />{" "}{t("Export CSV")}
            </Button>
          </>
        }
      />
      <div className="mb-5 flex flex-wrap items-end gap-3">
        <Field label={t("From")}>
          <Input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
        </Field>
        <Field label={t("To")}>
          <Input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} />
        </Field>
        <p className="pb-2 text-xs text-muted">{t("Sales count on the day they were paid, refunds on the day they were made.")}</p>
      </div>
      <ErrorNote error={exportCsv.error} />
      {!valid ? (
        <p className="text-sm text-danger">{t("The start date must be on or before the end date.")}</p>
      ) : report.isPending ? (
        <Spinner />
      ) : report.error ? (
        <ErrorNote error={report.error} />
      ) : (
        <ReportBody data={report.data} label={label} />
      )}
    </div>
  );
}

function ReportBody({ data, label }: { data: TaxReport; label: string }) {
  const t = useT();
  const c = data.currency;
  const s = data.summary;
  const m = (n: number) => money(n, c);
  return (
    <div className="flex flex-col gap-5">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label={t("{label} collected", { label })} value={m(s.taxCollected)} hint={t("{m} added · {m2} included", { m: m(s.taxAdded), m2: m(s.taxIncluded) })} />
        <Stat label={t("{label} refunded", { label })} value={m(s.taxRefunded)} hint={`${s.returns} ${s.returns === 1 ? "return" : "returns"}`} />
        <Stat label={t("Net {label} due", { label })} value={m(s.netTax)} strong />
        <Stat label={t("Gross sales")} value={m(s.grossSales)} hint={t("{orders} orders", { orders: s.orders })} />
      </div>

      <Card className="p-5">
        <h2 className="mb-3 text-sm font-semibold">{t("Summary")}</h2>
        <dl className="grid gap-x-8 gap-y-1.5 text-sm sm:grid-cols-2">
          <Line label={t("Gross sales (paid by customers)")} value={m(s.grossSales)} />
          <Line label={t("Discounts given")} value={m(s.discounts)} />
          <Line label={t("Net sales (without {label})", { label })} value={m(s.netSales)} />
          <Line label={t("Taxable sales")} value={m(s.taxableSales)} />
          <Line label={t("Non-taxable sales")} value={m(s.nonTaxableSales)} />
          <Line label={t("{label} added on top", { label })} value={m(s.taxAdded)} />
          <Line label={t("{label} included in prices", { label })} value={m(s.taxIncluded)} />
          <Line label={t("{label} collected", { label })} value={m(s.taxCollected)} />
          <Line label={t("Refunded to customers")} value={m(s.refunds)} />
          <Line label={t("Refunds without {label}", { label })} value={m(s.netRefunds)} />
          <Line label={t("{label} refunded", { label })} value={m(s.taxRefunded)} />
          <Line label={t("Net {label} due", { label })} value={m(s.netTax)} strong />
        </dl>
      </Card>

      <Card className="overflow-x-auto">
        <h2 className="px-4 pt-4 text-sm font-semibold">{t("By rate")}</h2>
        {data.byRate.length === 0 ? (
          <p className="p-4 text-sm text-muted">{t("No sales or refunds in this period.")}</p>
        ) : (
          <table className="mt-2 w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                <th className="px-4 py-2 font-medium">{t("Rate")}</th>
                <th className="px-4 py-2 font-medium">{t("Charged")}</th>
                <th className="px-4 py-2 text-right font-medium">{t("Taxable base")}</th>
                <th className="px-4 py-2 text-right font-medium">{t("Tax")}</th>
                <th className="px-4 py-2 text-right font-medium">{t("Refunded base")}</th>
                <th className="px-4 py-2 text-right font-medium">{t("Tax refunded")}</th>
                <th className="px-4 py-2 text-right font-medium">{t("Net tax")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border tabular-nums">
              {data.byRate.map((r) => (
                <tr key={`${r.name}-${r.rate}-${r.included}`}>
                  <td className="px-4 py-2 font-medium">{r.rate > 0 ? taxLabel(r) : r.name}</td>
                  <td className="px-4 py-2 text-muted">{r.rate === 0 ? t("No tax") : r.included ? t("Included in price") : t("Added on top")}</td>
                  <td className="px-4 py-2 text-right">{m(r.taxableBase)}</td>
                  <td className="px-4 py-2 text-right">{m(r.tax)}</td>
                  <td className="px-4 py-2 text-right">{m(r.refundedBase)}</td>
                  <td className="px-4 py-2 text-right">{m(r.refundedTax)}</td>
                  <td className="px-4 py-2 text-right font-medium">{m(r.netTax)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <div className="grid gap-5 lg:grid-cols-[1fr_2fr]">
        <Card className="p-4">
          <h2 className="mb-2 text-sm font-semibold">{t("By tax mode at the time of sale")}</h2>
          {data.byMode.length === 0 ? (
            <p className="text-sm text-muted">{t("No sales.")}</p>
          ) : (
            <ul className="divide-y divide-border text-sm">
              {data.byMode.map((r) => (
                <li key={r.mode} className="flex justify-between gap-3 py-2">
                  <span>
                    {t(MODE_LABELS[r.mode])} <span className="text-muted">{t("· {orders} orders", { orders: r.orders })}</span>
                  </span>
                  <span className="text-right tabular-nums">
                    {m(r.grossSales)}
                    <span className="block text-xs text-muted">{t("tax {m}", { m: m(r.tax) })}</span>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card className="max-h-96 overflow-auto">
          <table className="w-full text-sm">
            <thead className="sticky top-0 border-b border-border bg-surface text-left text-xs text-muted">
              <tr>
                <th className="px-4 py-2 font-medium">{t("Day")}</th>
                <th className="px-4 py-2 text-right font-medium">{t("Gross sales")}</th>
                <th className="px-4 py-2 text-right font-medium">{t("Tax collected")}</th>
                <th className="px-4 py-2 text-right font-medium">{t("Refunds")}</th>
                <th className="px-4 py-2 text-right font-medium">{t("Tax refunded")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border tabular-nums">
              {data.daily.map((d) => (
                <tr key={d.date} className={d.grossSales === 0 && d.refunds === 0 ? "text-muted" : ""}>
                  <td className="px-4 py-1.5">{d.date}</td>
                  <td className="px-4 py-1.5 text-right">{m(d.grossSales)}</td>
                  <td className="px-4 py-1.5 text-right">{m(d.taxCollected)}</td>
                  <td className="px-4 py-1.5 text-right">{d.refunds ? `-${m(d.refunds)}` : m(0)}</td>
                  <td className="px-4 py-1.5 text-right">{d.taxRefunded ? `-${m(d.taxRefunded)}` : m(0)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      </div>
    </div>
  );
}

function Stat({ label, value, hint, strong }: { label: string; value: string; hint?: string; strong?: boolean }) {
  return (
    <Card className={`p-4 ${strong ? "border-primary" : ""}`}>
      <div className="text-xs text-muted">{label}</div>
      <div className="mt-1 text-xl font-semibold tabular-nums">{value}</div>
      {hint && <div className="mt-0.5 text-xs text-muted tabular-nums">{hint}</div>}
    </Card>
  );
}

function Line({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="flex justify-between gap-3 border-b border-border py-1">
      <dt className={strong ? "font-semibold" : "text-muted"}>{label}</dt>
      <dd className={`tabular-nums ${strong ? "font-semibold" : ""}`}>{value}</dd>
    </div>
  );
}
