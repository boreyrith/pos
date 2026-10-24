"use client";

import { useQuery } from "@tanstack/react-query";
import JsBarcode from "jsbarcode";
import { Eye, Minus, Plus, Printer, Search, Trash2, ZoomIn, ZoomOut } from "lucide-react";
import { QRCodeSVG } from "qrcode.react";
import { useEffect, useMemo, useRef, useState } from "react";

import { Button, Card, Input, Select, Spinner, cx } from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { TYPE_LABELS, money } from "@/lib/format";
import { taxKeyFor } from "@/lib/pricing";
import { useCategories, useStoreSettings } from "@/lib/queries";
import type { Offering, OfferingType, Page, StoreSettings } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

/** Label sizes: thermal rolls print one label per page; A4 sheets lay labels out in a grid. */
const TEMPLATES = {
  roll50x30: { label: msg("Roll label 50 × 30 mm"), w: 50, h: 30, sheet: null },
  roll38x25: { label: msg("Small roll label 38 × 25 mm"), w: 38, h: 25, sheet: null },
  roll60x40: { label: msg("Price tag 60 × 40 mm"), w: 60, h: 40, sheet: null },
  a4x24: { label: msg("A4 sheet, 24 labels (3 × 8, 70 × 37 mm)"), w: 70, h: 37, sheet: { cols: 3, rows: 8, top: 0, left: 0 } },
  a4x40: { label: msg("A4 sheet, 40 labels (4 × 10, 52.5 × 29.7 mm)"), w: 52.5, h: 29.7, sheet: { cols: 4, rows: 10, top: 0, left: 0 } },
} as const;
type TemplateKey = keyof typeof TEMPLATES;

type CodeKind = "barcode" | "qr" | "none";

interface Options {
  template: TemplateKey;
  code: CodeKind;
  name: boolean;
  nameKm: boolean;
  price: boolean;
  /** For VAT-added prices: print what the customer pays (price + VAT). */
  withVat: boolean;
  /** The code as text: SKU, plus the barcode number when it differs. */
  sku: boolean;
  description: boolean;
  unit: boolean;
  store: boolean;
}

const DEFAULTS: Options = {
  template: "roll50x30",
  code: "barcode",
  name: true,
  nameKm: true,
  price: true,
  withVat: true,
  sku: true,
  description: true,
  unit: true,
  store: false,
};
const OPTIONS_KEY = "pos.labels.options.v2";

interface QueueLine {
  offering: Offering;
  copies: number;
}

/**
 * Catalog > Print labels: pick what to label and how many copies, choose a label size and what
 * goes on it, check the preview at true size, then print. Barcodes print the entry's barcode (EAN
 * or UPC when it is a valid one, otherwise Code 128), or its SKU when it has none.
 */
export function LabelsTab() {
  const t = useT();
  const store = useStoreSettings();
  const categories = useCategories();
  const [q, setQ] = useState("");
  const [type, setType] = useState<OfferingType | "">("");
  const [categoryId, setCategoryId] = useState("");
  const [queue, setQueue] = useState<QueueLine[]>([]);
  const [previewing, setPreviewing] = useState(false);
  const [options, setOptions] = useState<Options>(() => {
    try {
      return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(OPTIONS_KEY) ?? "{}") };
    } catch {
      return DEFAULTS;
    }
  });
  useEffect(() => {
    try {
      localStorage.setItem(OPTIONS_KEY, JSON.stringify(options));
    } catch {
      // not kept
    }
  }, [options]);
  const set = <K extends keyof Options>(k: K, v: Options[K]) => setOptions((o) => ({ ...o, [k]: v }));

  const results = useQuery({
    queryKey: ["offerings", "labels", q.trim(), type, categoryId],
    queryFn: () => api<Page<Offering>>(`/offerings${queryString({ q: q.trim(), type, categoryId, forSale: true, size: 50 })}`),
    placeholderData: (previous) => previous,
  });

  const add = (o: Offering, copies = 1) =>
    setQueue((qs) => {
      const existing = qs.find((l) => l.offering.id === o.id);
      return existing
        ? qs.map((l) => (l.offering.id === o.id ? { ...l, copies: Math.min(999, l.copies + copies) } : l))
        : [...qs, { offering: o, copies: Math.max(1, Math.min(999, copies)) }];
    });
  const setCopies = (id: number, copies: number) =>
    setQueue((qs) => (copies <= 0 ? qs.filter((l) => l.offering.id !== id) : qs.map((l) => (l.offering.id === id ? { ...l, copies: Math.min(999, copies) } : l))));
  const labels = useMemo(() => queue.flatMap((l) => Array.from({ length: l.copies }, () => l.offering)), [queue]);
  const x = TEMPLATES[options.template];

  if (!store.data) return <Spinner />;
  const exclusive = store.data.taxMode === "EXCLUSIVE";

  return (
    <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
      <div className="flex flex-col gap-4 print:hidden">
        <Card className="p-4">
          <h2 className="mb-3 text-sm font-semibold">{t("1. Choose what to label")}</h2>
          <div className="mb-3 flex flex-wrap gap-2">
            <div className="relative min-w-48 flex-1">
              <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted" aria-hidden />
              <Input placeholder={t("Name, SKU or barcode")} aria-label={t("Search the catalog")} value={q} onChange={(e) => setQ(e.target.value)} className="pl-9" />
            </div>
            <Select aria-label={t("Type")} value={type} onChange={(e) => setType(e.target.value as OfferingType | "")}>
              <option value="">{t("All types")}</option>
              {(["PRODUCT", "SERVICE", "ITEM", "PACKAGE"] as const).map((x) => (
                <option key={x} value={x}>
                  {t(TYPE_LABELS[x])}s
                </option>
              ))}
            </Select>
            <Select aria-label={t("Category")} value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
              <option value="">{t("All categories")}</option>
              {categories.data?.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </Select>
          </div>
          <div className="max-h-80 overflow-y-auto rounded-lg border border-border">
            {results.isPending ? (
              <Spinner />
            ) : results.data?.items.length === 0 ? (
              <p className="p-4 text-center text-sm text-muted">{t("Nothing for sale matches.")}</p>
            ) : (
              <ul className="divide-y divide-border text-sm">
                {results.data?.items.map((o) => (
                  <li key={o.id} className="flex items-center gap-2 px-3 py-2">
                    <div className="min-w-0 flex-1">
                      <div className="truncate font-medium">{o.name}</div>
                      <div className="truncate font-mono text-xs text-muted">
                        {o.barcode ?? o.sku} · {money(o.price, store.data!.currency)}
                        {o.stock != null && t(" · {stock} in stock", { stock: o.stock })}
                      </div>
                    </div>
                    {o.stock != null && o.stock > 0 && (
                      <Button size="sm" variant="ghost" onClick={() => add(o, o.stock!)} title={t("One label per unit in stock")}>
                        × {o.stock}
                      </Button>
                    )}
                    <Button size="sm" variant="secondary" onClick={() => add(o)}>
                      <Plus className="size-3.5" aria-hidden />{" "}{t("Add")}
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </div>
          {results.data && results.data.items.length > 0 && (
            <div className="mt-2 flex justify-end">
              <Button size="sm" variant="ghost" onClick={() => results.data.items.forEach((o) => add(o))}>
                {t("Add all {length} shown", { length: results.data.items.length })}
              </Button>
            </div>
          )}
        </Card>

        <Card className="p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold">{t("2. Labels to print")}</h2>
            {queue.length > 0 && (
              <Button size="sm" variant="ghost" onClick={() => setQueue([])}>
                {t("Clear")}
              </Button>
            )}
          </div>
          {queue.length === 0 ? (
            <p className="text-sm text-muted">{t("Add entries above.")}</p>
          ) : (
            <ul className="divide-y divide-border text-sm">
              {queue.map((l) => (
                <li key={l.offering.id} className="flex items-center gap-2 py-2">
                  <span className="min-w-0 flex-1 truncate">{l.offering.name}</span>
                  <button
                    className="grid size-8 shrink-0 place-items-center rounded-md border border-border hover:bg-surface-2"
                    onClick={() => setCopies(l.offering.id, l.copies - 1)}
                    aria-label={l.copies === 1 ? t("Remove {name}", { name: l.offering.name }) : t("One label less for {name}", { name: l.offering.name })}
                  >
                    {l.copies === 1 ? <Trash2 className="size-4" /> : <Minus className="size-4" />}
                  </button>
                  <div className="w-16 shrink-0">
                    <Input
                      inputMode="numeric"
                      aria-label={t("Labels for {name}", { name: l.offering.name })}
                      value={l.copies}
                      onChange={(e) => setCopies(l.offering.id, Number(e.target.value.replace(/\D/g, "")) || 0)}
                      className="h-8 text-center tabular-nums"
                    />
                  </div>
                  <button
                    className="grid size-8 shrink-0 place-items-center rounded-md border border-border hover:bg-surface-2"
                    onClick={() => setCopies(l.offering.id, l.copies + 1)}
                    aria-label={t("One more label for {name}", { name: l.offering.name })}
                  >
                    <Plus className="size-4" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card className="p-4">
          <h2 className="mb-3 text-sm font-semibold">{t("3. Label layout")}</h2>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5 text-sm">
              <span className="font-medium">{t("Size")}</span>
              <Select value={options.template} onChange={(e) => set("template", e.target.value as TemplateKey)}>
                {Object.entries(TEMPLATES).map(([k, v]) => (
                  <option key={k} value={k}>
                    {t(v.label)}
                  </option>
                ))}
              </Select>
            </label>
            <label className="flex flex-col gap-1.5 text-sm">
              <span className="font-medium">{t("Code")}</span>
              <Select value={options.code} onChange={(e) => set("code", e.target.value as CodeKind)}>
                <option value="barcode">{t("Barcode")}</option>
                <option value="qr">{t("QR code")}</option>
                <option value="none">{t("None")}</option>
              </Select>
            </label>
          </div>
          <div className="mt-3 flex flex-wrap gap-x-5 gap-y-2 text-sm">
            {(
              [
                ["name", msg("Name")],
                ["nameKm", msg("Khmer name")],
                ["price", msg("Price")],
                ["unit", msg("Unit (/ pcs)")],
                ["sku", msg("Code (SKU)")],
                ["description", msg("Description")],
                ["store", msg("Store name")],
              ] as const
            ).map(([k, l]) => (
              <label key={k} className="flex items-center gap-2">
                <input type="checkbox" checked={options[k]} onChange={(e) => set(k, e.target.checked)} />
                {t(l)}
              </label>
            ))}
            {exclusive && options.price && (
              <label className="flex items-center gap-2">
                <input type="checkbox" checked={options.withVat} onChange={(e) => set("withVat", e.target.checked)} />
                {t("Price with {taxLabel} added", { taxLabel: store.data.taxLabel })}
              </label>
            )}
          </div>
        </Card>
      </div>

      <div className="flex min-w-0 flex-col gap-3">
        <div className="flex items-center justify-between print:hidden">
          <h2 className="text-sm font-semibold">
            {labels.length === 1 ? t("Preview · 1 label") : t("Preview · {n} labels", { n: labels.length })}
            {x.sheet && labels.length > 0 && ` · ${t("{n} sheet(s)", { n: Math.ceil(labels.length / (x.sheet.cols * x.sheet.rows)) })}`}
          </h2>
          <div className="flex gap-2">
            <Button variant="secondary" onClick={() => setPreviewing(true)} disabled={labels.length === 0}>
              <Eye className="size-4" aria-hidden />{" "}{t("Print preview")}
            </Button>
            <Button onClick={() => window.print()} disabled={labels.length === 0}>
              <Printer className="size-4" aria-hidden />{" "}{t("Print")}
            </Button>
          </div>
        </div>
        <p className="text-xs text-muted print:hidden">
          {t("Shown at true size. In the print dialog choose the label printer (or A4 paper), set margins to none and scale to 100%.")}
        </p>
        <LabelSheet labels={labels} options={options} store={store.data} />
      </div>
      {previewing && <PrintPreview labels={labels} options={options} store={store.data} onClose={() => setPreviewing(false)} />}
    </div>
  );
}

/** Labels split into printed pages: one label per page on rolls, a sheet of labels on A4. */
function paginate(labels: Offering[], template: TemplateKey): Offering[][] {
  const x = TEMPLATES[template];
  const per = x.sheet ? x.sheet.cols * x.sheet.rows : 1;
  const pages: Offering[][] = [];
  for (let i = 0; i < labels.length; i += per) pages.push(labels.slice(i, i + per));
  return pages;
}

/** One printed page, at its real size in mm. */
function PrintPage({ labels, options, store }: { labels: Offering[]; options: Options; store: StoreSettings }) {
  const x = TEMPLATES[options.template];
  if (!x.sheet) {
    return <Label offering={labels[0]} options={options} store={store} w={x.w} h={x.h} />;
  }
  return (
    <div
      className="label-page grid content-start bg-white"
      style={{ width: "210mm", height: "297mm", gridTemplateColumns: `repeat(${x.sheet.cols}, ${x.w}mm)`, gridAutoRows: `${x.h}mm` }}
    >
      {labels.map((o, i) => (
        <Label key={i} offering={o} options={options} store={store} w={x.w} h={x.h} />
      ))}
    </div>
  );
}

/** What prints (hidden on screen except as the inline preview): every page, each on its own sheet of paper. */
function LabelSheet({ labels, options, store }: { labels: Offering[]; options: Options; store: StoreSettings }) {
  const t = useT();
  const x = TEMPLATES[options.template];
  const pages = paginate(labels, options.template);
  const pageCss = `@page { size: ${x.sheet ? "A4" : `${x.w}mm ${x.h}mm`}; margin: 0; }`;
  if (labels.length === 0) {
    return <p className="rounded-lg border border-dashed border-border p-8 text-center text-sm text-muted print:hidden">{t("No labels yet.")}</p>;
  }
  return (
    <div className="max-h-[32rem] overflow-auto rounded-lg bg-surface-2 p-4 print:max-h-none print:overflow-visible print:bg-white print:p-0">
      {/* Overrides the receipt page size while this page is open; each label or sheet is one page. */}
      <style>{`@media print { ${pageCss} .print-area.label-sheet { width: auto; position: absolute; inset: 0 auto auto 0; }
        .label-sheet > .print-page { break-after: page; } .label-sheet > .print-page:last-child { break-after: auto; } }`}</style>
      <div className="print-area label-sheet flex flex-wrap gap-2 print:block">
        {pages.map((p, i) => (
          <div key={i} className="print-page">
            <PrintPage labels={p} options={options} store={store} />
          </div>
        ))}
      </div>
    </div>
  );
}

const ZOOMS = [0.5, 0.75, 1, 1.5, 2];

/**
 * Print preview: the pages exactly as they will print, on paper, numbered, with zoom. Printing from
 * here prints the same pages.
 */
function PrintPreview({ labels, options, store, onClose }: { labels: Offering[]; options: Options; store: StoreSettings; onClose: () => void }) {
  const t = useT();
  const x = TEMPLATES[options.template];
  const pages = paginate(labels, options.template);
  const [zoom, setZoom] = useState(x.sheet ? 0.75 : 1.5);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  const paper = x.sheet ? "A4 (210 × 297 mm)" : `${x.w} × ${x.h} mm roll`;
  return (
    <div role="dialog" aria-modal="true" aria-label={t("Print preview")} className="fixed inset-0 z-50 flex flex-col bg-neutral-800 print:hidden">
      <div className="flex flex-wrap items-center gap-3 bg-surface px-4 py-3 shadow">
        <h2 className="font-semibold">{t("Print preview")}</h2>
        <span className="text-sm text-muted">
          {t("{labels} labels · {pages} pages · {paper}", { labels: labels.length, pages: pages.length, paper })}
        </span>
        <div className="ml-auto flex items-center gap-1" role="group" aria-label={t("Zoom")}>
          <Button size="sm" variant="ghost" onClick={() => setZoom(ZOOMS[Math.max(0, ZOOMS.indexOf(zoom) - 1)])} aria-label={t("Zoom out")} disabled={zoom === ZOOMS[0]}>
            <ZoomOut className="size-4" />
          </Button>
          <span className="w-12 text-center text-sm tabular-nums">{Math.round(zoom * 100)}%</span>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => setZoom(ZOOMS[Math.min(ZOOMS.length - 1, ZOOMS.indexOf(zoom) + 1)])}
            aria-label={t("Zoom in")}
            disabled={zoom === ZOOMS[ZOOMS.length - 1]}
          >
            <ZoomIn className="size-4" />
          </Button>
        </div>
        <Button variant="secondary" onClick={onClose}>
          {t("Close")}
        </Button>
        <Button onClick={() => window.print()}>
          <Printer className="size-4" aria-hidden />{" "}{t("Print")}
        </Button>
      </div>
      <p className="bg-surface-2 px-4 py-1.5 text-xs text-muted">
        {x.sheet
          ? t("In the print dialog choose A4 paper, margins none, scale 100%.")
          : t("In the print dialog choose the label printer with {w} × {h} mm labels, margins none, scale 100%.", { w: x.w, h: x.h })}
      </p>
      <div className="flex-1 overflow-auto p-6">
        <div className={cx("mx-auto flex gap-6", x.sheet ? "flex-col items-center" : "flex-wrap justify-center")}>
          {pages.map((p, i) => (
            <figure key={i} className="flex flex-col items-center gap-1">
              <div
                className="bg-white shadow-lg"
                style={{
                  width: `calc(${x.sheet ? 210 : x.w}mm * ${zoom})`,
                  height: `calc(${x.sheet ? 297 : x.h}mm * ${zoom})`,
                  overflow: "hidden",
                }}
              >
                <div style={{ transform: `scale(${zoom})`, transformOrigin: "top left", width: `${x.sheet ? 210 : x.w}mm` }}>
                  <PrintPage labels={p} options={options} store={store} />
                </div>
              </div>
              <figcaption className="text-xs text-white/80">
                {t("Page {i} of {length}", { i: i + 1, length: pages.length })}
              </figcaption>
            </figure>
          ))}
        </div>
      </div>
    </div>
  );
}

function Label({ offering: o, options, store, w, h }: { offering: Offering; options: Options; store: StoreSettings; w: number; h: number }) {
  const t = useT();
  const key = taxKeyFor(o, store);
  const price = options.withVat && !key.included && key.rate > 0 ? Math.round(o.price * (1 + key.rate) * 100) / 100 : o.price;
  const per = o.pricing === "HOURLY" ? " / h" : options.unit && o.unit ? ` / ${o.unit}` : "";
  const code = o.barcode || o.sku;
  const small = h < 28;
  const codeH = options.code === "none" ? 0 : Math.max(8, h * 0.38);
  return (
    <div
      className="label flex flex-col overflow-hidden bg-white text-black ring-1 ring-black/10 print:ring-0"
      style={{ width: `${w}mm`, height: `${h}mm`, padding: "1.5mm 2mm", fontSize: small ? "6.5pt" : "8pt", lineHeight: 1.15 }}
    >
      {options.store && <div className="truncate text-center font-semibold" style={{ fontSize: "6pt" }}>{store.name}</div>}
      {options.name && <div className="line-clamp-2 font-semibold">{o.name}</div>}
      {options.nameKm && o.nameKm && (
        <div lang="km" className="truncate">
          {o.nameKm}
        </div>
      )}
      {options.description && o.description && (
        <div className={cx(small ? "line-clamp-1" : "line-clamp-2", "text-neutral-700")} style={{ fontSize: small ? "5.5pt" : "6.5pt" }}>
          {t(o.description)}
        </div>
      )}
      <div className="mt-auto flex items-end justify-between gap-1">
        {options.code === "qr" && <QRCodeSVG value={code} size={Math.round(codeH * 3.78)} level="M" marginSize={0} style={{ width: `${codeH}mm`, height: `${codeH}mm` }} />}
        {options.code === "barcode" && <Barcode value={code} heightMm={codeH} />}
        <div className="ml-auto text-right">
          {options.price && (
            <div className="font-bold whitespace-nowrap" style={{ fontSize: small ? "9pt" : "12pt" }}>
              {money(price, store.currency)}
              <span style={{ fontSize: "6pt", fontWeight: 400 }}>{per}</span>
            </div>
          )}
          {options.price && key.rate > 0 && (
            <div style={{ fontSize: "5.5pt" }}>
              {key.included || options.withVat ? t("incl. {taxLabel}", { taxLabel: store.taxLabel }) : `+ ${store.taxLabel}`}
            </div>
          )}
          {options.sku && (
            <div className="font-mono whitespace-nowrap" style={{ fontSize: "6pt" }}>
              {o.sku}
              {options.code !== "barcode" && o.barcode && o.barcode !== o.sku && ` · ${o.barcode}`}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/** EAN-13 / EAN-8 / UPC-A when the code is a valid one of those, otherwise Code 128. */
function formatFor(code: string) {
  const digits = /^\d+$/.test(code);
  const check = (s: string) => {
    const nums = s.split("").map(Number);
    const last = nums.pop()!;
    const sum = nums.reverse().reduce((n, d, i) => n + d * (i % 2 === 0 ? 3 : 1), 0);
    return (10 - (sum % 10)) % 10 === last;
  };
  if (digits && code.length === 13 && check(code)) return "EAN13";
  if (digits && code.length === 8 && check(code)) return "EAN8";
  if (digits && code.length === 12 && check(code)) return "UPC";
  return "CODE128";
}

function Barcode({ value, heightMm }: { value: string; heightMm: number }) {
  const t = useT();
  const ref = useRef<SVGSVGElement>(null);
  useEffect(() => {
    if (!ref.current) return;
    const opts = { height: Math.round(heightMm * 3.78 * 0.72), width: 1.4, margin: 0, fontSize: 10, textMargin: 0, displayValue: true };
    try {
      JsBarcode(ref.current, value, { ...opts, format: formatFor(value) });
    } catch {
      try {
        JsBarcode(ref.current, value, { ...opts, format: "CODE128" });
      } catch {
        // a code that can't be drawn; leave it blank
      }
    }
  }, [value, heightMm]);
  return <svg ref={ref} style={{ height: `${heightMm}mm`, maxWidth: "62%" }} preserveAspectRatio="xMinYMax meet" aria-label={t("Barcode {value}", { value })} />;
}
