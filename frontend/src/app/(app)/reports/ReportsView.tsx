"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Download, Percent, Printer } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useMemo, useState } from "react";

import { Button, Card, ErrorNote, Field, Input, PageHeader, Select, Spinner, cx } from "@/components/ui";
import { api, download, queryString } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { dateTime, money, todayIn } from "@/lib/format";
import { useCategories, useStoreSettings } from "@/lib/queries";
import type { ReportColumn, ReportInfo, ReportResult } from "@/lib/types";
import { msg, translateServer, useT } from "@/lib/i18n";

import { BranchFilter } from "../inventory/BranchFilter";

type Range = { from: string; to: string };

/** Quick date ranges, in the store's time zone. */
function presets(today: string): { label: string; range: Range }[] {
  const d = new Date(`${today}T00:00:00Z`);
  const iso = (x: Date) => x.toISOString().slice(0, 10);
  const add = (x: Date, days: number) => new Date(x.getTime() + days * 86_400_000);
  const monday = add(d, -((d.getUTCDay() + 6) % 7));
  const monthStart = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1));
  const lastMonthStart = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - 1, 1));
  return [
    { label: msg("Today"), range: { from: today, to: today } },
    { label: msg("Yesterday"), range: { from: iso(add(d, -1)), to: iso(add(d, -1)) } },
    { label: msg("This week"), range: { from: iso(monday), to: today } },
    { label: msg("This month"), range: { from: iso(monthStart), to: today } },
    { label: msg("Last month"), range: { from: iso(lastMonthStart), to: iso(add(monthStart, -1)) } },
    { label: msg("Last 30 days"), range: { from: iso(add(d, -29)), to: today } },
    { label: msg("This year"), range: { from: `${d.getUTCFullYear()}-01-01`, to: today } },
  ];
}

/**
 * Reports: pick a report, a date range and branch (and grouping or filters where the report has
 * them), then read, sort, print or export it as CSV. Each report shows only to roles allowed to see it.
 */
export function ReportsView() {
  const t = useT();
  const can = useCan();
  const store = useStoreSettings();
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const catalog = useQuery({ queryKey: ["reports", "catalog"], queryFn: () => api<ReportInfo[]>("/reports/catalog") });
  const today = todayIn(store.data?.timezone ?? "Asia/Phnom_Penh");
  const [range, setRange] = useState<Range>(() => presets(today)[3].range);
  const [branchId, setBranchId] = useState("");
  const [groupBy, setGroupBy] = useState("");
  const [type, setType] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const showTax = can("REPORTS_TAX") && store.data?.taxMode !== "NONE";

  const list = useMemo(() => catalog.data ?? [], [catalog.data]);
  const selected = list.find((r) => r.key === params.get("r")) ?? list[0];
  const choose = (key: string) => {
    setGroupBy("");
    setType("");
    setCategoryId("");
    router.replace(`${pathname}?r=${key}`);
  };
  const groups = useMemo(() => {
    const g = new Map<string, ReportInfo[]>();
    for (const r of list) g.set(r.group, [...(g.get(r.group) ?? []), r]);
    return [...g.entries()];
  }, [list]);

  if (catalog.isPending || !store.data) return <Spinner />;
  if (catalog.error) return <div className="p-8"><ErrorNote error={catalog.error} /></div>;

  return (
    <div className="mx-auto max-w-7xl p-4 lg:p-8 print:p-0">
      <div className="print:hidden">
        <PageHeader title={t("Reports")} />
      </div>
      <div className="grid gap-5 lg:grid-cols-[230px_1fr]">
        <nav aria-label={t("Reports")} className="flex flex-col gap-4 print:hidden">
          {groups.map(([group, items]) => (
            <div key={group}>
              <h2 className="mb-1 px-2 text-xs font-semibold tracking-wide text-muted uppercase">{translateServer(group)}</h2>
              <ul className="flex flex-col">
                {items.map((r) => (
                  <li key={r.key}>
                    <button
                      onClick={() => choose(r.key)}
                      aria-current={selected?.key === r.key ? "page" : undefined}
                      className={cx(
                        "w-full rounded-lg px-2 py-1.5 text-left text-sm",
                        selected?.key === r.key ? "bg-primary-soft font-medium text-primary" : "hover:bg-surface-2",
                      )}
                    >
                      {translateServer(r.title)}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ))}
          {showTax && (
            <div>
              <h2 className="mb-1 px-2 text-xs font-semibold tracking-wide text-muted uppercase">{t("Tax")}</h2>
              <Link href="/reports/tax" className="flex items-center gap-1.5 rounded-lg px-2 py-1.5 text-sm hover:bg-surface-2">
                <Percent className="size-3.5" aria-hidden />{" "}{t("Tax report")}
              </Link>
            </div>
          )}
          {list.length === 0 && !showTax && <p className="px-2 text-sm text-muted">{t("Your role has no reports.")}</p>}
        </nav>

        {selected && (
          <ReportPanel
            key={selected.key}
            info={selected}
            range={range}
            setRange={setRange}
            today={today}
            branchId={branchId}
            setBranchId={setBranchId}
            groupBy={groupBy}
            setGroupBy={setGroupBy}
            type={type}
            setType={setType}
            categoryId={categoryId}
            setCategoryId={setCategoryId}
            timezone={store.data.timezone}
          />
        )}
      </div>
    </div>
  );
}

function ReportPanel(props: {
  info: ReportInfo;
  range: Range;
  setRange: (r: Range) => void;
  today: string;
  branchId: string;
  setBranchId: (v: string) => void;
  groupBy: string;
  setGroupBy: (v: string) => void;
  type: string;
  setType: (v: string) => void;
  categoryId: string;
  setCategoryId: (v: string) => void;
  timezone: string;
}) {
  const t = useT();
  const { info, range, branchId, groupBy, type, categoryId } = props;
  const categories = useCategories();
  const valid = !info.dated || (range.from !== "" && range.to !== "" && range.from <= range.to);
  const qs = queryString({
    from: info.dated ? range.from : null,
    to: info.dated ? range.to : null,
    branchId,
    groupBy,
    type,
    categoryId,
  });
  const report = useQuery({
    queryKey: ["reports", info.key, qs],
    queryFn: () => api<ReportResult>(`/reports/run/${info.key}${qs}`),
    enabled: valid,
    placeholderData: (previous) => (previous?.key === info.key ? previous : undefined),
  });
  const exportCsv = useMutation({ mutationFn: () => download(`/reports/run/${info.key}.csv${qs}`, `${info.key}-${range.from}-to-${range.to}.csv`) });
  const groupOptions =
    info.key === "sales-summary"
      ? [
          ["day", msg("By day")],
          ["week", msg("By week")],
          ["month", msg("By month")],
        ]
      : info.key === "sales-by-time"
        ? [
            ["hour", msg("By hour of day")],
            ["weekday", msg("By weekday")],
          ]
        : null;

  return (
    <section aria-label={translateServer(info.title)} className="min-w-0">
      <div className="mb-1 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-lg font-semibold">{translateServer(info.title)}</h2>
        <div className="flex gap-2 print:hidden">
          <Button variant="secondary" size="sm" onClick={() => window.print()} disabled={!report.data}>
            <Printer className="size-4" aria-hidden />{" "}{t("Print")}
          </Button>
          <Button variant="secondary" size="sm" onClick={() => exportCsv.mutate()} loading={exportCsv.isPending} disabled={!valid}>
            <Download className="size-4" aria-hidden />{" "}{t("Export CSV")}
          </Button>
        </div>
      </div>
      <p className="mb-4 text-sm text-muted">{translateServer(info.description)}</p>

      <Card className="mb-4 flex flex-wrap items-end gap-3 p-4 print:hidden">
        {info.dated ? (
          <>
            <Field label={t("From")}>
              <Input type="date" value={range.from} max={range.to} onChange={(e) => props.setRange({ ...range, from: e.target.value })} />
            </Field>
            <Field label={t("To")}>
              <Input type="date" value={range.to} min={range.from} onChange={(e) => props.setRange({ ...range, to: e.target.value })} />
            </Field>
            <Field label={t("Quick range")}>
              <Select
                value=""
                onChange={(e) => {
                  const p = presets(props.today).find((x) => x.label === e.target.value);
                  if (p) props.setRange(p.range);
                }}
              >
                <option value="">{t("Choose…")}</option>
                {presets(props.today).map((p) => (
                  <option key={p.label}>{t(p.label)}</option>
                ))}
              </Select>
            </Field>
          </>
        ) : (
          <p className="pb-2 text-sm text-muted">{t("As of now")}</p>
        )}
        <Field label={t("Branch")}>
          <BranchFilter value={branchId} onChange={props.setBranchId} />
        </Field>
        {groupOptions && (
          <Field label={t("Group")}>
            <Select value={groupBy || groupOptions[0][0]} onChange={(e) => props.setGroupBy(e.target.value)}>
              {groupOptions.map(([v, l]) => (
                <option key={v} value={v}>
                  {t(l)}
                </option>
              ))}
            </Select>
          </Field>
        )}
        {info.key === "sales-by-item" && (
          <Field label={t("Type")}>
            <Select value={type} onChange={(e) => props.setType(e.target.value)}>
              <option value="">{t("All")}</option>
              <option value="PRODUCT">{t("Products")}</option>
              <option value="SERVICE">{t("Services")}</option>
              <option value="ITEM">{t("Items")}</option>
              <option value="PACKAGE">{t("Packages")}</option>
            </Select>
          </Field>
        )}
        {(info.key === "sales-by-item" || info.key === "inventory-valuation") && (
          <Field label={t("Category")}>
            <Select value={categoryId} onChange={(e) => props.setCategoryId(e.target.value)}>
              <option value="">{t("All")}</option>
              {categories.data?.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </Select>
          </Field>
        )}
      </Card>
      <ErrorNote error={exportCsv.error} />

      {!valid ? (
        <p className="text-sm text-danger">{t("The start date must be on or before the end date.")}</p>
      ) : report.isPending ? (
        <Spinner />
      ) : report.error ? (
        <ErrorNote error={report.error} />
      ) : (
        <ReportTable report={report.data} timezone={props.timezone} range={info.dated ? range : null} />
      )}
    </section>
  );
}

/** Any report as a sortable table with a totals row and its notes; summary reports also get bars. */
function ReportTable({ report, timezone, range }: { report: ReportResult; timezone: string; range: Range | null }) {
  const t = useT();
  const [sort, setSort] = useState<{ key: string; desc: boolean } | null>(null);
  const rows = useMemo(() => {
    if (!sort) return report.rows;
    return [...report.rows].sort((a, b) => {
      const x = a[sort.key];
      const y = b[sort.key];
      const c = typeof x === "number" && typeof y === "number" ? x - y : String(x ?? "").localeCompare(String(y ?? ""));
      return sort.desc ? -c : c;
    });
  }, [report.rows, sort]);
  const fmt = (col: ReportColumn, v: string | number | null | undefined) => {
    if (v === null || v === undefined || v === "") return "";
    switch (col.type) {
      case "money":
        return money(Number(v), report.currency);
      case "percent":
        return `${Number(v)}%`;
      case "int":
        return Number(v).toLocaleString();
      case "decimal":
        return Number(v).toLocaleString(undefined, { maximumFractionDigits: 4 });
      case "datetime":
        return dateTime(String(v), timezone);
      default:
        // Kinds, types and weekdays come from the server in English; names and codes pass through.
        return translateServer(String(v));
    }
  };
  const numeric = (x: ReportColumn["type"]) => x !== "text" && x !== "date" && x !== "datetime";
  const chart =
    report.key === "sales-summary"
      ? { label: "period", value: "netAfterReturns", title: msg("Net sales after returns") }
      : report.key === "sales-by-time"
        ? { label: "slot", value: "total", title: msg("Sales") }
        : null;

  return (
    <div className="flex flex-col gap-4">
      <div className="hidden print:block">
        <h1 className="text-lg font-semibold">{translateServer(report.title)}</h1>
        <p className="text-sm">{range ? t("{from} to {to}", { from: range.from, to: range.to }) : t("As of now")}</p>
      </div>
      {chart && report.rows.length > 1 && <Bars rows={report.rows} labelKey={chart.label} valueKey={chart.value} title={t(chart.title)} currency={report.currency} />}
      <Card className="overflow-x-auto print:border-0">
        {rows.length === 0 ? (
          <p className="p-8 text-center text-sm text-muted">{t("Nothing for this period.")}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-border text-left text-xs text-muted">
              <tr>
                {report.columns.map((c) => (
                  <th key={c.key} className={cx("px-3 py-2 font-medium whitespace-nowrap", numeric(c.type) && "text-right")}>
                    <button
                      className="inline-flex items-center gap-0.5 hover:text-text print:pointer-events-none"
                      onClick={() => setSort((s) => (s?.key === c.key ? { key: c.key, desc: !s.desc } : { key: c.key, desc: numeric(c.type) }))}
                    >
                      {translateServer(c.label)}
                      {sort?.key === c.key && (sort.desc ? <ArrowDown className="size-3" /> : <ArrowUp className="size-3" />)}
                    </button>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {rows.map((r, i) => (
                <tr key={i} className="hover:bg-surface-2">
                  {report.columns.map((c, ci) => (
                    <td
                      key={c.key}
                      className={cx(
                        "px-3 py-1.5",
                        ci === 0 && "whitespace-nowrap",
                        numeric(c.type) ? "text-right whitespace-nowrap tabular-nums" : "max-w-72",
                        c.type === "money" && Number(r[c.key]) < 0 && "text-danger",
                      )}
                    >
                      {fmt(c, r[c.key])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
            {report.totals && (
              <tfoot className="border-t-2 border-border font-semibold">
                <tr>
                  {report.columns.map((c) => (
                    <td key={c.key} className={cx("px-3 py-2", numeric(c.type) && "text-right whitespace-nowrap tabular-nums")}>
                      {fmt(c, report.totals?.[c.key])}
                    </td>
                  ))}
                </tr>
              </tfoot>
            )}
          </table>
        )}
      </Card>
      {report.notes.length > 0 && (
        <ul className="list-disc space-y-1 pl-5 text-xs text-muted">
          {report.notes.map((n) => (
            <li key={n}>{translateServer(n)}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** A simple bar chart of one column; hover a bar for its value. */
function Bars({
  rows,
  labelKey,
  valueKey,
  title,
  currency,
}: {
  rows: Record<string, string | number | null>[];
  labelKey: string;
  valueKey: string;
  title: string;
  currency: string;
}) {
  const t = useT();
  const values = rows.map((r) => Number(r[valueKey] ?? 0));
  const max = Math.max(...values, 0);
  return (
    <Card className="p-4">
      <h3 className="mb-3 text-xs font-medium text-muted">{title}</h3>
      <div className="flex h-36 items-end gap-0.5" role="img" aria-label={t("{title} chart; the table below has the figures", { title })}>
        {rows.map((r, i) => (
          <div key={i} className="group relative flex h-full min-w-0 flex-1 flex-col justify-end">
            <div
              className="rounded-t bg-chart-1 group-hover:opacity-80"
              style={{ height: values[i] > 0 && max > 0 ? `max(2px, ${(values[i] / max) * 100}%)` : 0 }}
            />
            <span className="pointer-events-none absolute bottom-full left-1/2 z-10 mb-1 hidden -translate-x-1/2 rounded bg-text px-1.5 py-0.5 text-[10px] whitespace-nowrap text-surface group-hover:block">
              {String(r[labelKey])}: {money(values[i], currency)}
            </span>
          </div>
        ))}
      </div>
      <div className="mt-1 flex justify-between text-[10px] text-muted">
        <span>{String(rows[0][labelKey])}</span>
        <span>{String(rows[rows.length - 1][labelKey])}</span>
      </div>
    </Card>
  );
}
