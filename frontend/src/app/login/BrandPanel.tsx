"use client";

import { ChartColumn, Check, ShoppingBag, Wallet } from "lucide-react";

import { useT } from "@/lib/i18n";

import { CommerceScene, QrPattern } from "./CommerceScene";
import { StoreLogo } from "@/components/StoreLogo";

/** Data pulses on the backdrop grid: which grid line, how long one crossing takes, and when it starts. */
const PULSES = [
  { axis: "x", line: 3, dur: 7, delay: 0 },
  { axis: "x", line: 14, dur: 9, delay: 2.5 },
  { axis: "x", line: 22, dur: 8, delay: 5 },
  { axis: "y", line: 5, dur: 8, delay: 1.2 },
  { axis: "y", line: 17, dur: 10, delay: 3.8 },
] as const;

/** The left half of the sign-in page on wide screens: the store's logo, what the app does, and the 3D scene. */
export function BrandPanel() {
  const t = useT();
  return (
    <aside className="brand-panel relative isolate m-3 hidden flex-col overflow-hidden rounded-[28px] p-10 lg:flex xl:p-12">
      <div aria-hidden className="brand-grid pointer-events-none absolute inset-0 -z-10 overflow-hidden">
        {PULSES.map((p, i) => (
          <span
            key={i}
            className={p.axis === "x" ? "brand-pulse-x" : "brand-pulse-y"}
            // Line k of the grid runs through the dot centres at 16px + 32px * k.
            style={{
              [p.axis === "x" ? "top" : "left"]: `calc(16px + 32px * ${p.line})`,
              "--dur": `${p.dur}s`,
              "--delay": `${p.delay}s`,
            } as React.CSSProperties}
          />
        ))}
      </div>

      <StoreLogo className="size-12 rounded-[14px] shadow-lg ring-1 ring-white/15" />

      {/* The scene is centred out of the flow, so on a short screen it shrinks (login.css) instead of pushing the text down. */}
      <div className="relative min-h-0 flex-1">
        <div className="scene-fit absolute top-1/2 left-1/2 -translate-x-[calc(50%+1rem)] -translate-y-1/2">
          <CommerceScene />
        </div>
      </div>

      <div className="max-w-md">
        <h2 className="text-3xl leading-tight font-semibold tracking-tight text-balance xl:text-[34px]">
          {t("Sell, take payments and see every sale as it happens")}
        </h2>
        <p className="mt-3 text-[15px] text-white/65 [@media(max-height:680px)]:hidden">
          {t("Cash and KHQR payments, stock and reports for every branch, in one place.")}
        </p>
        <ul className="mt-6 flex flex-wrap gap-2 text-[13px] font-medium text-white/85">
          {[
            { Icon: ShoppingBag, label: t("Sell") },
            { Icon: Wallet, label: t("Payments") },
            { Icon: ChartColumn, label: t("Reports") },
          ].map(({ Icon, label }) => (
            <li key={label} className="flex items-center gap-1.5 rounded-full bg-white/8 px-3 py-1.5 ring-1 ring-white/12 backdrop-blur">
              <Icon className="size-3.5" aria-hidden />
              {label}
            </li>
          ))}
        </ul>
      </div>
    </aside>
  );
}

/**
 * Tablets and phones get this band instead of the brand panel: the same look, but flat. Its objects only
 * float up and down (transforms, no 3D, blur or moving paths), so it stays light on small devices.
 * Phones get the logo and three objects; tablets add the headline, two more objects and two data pulses.
 */
export function BrandHero({ children }: { children?: React.ReactNode }) {
  const t = useT();
  return (
    <header className="brand-panel relative isolate overflow-hidden px-5 pt-5 pb-8 md:px-8 md:pt-6 md:pb-28 lg:hidden">
      <div aria-hidden className="brand-grid pointer-events-none absolute inset-0 -z-10 overflow-hidden">
        <span className="brand-pulse-x" style={{ top: "calc(16px + 32px * 3)", "--dur": "8s" } as React.CSSProperties} />
        <span className="brand-pulse-y" style={{ left: "calc(16px + 32px * 6)", "--dur": "9s", "--delay": "2s" } as React.CSSProperties} />
      </div>

      <div className="flex items-center justify-between">
        <StoreLogo className="size-11 rounded-[13px] shadow-lg ring-1 ring-white/15" />
        {children}
      </div>

      <div aria-hidden className="pointer-events-none relative mt-2 h-24 md:absolute md:inset-y-0 md:right-0 md:mt-0 md:h-auto md:w-1/2">
        {/* QR payment, confirmed. */}
        <div className="lite-float absolute top-1 right-[6%] rounded-xl bg-white p-1.5 shadow-xl md:top-[92px] md:right-[18%] md:p-2" style={lite(6, 0, 4)}>
          <div className="relative overflow-hidden rounded-[3px]">
            <QrPattern className="size-12 md:size-16" />
            <div className="lite-scan" />
          </div>
          <span className="qr-paid absolute -right-2 -bottom-2 grid size-6 place-items-center rounded-full bg-emerald-500 shadow-lg ring-2 ring-white">
            <Check className="size-3.5 text-white" strokeWidth={3} />
          </span>
        </div>
        {/* Payment card. */}
        <div
          className="lite-float absolute top-8 left-[4%] h-[46px] w-[74px] rounded-[10px] bg-linear-to-br from-indigo-400 via-violet-500 to-fuchsia-500 p-2 shadow-xl md:top-[176px] md:left-[24%] md:h-[60px] md:w-[96px]"
          style={lite(7, -2, -8)}
        >
          <span className="block h-2.5 w-3.5 rounded-[3px] bg-linear-to-br from-amber-200 to-amber-400 md:h-3.5 md:w-5" />
        </div>
        {/* A dollar coin. */}
        <div
          className="lite-coin absolute top-2 left-[42%] grid size-7 place-items-center rounded-full bg-linear-to-br from-amber-200 via-amber-400 to-amber-600 text-xs font-bold text-amber-900 shadow-lg md:top-[64px] md:left-[36%] md:size-8"
          style={lite(5.5, -1, 0)}
        >
          $
        </div>
        {/* Tablets only: a sales chart and a riel coin. */}
        <div className="lite-float absolute top-[96px] left-[2%] hidden w-[110px] rounded-xl border border-white/15 bg-white/10 p-2.5 shadow-xl md:block" style={lite(8, -3, 3)}>
          <div className="flex h-10 items-end gap-1">
            {[0.4, 0.55, 0.45, 0.7, 0.85, 1].map((h, i) => (
              <span key={i} className="flex-1 rounded-t-[2px] bg-linear-to-t from-sky-500/70 to-sky-300" style={{ height: `${h * 100}%` }} />
            ))}
          </div>
        </div>
        <div
          className="lite-coin absolute top-[196px] right-[6%] hidden size-7 place-items-center rounded-full bg-linear-to-br from-amber-200 via-amber-400 to-amber-600 text-xs font-bold text-amber-900 shadow-lg md:grid"
          style={lite(6.5, -4, 0)}
        >
          ៛
        </div>
      </div>

      <div className="relative hidden max-w-[48%] md:mt-10 md:block">
        <h2 className="text-[26px] leading-tight font-semibold tracking-tight text-balance">
          {t("Sell, take payments and see every sale as it happens")}
        </h2>
        <p className="mt-2 text-sm text-white/65">{t("Cash and KHQR payments, stock and reports for every branch, in one place.")}</p>
      </div>
    </header>
  );
}

function lite(bob: number, phase: number, tilt: number) {
  return { "--bob": `${bob}s`, "--phase": `${phase}s`, rotate: `${tilt}deg` } as React.CSSProperties;
}
