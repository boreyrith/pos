"use client";

import { useEffect, useId, useRef, useState } from "react";

import { cx } from "@/components/ui";
import { money, shortDate } from "@/lib/format";
import { msg, useT } from "@/lib/i18n";

export interface Day {
  date: string;
  sales: number;
  orders: number;
  profit: number;
}

export type Measure = "sales" | "profit" | "orders";

export const MEASURES: { key: Measure; label: string }[] = [
  { key: "sales", label: msg("Sales") },
  { key: "profit", label: msg("Profit") },
  { key: "orders", label: msg("Orders") },
];

const H = 240;
const PAD = { top: 12, right: 12, bottom: 28, left: 56 };

/**
 * Daily sales, profit or orders as one area series (one measure at a time: they don't share a scale).
 * Hover, touch or the arrow keys move a crosshair with the day's figures; "Show as table" lists them.
 * Fewer than three days draw as bars, since a line needs a run of points to mean anything.
 */
export function TrendChart({ days, currency, measure }: { days: Day[]; currency: string; measure: Measure }) {
  const t = useT();
  const gradient = useId();
  const box = useRef<HTMLDivElement>(null);
  const width = useWidth(box);
  const [active, setActive] = useState<number | null>(null);
  const [showTable, setShowTable] = useState(false);

  const values = days.map((d) => d[measure]);
  const format = (v: number) => (measure === "orders" ? String(Math.round(v)) : money(v, currency));
  const axis = (v: number) => (measure === "orders" ? String(v) : money(v, currency).replace(/\.00$/, ""));
  const [lo, hi] = niceDomain(Math.min(0, ...values), Math.max(0, ...values));
  const ticks = lo < 0 ? [lo, 0, hi] : [0, hi / 2, hi];
  const plotW = Math.max(0, width - PAD.left - PAD.right);
  const plotH = H - PAD.top - PAD.bottom;
  const y = (v: number) => PAD.top + (1 - (v - lo) / (hi - lo)) * plotH;
  const bars = days.length < 3;
  const x = (i: number) =>
    bars ? PAD.left + ((i + 0.5) / days.length) * plotW : PAD.left + (days.length === 1 ? plotW / 2 : (i / (days.length - 1)) * plotW);
  const points = values.map((v, i) => [x(i), y(v)] as const);
  const line = smoothPath(points);
  const area = points.length ? `${line} L${points.at(-1)![0]},${y(Math.max(lo, 0))} L${points[0][0]},${y(Math.max(lo, 0))} Z` : "";
  const labelEvery = Math.max(1, Math.ceil(days.length / Math.max(2, Math.floor(plotW / 72))));

  function pick(clientX: number) {
    const rect = box.current?.getBoundingClientRect();
    if (!rect || !days.length) return;
    const rel = clientX - rect.left - PAD.left;
    const i = bars ? Math.floor((rel / plotW) * days.length) : Math.round((rel / plotW) * (days.length - 1));
    setActive(Math.min(days.length - 1, Math.max(0, i)));
  }

  function onKey(e: React.KeyboardEvent) {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight" && e.key !== "Home" && e.key !== "End") return;
    e.preventDefault();
    const last = days.length - 1;
    setActive((a) => {
      if (e.key === "Home") return 0;
      if (e.key === "End") return last;
      if (a === null) return last;
      return Math.min(last, Math.max(0, a + (e.key === "ArrowRight" ? 1 : -1)));
    });
  }

  const current = active !== null ? days[active] : null;

  return (
    <div>
      <div className="-mt-1 mb-2 flex justify-end">
        <button onClick={() => setShowTable((s) => !s)} className="rounded text-xs font-medium text-primary hover:underline">
          {showTable ? t("Show chart") : t("Show as table")}
        </button>
      </div>
      {showTable ? (
        <div className="max-h-[260px] overflow-y-auto">
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-surface text-left text-xs text-muted">
              <tr>
                <th className="py-1.5 font-medium">{t("Day")}</th>
                <th className="py-1.5 text-right font-medium">{t("Orders")}</th>
                <th className="py-1.5 text-right font-medium">{t("Sales")}</th>
                <th className="py-1.5 text-right font-medium">{t("Profit")}</th>
              </tr>
            </thead>
            <tbody>
              {days.map((d) => (
                <tr key={d.date} className="border-t border-border">
                  <td className="py-1.5">{shortDate(d.date)}</td>
                  <td className="py-1.5 text-right tabular-nums">{d.orders}</td>
                  <td className="py-1.5 text-right tabular-nums">{money(d.sales, currency)}</td>
                  <td className="py-1.5 text-right tabular-nums">{money(d.profit, currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div
          ref={box}
          tabIndex={0}
          role="group"
          aria-roledescription={t("chart")}
          aria-label={t("{measure} by day. Use the left and right arrow keys to move between days.", { measure: t(MEASURES.find((m) => m.key === measure)!.label) })}
          onKeyDown={onKey}
          onPointerMove={(e) => pick(e.clientX)}
          onPointerDown={(e) => pick(e.clientX)}
          onPointerLeave={(e) => e.pointerType === "mouse" && setActive(null)}
          onBlur={() => setActive(null)}
          className="relative touch-pan-y rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-primary"
          style={{ height: H }}
        >
          {width > 0 && (
            <svg width={width} height={H} className="block overflow-visible" aria-hidden>
              <defs>
                <linearGradient id={gradient} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stopColor="var(--chart-1)" stopOpacity="0.28" />
                  <stop offset="1" stopColor="var(--chart-1)" stopOpacity="0" />
                </linearGradient>
              </defs>
              {ticks.map((v) => (
                <g key={v}>
                  <line x1={PAD.left} x2={width - PAD.right} y1={y(v)} y2={y(v)} className={v === 0 ? "stroke-border" : "stroke-chart-grid"} strokeDasharray={v === 0 ? undefined : "3 4"} />
                  <text x={PAD.left - 10} y={y(v)} dy="0.32em" textAnchor="end" className="fill-muted text-[11px] tabular-nums">
                    {axis(v)}
                  </text>
                </g>
              ))}
              {days.map((d, i) =>
                i % labelEvery === 0 || i === days.length - 1 ? (
                  <text key={d.date} x={x(i)} y={H - 8} textAnchor={bars ? "middle" : i === 0 ? "start" : i === days.length - 1 ? "end" : "middle"} className="fill-muted text-[11px]">
                    {shortDate(d.date)}
                  </text>
                ) : null,
              )}
              {bars ? (
                days.map((d, i) => {
                  const w = Math.min(56, (plotW / days.length) * 0.5);
                  const top = y(Math.max(0, d[measure]));
                  const h = Math.abs(y(d[measure]) - y(0));
                  return (
                    <rect
                      key={`${measure}-${d.date}`}
                      x={x(i) - w / 2}
                      y={top}
                      width={w}
                      height={Math.max(h, d[measure] ? 2 : 0)}
                      rx={4}
                      className={cx("fill-chart-1 transition-opacity", active !== null && active !== i && "opacity-50")}
                    />
                  );
                })
              ) : (
                <g key={measure}>
                  <path d={area} fill={`url(#${gradient})`} className="dash-fade" />
                  <path d={line} pathLength={1} fill="none" stroke="var(--chart-1)" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="dash-draw" />
                </g>
              )}
              {current && active !== null && (
                <g className="pointer-events-none">
                  {!bars && <line x1={x(active)} x2={x(active)} y1={PAD.top} y2={H - PAD.bottom} className="stroke-muted" strokeOpacity={0.5} />}
                  {!bars && <circle cx={x(active)} cy={y(current[measure])} r={5} fill="var(--chart-1)" stroke="var(--surface)" strokeWidth={2} />}
                </g>
              )}
            </svg>
          )}
          {current && active !== null && (
            <div
              role="status"
              className="pointer-events-none absolute top-0 z-10 min-w-36 rounded-xl border border-border bg-surface/95 px-3 py-2 text-xs shadow-lg backdrop-blur"
              style={{
                left: Math.min(Math.max(x(active), PAD.left + 72), width - 84),
                transform: "translateX(-50%)",
              }}
            >
              <div className="font-semibold">{shortDate(current.date)}</div>
              <div className="mt-1 flex justify-between gap-4">
                <span className="text-muted">{t("Sales")}</span>
                <span className="tabular-nums">{money(current.sales, currency)}</span>
              </div>
              <div className="flex justify-between gap-4">
                <span className="text-muted">{t("Profit")}</span>
                <span className="tabular-nums">{money(current.profit, currency)}</span>
              </div>
              <div className="flex justify-between gap-4">
                <span className="text-muted">{t("Orders")}</span>
                <span className="tabular-nums">{current.orders}</span>
              </div>
            </div>
          )}
          <span className="sr-only" aria-live="polite">
            {current ? `${shortDate(current.date)}: ${format(current[measure])}` : ""}
          </span>
        </div>
      )}
    </div>
  );
}

/** A small trend line for a stat tile. Decoration: the figures it summarises are in the tile and the main chart. */
export function Sparkline({ values, className, tone = "var(--chart-1)" }: { values: number[]; className?: string; tone?: string }) {
  const id = useId();
  const box = useRef<HTMLDivElement>(null);
  // Drawn at its real size rather than stretched, so the line keeps an even width and draws in cleanly.
  const w = useWidth(box);
  const [h, setH] = useState(0);
  useEffect(() => setH(box.current?.clientHeight ?? 0), [w]);
  // A flat line of zeros says nothing: leave the space empty.
  if (values.length < 2 || values.every((v) => v === 0)) return <div ref={box} className={className} aria-hidden />;
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const pts = values.map((v, i) => [(i / (values.length - 1)) * w, hi === lo ? h / 2 : 3 + (1 - (v - lo) / (hi - lo)) * (h - 6)] as const);
  const line = smoothPath(pts);
  return (
    <div ref={box} className={className} aria-hidden>
      {w > 0 && h > 0 && (
        <svg width={w} height={h} className="block overflow-visible">
          <defs>
            <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor={tone} stopOpacity="0.3" />
              <stop offset="1" stopColor={tone} stopOpacity="0" />
            </linearGradient>
          </defs>
          <path d={`${line} L${w},${h} L0,${h} Z`} fill={`url(#${id})`} className="dash-fade" />
          <path d={line} pathLength={1} fill="none" stroke={tone} strokeWidth={2} strokeLinecap="round" className="dash-draw" />
        </svg>
      )}
    </div>
  );
}

/**
 * Counts a figure up to its value when it first shows, and from the old value to the new one when the
 * dashboard refreshes. Shows the value straight away when the user prefers less motion.
 */
export function useCountUp(value: number, duration = 900) {
  const [shown, setShown] = useState(0);
  const from = useRef(0);
  useEffect(() => {
    const start = from.current;
    if (start === value || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      from.current = value;
      setShown(value);
      return;
    }
    let frame = 0;
    const began = performance.now();
    const tick = (now: number) => {
      const p = Math.min(1, (now - began) / duration);
      const eased = 1 - (1 - p) ** 3;
      const v = start + (value - start) * eased;
      from.current = v;
      setShown(v);
      if (p < 1) frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [value, duration]);
  return shown;
}

function useWidth(ref: React.RefObject<HTMLElement | null>) {
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)));
    observer.observe(el);
    return () => observer.disconnect();
  }, [ref]);
  return width;
}

/** A smooth line through the points that never overshoots them (monotone cubic, Fritsch–Carlson). */
function smoothPath(points: readonly (readonly [number, number])[]) {
  const n = points.length;
  if (n === 0) return "";
  if (n < 3) return points.map(([px, py], i) => `${i ? "L" : "M"}${px},${py}`).join(" ");
  const dx = points.slice(1).map((p, i) => p[0] - points[i][0]);
  const slope = points.slice(1).map((p, i) => (p[1] - points[i][1]) / dx[i]);
  const tangent = points.map((_, i) => {
    if (i === 0) return slope[0];
    if (i === n - 1) return slope[n - 2];
    if (slope[i - 1] * slope[i] <= 0) return 0;
    return (3 * (dx[i - 1] + dx[i])) / ((2 * dx[i] + dx[i - 1]) / slope[i - 1] + (dx[i] + 2 * dx[i - 1]) / slope[i]);
  });
  let d = `M${points[0][0]},${points[0][1]}`;
  for (let i = 0; i < n - 1; i++) {
    const h = dx[i] / 3;
    d += ` C${points[i][0] + h},${points[i][1] + tangent[i] * h} ${points[i + 1][0] - h},${points[i + 1][1] - tangent[i + 1] * h} ${points[i + 1][0]},${points[i + 1][1]}`;
  }
  return d;
}

/** Rounds the range out to 1, 2 or 5 times a power of ten so the axis has round numbers. */
function niceDomain(min: number, max: number): [number, number] {
  const nice = (v: number) => {
    if (v <= 0) return 0;
    const power = 10 ** Math.floor(Math.log10(v));
    return [1, 2, 5, 10].find((s) => s * power >= v)! * power;
  };
  const hi = nice(max) || 10;
  const lo = min < 0 ? -nice(-min) : 0;
  return [lo, hi];
}
