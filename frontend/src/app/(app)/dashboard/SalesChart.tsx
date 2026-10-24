"use client";

import { useState } from "react";

import { money, shortDate } from "@/lib/format";
import { useT } from "@/lib/i18n";

interface Day {
  date: string;
  sales: number;
  orders: number;
}

/** Daily sales as bars: one series, so no legend; hover or focus a bar for its values. */
export function SalesChart({ days, currency }: { days: Day[]; currency: string }) {
  const t = useT();
  const [active, setActive] = useState<number | null>(null);
  const [showTable, setShowTable] = useState(false);

  const max = Math.max(...days.map((d) => d.sales), 0);
  const top = niceCeil(max);
  const ticks = [0, top / 2, top];
  const labelEvery = Math.ceil(days.length / 8);

  return (
    <div>
      <div className="mb-3 flex justify-end">
        <button onClick={() => setShowTable((s) => !s)} className="text-xs text-primary hover:underline">
          {showTable ? t("Show chart") : t("Show as table")}
        </button>
      </div>
      {showTable ? (
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-muted">
            <tr>
              <th className="py-1 font-medium">{t("Day")}</th>
              <th className="py-1 text-right font-medium">{t("Orders")}</th>
              <th className="py-1 text-right font-medium">{t("Sales")}</th>
            </tr>
          </thead>
          <tbody>
            {days.map((d) => (
              <tr key={d.date} className="border-t border-border">
                <td className="py-1">{shortDate(d.date)}</td>
                <td className="py-1 text-right tabular-nums">{d.orders}</td>
                <td className="py-1 text-right tabular-nums">{money(d.sales, currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="flex gap-2">
          {/* y axis */}
          <div className="relative h-48 w-14 shrink-0 text-right text-[11px] text-muted tabular-nums">
            {ticks.map((x) => (
              <span key={x} className="absolute right-0 -translate-y-1/2" style={{ bottom: `${top ? (x / top) * 100 : 0}%` }}>
                {money(x, currency).replace(/\.00$/, "")}
              </span>
            ))}
          </div>
          <div className="min-w-0 flex-1">
            <div className="relative h-48" onMouseLeave={() => setActive(null)}>
              {ticks.map((entry) => (
                <div
                  key={entry}
                  className="absolute inset-x-0 border-t border-chart-grid"
                  style={{ bottom: `${top ? (entry / top) * 100 : 0}%` }}
                  aria-hidden
                />
              ))}
              <div className="absolute inset-0 flex items-end gap-0.5">
                {days.map((d, i) => (
                  <button
                    key={d.date}
                    className="group relative flex h-full flex-1 items-end justify-center rounded-sm focus-visible:outline-2 focus-visible:outline-primary"
                    onMouseEnter={() => setActive(i)}
                    onFocus={() => setActive(i)}
                    onBlur={() => setActive(null)}
                    aria-label={t("{date}: {sales} from {orders} orders", { date: shortDate(d.date), sales: money(d.sales, currency), orders: d.orders })}
                  >
                    <span
                      className={`block w-full max-w-10 rounded-t bg-chart-1 transition-opacity ${active !== null && active !== i ? "opacity-50" : ""}`}
                      style={{ height: d.sales > 0 && top ? `max(2px, ${(d.sales / top) * 100}%)` : 0 }}
                    />
                  </button>
                ))}
              </div>
              {active !== null && (
                <div
                  role="tooltip"
                  className="pointer-events-none absolute z-10 -translate-x-1/2 rounded-lg border border-border bg-surface px-3 py-2 text-xs shadow-md"
                  style={{
                    left: `${((active + 0.5) / days.length) * 100}%`,
                    bottom: `calc(${top ? (Math.max(0, days[active].sales) / top) * 100 : 0}% + 8px)`,
                  }}
                >
                  <div className="font-medium">{shortDate(days[active].date)}</div>
                  <div className="tabular-nums">{money(days[active].sales, currency)}</div>
                  <div className="text-muted tabular-nums">
                    {days[active].orders === 1 ? t("1 order") : t("{n} orders", { n: days[active].orders })}
                  </div>
                </div>
              )}
            </div>
            <div className="mt-1 flex gap-0.5 text-[11px] text-muted">
              {days.map((d, i) => (
                <span key={d.date} className="flex-1 text-center whitespace-nowrap">
                  {i % labelEvery === 0 || i === days.length - 1 ? shortDate(d.date) : ""}
                </span>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/** Rounds up to 1, 2 or 5 times a power of ten so the axis has round numbers. */
function niceCeil(value: number) {
  if (value <= 0) return 10;
  const power = 10 ** Math.floor(Math.log10(value));
  const step = [1, 2, 5, 10].find((s) => s * power >= value)!;
  return step * power;
}
