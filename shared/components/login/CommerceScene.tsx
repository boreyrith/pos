import { Baby, Check, Nfc, ScanBarcode, Shirt, ShoppingBag, TrendingUp } from "lucide-react";

/**
 * The sign-in page's 3D illustration: a till ringing up a sale, the customer paying by card or QR,
 * coins and parcels, and the sale flowing into a chart. CSS 3D only (styles in login.css), and
 * decoration only, so it is hidden from screen readers and uses no words that need translating.
 */
export function CommerceScene() {
  return (
    <div aria-hidden className="scene pointer-events-none select-none">
      <div className="scene-world">
        <div className="scene-floor" />

        {/* Sales chart, furthest back. */}
        <div className="float glass left-[286px] top-0 w-[150px] rounded-2xl p-3" style={vars({ z: -70, bob: 8, phase: -2 })}>
          <div className="flex items-center justify-between text-[11px] font-semibold">
            <TrendingUp className="size-3.5 text-emerald-300" />
            <span className="text-emerald-300">+18%</span>
          </div>
          <div className="mt-2 flex h-14 items-end gap-1.5">
            {BARS.map(([from, to], i) => (
              <div
                key={i}
                className="bar h-full flex-1 rounded-t-[3px] bg-linear-to-t from-sky-500/70 to-sky-300"
                style={{ "--from": from, "--to": to, "--delay": `${-i * 0.7}s` } as React.CSSProperties}
              />
            ))}
          </div>
        </div>

        {/* Shopping bag. */}
        <div className="float left-[24px] top-[40px]" style={vars({ z: 40, bob: 6.5, phase: -1, tilt: -4 })}>
          <svg width="74" height="84" viewBox="0 0 74 84">
            <defs>
              <linearGradient id="bag" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stopColor="#ff8fb1" />
                <stop offset="1" stopColor="#f0567f" />
              </linearGradient>
            </defs>
            <path d="M24 26v-6a13 13 0 0 1 26 0v6" fill="none" stroke="#ffd1df" strokeWidth="4" strokeLinecap="round" />
            <path d="M8 24h58l-4 54a5 5 0 0 1-5 5H17a5 5 0 0 1-5-5z" fill="url(#bag)" />
            <path d="M8 24h58l-1 10H9z" fill="#fff" opacity="0.18" />
            <circle cx="37" cy="52" r="9" fill="#fff" opacity="0.9" />
            <path d="M33 52l3 3 5-6" fill="none" stroke="#f0567f" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </div>

        {/* The till ringing up a sale, with the receipt printing underneath. */}
        <div className="float glass left-[120px] top-[70px] w-[230px] rounded-[20px] p-4" style={vars({ z: 0, bob: 7.5 })}>
          <div className="flex items-center justify-between">
            <div className="flex gap-1">
              <span className="size-2 rounded-full bg-white/30" />
              <span className="size-2 rounded-full bg-white/20" />
              <span className="size-2 rounded-full bg-white/15" />
            </div>
            <ScanBarcode className="size-4 text-sky-300" />
          </div>
          <div className="mt-3 flex flex-col gap-2">
            {LINES.map(({ Icon, color, width, price }, i) => (
              <div key={i} className="cart-line flex items-center gap-2.5" style={{ "--delay": `${i * 0.5}s` } as React.CSSProperties}>
                <span className={`grid size-7 place-items-center rounded-lg ${color}`}>
                  <Icon className="size-3.5 text-white" />
                </span>
                <span className="flex flex-1 flex-col gap-1">
                  <span className="h-1.5 rounded-full bg-white/50" style={{ width }} />
                  <span className="h-1.5 w-8 rounded-full bg-white/20" />
                </span>
                <span className="text-[11px] font-semibold tabular-nums text-white/90">{price}</span>
              </div>
            ))}
          </div>
          <div className="mt-3 flex items-center justify-between border-t border-white/15 pt-2.5">
            <span className="h-1.5 w-10 rounded-full bg-white/35" />
            <span className="text-[15px] font-semibold tabular-nums">$48.90</span>
          </div>
          <div className="absolute top-full left-8 h-[92px] w-[92px] overflow-hidden">
            <div className="receipt-paper flex h-[84px] flex-col gap-1.5 bg-white px-2.5 pt-2.5 [mask-image:linear-gradient(#000_calc(100%-6px),transparent_calc(100%-6px)),conic-gradient(from_135deg_at_50%_100%,#000_90deg,transparent_0)] [mask-position:0_0,0_100%] [mask-repeat:no-repeat,repeat-x] [mask-size:100%_100%,8px_6px]">
              <span className="h-1 w-10 rounded-full bg-slate-300" />
              <span className="h-1 w-14 rounded-full bg-slate-200" />
              <span className="h-1 w-12 rounded-full bg-slate-200" />
              <span className="mt-0.5 flex justify-between text-[8px] font-bold text-slate-700">
                <span>=</span>
                <span>$48.90</span>
              </span>
            </div>
          </div>
        </div>

        {/* Payment card, tapped on the till. */}
        <div className="float left-[6px] top-[205px] w-[152px] rounded-2xl" style={vars({ z: 70, bob: 6, phase: -3, tilt: -6 })}>
          <div className="relative h-[94px] overflow-hidden rounded-2xl bg-linear-to-br from-indigo-400 via-violet-500 to-fuchsia-500 p-3 shadow-[0_24px_40px_-14px_rgb(0_0_0/0.6)]">
            <div className="absolute -top-8 -right-6 size-24 rounded-full bg-white/15" />
            <div className="flex items-center justify-between">
              <span className="h-5 w-7 rounded-[5px] bg-linear-to-br from-amber-200 to-amber-400" />
              <Nfc className="size-4 text-white/85" />
            </div>
            <div className="mt-5 flex gap-2 text-[10px] font-medium tracking-[0.18em] text-white/85">
              <span>••••</span>
              <span>••••</span>
              <span>4821</span>
            </div>
          </div>
        </div>

        {/* QR payment: scanned, then confirmed. */}
        <div className="float left-[378px] top-[150px] w-[116px] rounded-2xl bg-white p-2.5 shadow-[0_28px_48px_-16px_rgb(0_0_0/0.65)]" style={vars({ z: 100, bob: 7, phase: -4, tilt: 3 })}>
          <div className="relative overflow-hidden rounded-lg">
            <QrPattern />
            <div className="qr-scan absolute inset-x-0 top-0 h-0.5 bg-rose-500 shadow-[0_0_8px_2px_rgb(244_63_94/0.7)]" />
            <div className="qr-paid absolute inset-0 grid place-items-center bg-white/75">
              <span className="grid size-10 place-items-center rounded-full bg-emerald-500 shadow-lg shadow-emerald-500/40">
                <Check className="size-6 text-white" strokeWidth={3} />
              </span>
            </div>
          </div>
          <div className="mt-2 text-center text-[11px] font-semibold tabular-nums text-slate-800">៛200,490</div>
        </div>

        {/* Coins in dollars and riel. */}
        {COINS.map((c, i) => (
          <div
            key={i}
            className="coin grid size-8 place-items-center rounded-full bg-linear-to-br from-amber-200 via-amber-400 to-amber-600 text-[13px] font-bold text-amber-900 shadow-[inset_0_0_0_2px_rgb(255_255_255/0.35),0_8px_16px_-6px_rgb(0_0_0/0.5)]"
            style={{ left: c.x, top: c.y, "--z": `${c.z}px`, "--phase": `${c.phase}s` } as React.CSSProperties}
          >
            {c.symbol}
          </div>
        ))}

        {/* Parcels from the stockroom. */}
        {CUBES.map((c, i) => (
          <div key={i} className="cube" style={{ left: c.x, top: c.y, "--z": `${c.z}px`, "--phase": `${c.phase}s`, "--face": c.color } as React.CSSProperties}>
            <i /><i /><i /><i /><i /><i />
          </div>
        ))}

        {/* Data flowing between them. */}
        {FLOWS.map((f, i) => (
          <span
            key={i}
            className="data-dot top-0 left-0"
            style={{ offsetPath: `path("${f.path}")`, "--delay": `${f.delay}s`, "--dur": `${f.dur}s` } as React.CSSProperties}
          />
        ))}
      </div>
    </div>
  );
}

function vars({ z, bob, phase = 0, tilt = 0 }: { z: number; bob: number; phase?: number; tilt?: number }) {
  return { "--z": `${z}px`, "--bob": `${bob}s`, "--phase": `${phase}s`, "--tilt": `${tilt}deg` } as React.CSSProperties;
}

const LINES = [
  { Icon: Shirt, color: "bg-sky-500", width: "70%", price: "$18.00" },
  { Icon: Baby, color: "bg-pink-500", width: "55%", price: "$9.90" },
  { Icon: ShoppingBag, color: "bg-amber-500", width: "64%", price: "$21.00" },
];

/** Each bar's height range, as a fraction of the chart. */
const BARS: [number, number][] = [
  [0.35, 0.55],
  [0.5, 0.4],
  [0.45, 0.7],
  [0.6, 0.5],
  [0.55, 0.85],
  [0.75, 1],
];

const COINS = [
  { symbol: "$", x: 268, y: 296, z: 60, phase: 0 },
  { symbol: "៛", x: 448, y: 72, z: 20, phase: -2.5 },
  { symbol: "$", x: 196, y: 14, z: -40, phase: -4 },
];

const CUBES = [
  { x: 404, y: 300, z: 30, phase: 0, color: "#5b8def" },
  { x: 78, y: 330, z: -10, phase: -6, color: "#f59e0b" },
];

/** Paths in the scene's own coordinates: card to till, till to QR payment, till up to the chart. */
const FLOWS = [
  { path: "M 120 250 C 140 230, 140 200, 165 175", delay: 0, dur: 3.2 },
  { path: "M 350 175 C 364 174, 372 186, 382 204", delay: 1.4, dur: 2.8 },
  { path: "M 300 72 C 310 40, 330 40, 360 52", delay: 0.6, dur: 3.4 },
  { path: "M 120 250 C 140 230, 140 200, 165 175", delay: 1.9, dur: 3.2 },
  { path: "M 300 72 C 310 40, 330 40, 360 52", delay: 2.3, dur: 3.4 },
];

/** A QR-code look-alike: three finder squares and a fixed pseudo-random fill. Not a scannable code. */
export function QrPattern({ className = "size-24" }: { className?: string }) {
  const n = 21;
  const cells: [number, number][] = [];
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      const inFinder = (x < 8 && y < 8) || (x >= n - 8 && y < 8) || (x < 8 && y >= n - 8);
      if (!inFinder && ((x * 7 + y * 13 + x * y * 3) % 5 < 2)) cells.push([x, y]);
    }
  }
  const finder = (x: number, y: number) => (
    <g key={`${x}-${y}`}>
      <rect x={x} y={y} width="7" height="7" fill="#0f172a" />
      <rect x={x + 1} y={y + 1} width="5" height="5" fill="#fff" />
      <rect x={x + 2} y={y + 2} width="3" height="3" fill="#0f172a" />
    </g>
  );
  return (
    <svg viewBox={`0 0 ${n} ${n}`} className={`block ${className}`} shapeRendering="crispEdges">
      <rect width={n} height={n} fill="#fff" />
      {cells.map(([x, y]) => (
        <rect key={`${x}-${y}`} x={x} y={y} width="1" height="1" fill="#0f172a" />
      ))}
      {finder(0, 0)}
      {finder(n - 7, 0)}
      {finder(0, n - 7)}
    </svg>
  );
}
