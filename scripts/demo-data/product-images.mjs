#!/usr/bin/env node
/**
 * Draws a product picture for every sellable catalog entry of the demo kids store (flat
 * illustrations, colour-coded by kind, with the size on a badge) and uploads it through the API.
 * Pictures are square; badges stay inside the middle 4:3 band, which is what the sell screen's tiles show.
 * Uses sharp from the frontend's dependencies to turn the drawings into WebP.
 *
 *   node scripts/demo-data/product-images.mjs              # draw and upload (replaces existing pictures)
 *   node scripts/demo-data/product-images.mjs --out DIR    # only write the files to DIR to look at
 *   node scripts/demo-data/product-images.mjs --missing    # only entries without a picture
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const sharp = createRequire(join(here, "../../frontend/package.json"))("sharp");
const API = process.env.API_URL ?? "http://localhost:8081/api";
const args = process.argv.slice(2);
const outDir = args.includes("--out") ? args[args.indexOf("--out") + 1] : null;
const onlyMissing = args.includes("--missing");

// ------------------------------------------------------------------ drawing kit (200 x 200)

const INK = "#3b3355";
const stroke = `stroke="${INK}" stroke-width="3" stroke-linejoin="round" stroke-linecap="round"`;
const shade = (hex, f) => {
  const n = parseInt(hex.slice(1), 16);
  const c = [n >> 16, (n >> 8) & 255, n & 255].map((v) => Math.round(f < 0 ? v * (1 + f) : v + (255 - v) * f));
  return `#${c.map((v) => v.toString(16).padStart(2, "0")).join("")}`;
};
const dots = (color, pts) => pts.map(([x, y, r = 3]) => `<circle cx="${x}" cy="${y}" r="${r}" fill="${color}"/>`).join("");

const shapes = {
  bodysuit: (c, opt) => {
    const one = (dx, dy, col) => `<g transform="translate(${dx} ${dy})">
      <path d="M70 52 Q100 66 130 52 L156 66 L146 92 L134 88 L134 132 Q134 150 116 152 L110 160 L90 160 L84 152 Q66 150 66 132 L66 88 L54 92 L44 66 Z" fill="${col}" ${stroke}/>
      <path d="M86 56 Q100 70 114 56" fill="none" ${stroke}/>${dots(INK, [[100, 96, 2.5], [100, 110, 2.5]])}</g>`;
    return opt.pack ? one(14, -6, shade(c, 0.45)) + one(7, -3, shade(c, 0.25)) + one(0, 0, c) : one(0, 0, c);
  },
  sleepsuit: (c) => `<path d="M72 40 Q100 52 128 40 L152 54 L144 80 L132 76 L134 150 L122 168 L106 168 L102 128 L98 128 L94 168 L78 168 L66 150 L68 76 L56 80 L48 54 Z" fill="${c}" ${stroke}/>
    <path d="M100 48 L100 120" ${stroke}/>${dots("#fff8d6", [[84, 70, 4], [118, 96, 4], [86, 118, 3], [116, 140, 3], [82, 146, 3]])}
    <path d="M78 160 h16 M106 160 h16" ${stroke}/>`,
  hat: (c) => `<path d="M52 128 Q50 62 100 56 Q150 62 148 128 Z" fill="${c}" ${stroke}/>
    <rect x="46" y="122" width="108" height="26" rx="10" fill="${shade(c, -0.12)}" ${stroke}/>
    <path d="M70 70 v50 M86 62 v58 M100 58 v62 M114 62 v58 M130 70 v50" stroke="${shade(c, -0.12)}" stroke-width="3"/>
    <circle cx="100" cy="50" r="12" fill="${shade(c, 0.3)}" ${stroke}/>`,
  mittens: (c) => [0, 1].map((i) => `<g transform="translate(${i * 54 - 2} ${i * 10})">
    <path d="M52 150 L50 92 Q50 62 76 62 Q102 62 102 92 L104 150 Z" fill="${c}" ${stroke}/>
    <rect x="48" y="138" width="58" height="18" rx="6" fill="${shade(c, -0.15)}" ${stroke}/></g>`).join(""),
  blanket: (c) => `<rect x="44" y="92" width="120" height="70" rx="10" fill="${shade(c, -0.1)}" ${stroke}/>
    <rect x="36" y="64" width="128" height="60" rx="10" fill="${c}" ${stroke}/>
    ${dots("#ffffff", [[56, 80], [80, 96], [104, 80], [128, 96], [150, 80], [68, 110], [116, 110], [144, 110]])}
    <path d="M36 112 h128" stroke="${shade(c, -0.2)}" stroke-width="3"/>`,
  bib: (c) => `<path d="M72 64 Q60 64 56 80 Q44 120 64 150 Q80 170 100 170 Q120 170 136 150 Q156 120 144 80 Q140 64 128 64 Q120 92 100 92 Q80 92 72 64 Z" fill="${c}" ${stroke}/>
    <path d="M72 64 Q74 40 84 40 M128 64 Q126 40 116 40" fill="none" ${stroke}/><circle cx="84" cy="40" r="5" fill="#ffffff" ${stroke}/>
    <path d="M100 150 C78 134 80 112 94 114 Q100 116 100 122 Q100 116 106 114 C120 112 122 134 100 150 Z" fill="#ff8fab" ${stroke}/>
    <path d="M60 136 Q100 160 140 136" fill="none" stroke="#ffffff" stroke-width="4" stroke-dasharray="6 5"/>`,
  dress: (c) => `<path d="M80 36 L92 36 Q100 46 108 36 L120 36 L124 70 L120 80 L160 160 Q100 178 40 160 L80 80 L76 70 Z" fill="${c}" ${stroke}/>
    <path d="M78 80 h44" ${stroke}/>${["#fff6a8", "#ffffff", "#ffd1e0"].map((f, i) => dots(f, [[70 + i * 22, 120 + (i % 2) * 14, 5], [90 + i * 20, 148 - (i % 2) * 10, 4]])).join("")}`,
  leggings: (c) => `<path d="M64 40 h72 l4 128 h-30 l-10 -96 l-10 96 h-30 Z" fill="${c}" ${stroke}/>
    <rect x="62" y="38" width="76" height="14" rx="4" fill="${shade(c, -0.15)}" ${stroke}/>`,
  tee: (c, opt) => `<path d="M70 44 Q100 60 130 44 L162 62 L148 94 L134 88 L134 162 L66 162 L66 88 L52 94 L38 62 Z" fill="${c}" ${stroke}/>
    <path d="M84 48 Q100 64 116 48" fill="none" ${stroke}/>${opt.motif ?? ""}`,
  skirt: (c) => `<rect x="66" y="56" width="68" height="16" rx="5" fill="${shade(c, -0.18)}" ${stroke}/>
    <path d="M68 72 L36 150 Q100 170 164 150 L132 72 Z" fill="${c}" ${stroke}/>
    <path d="M44 132 Q100 150 156 132 M54 108 Q100 124 146 108" fill="none" stroke="${shade(c, 0.4)}" stroke-width="3"/>`,
  shorts: (c) => `<path d="M58 54 h84 l12 96 h-44 l-10 -50 l-10 50 h-44 Z" fill="${c}" ${stroke}/>
    <rect x="56" y="50" width="88" height="14" rx="4" fill="${shade(c, -0.15)}" ${stroke}/>
    <path d="M64 136 h36 M100 136 h36" stroke="${shade(c, 0.35)}" stroke-width="3" stroke-dasharray="5 4"/>`,
  polo: (c) => `<path d="M70 44 L100 56 L130 44 L162 62 L148 94 L134 88 L134 162 L66 162 L66 88 L52 94 L38 62 Z" fill="${c}" ${stroke}/>
    <path d="M78 40 L100 58 L90 72 Z M122 40 L100 58 L110 72 Z" fill="#ffffff" ${stroke}/>
    <path d="M100 58 v32" ${stroke}/>${dots("#ffffff", [[100, 70, 2.5], [100, 82, 2.5]])}`,
  joggers: (c) => `<path d="M62 40 h76 l6 118 h-34 l-10 -88 l-10 88 h-34 Z" fill="${c}" ${stroke}/>
    <rect x="60" y="38" width="80" height="14" rx="4" fill="${shade(c, -0.15)}" ${stroke}/>
    <rect x="66" y="150" width="34" height="12" rx="4" fill="${shade(c, -0.15)}" ${stroke}/><rect x="110" y="150" width="34" height="12" rx="4" fill="${shade(c, -0.15)}" ${stroke}/>
    <path d="M96 52 l-4 14 M104 52 l4 14" ${stroke}/>`,
  pajamas: (c) => `<g transform="translate(-20 -6) scale(.8)"><path d="M70 44 Q100 60 130 44 L162 62 L148 94 L134 88 L134 150 L66 150 L66 88 L52 94 L38 62 Z" fill="${c}" ${stroke}/>
    <path d="M66 80 h68 M66 104 h68 M66 128 h68" stroke="#ffffff" stroke-width="5"/></g>
    <g transform="translate(70 40) scale(.75)"><path d="M62 40 h76 l6 118 h-34 l-10 -88 l-10 88 h-34 Z" fill="${shade(c, 0.25)}" ${stroke}/>
    <path d="M66 70 h72 M68 100 h30 M104 100 h36 M70 130 h26 M108 130 h34" stroke="#ffffff" stroke-width="5"/></g>`,
  babyShoes: (c) => [0, 1].map((i) => `<g transform="translate(${i * 50 - 24} ${i * 14})">
    <path d="M50 120 Q50 90 76 88 L100 88 Q112 110 136 118 Q152 124 150 140 Q148 152 130 152 L62 152 Q48 150 50 120 Z" fill="${c}" ${stroke}/>
    <path d="M70 94 Q84 112 102 98" fill="none" ${stroke}/><path d="M56 144 h88" stroke="${shade(c, -0.2)}" stroke-width="4"/></g>`).join(""),
  sandals: (c) => [0, 1].map((i) => `<g transform="translate(${i * 54 - 26} ${i * 12})">
    <path d="M56 148 Q46 100 66 80 Q86 66 104 84 Q118 104 112 150 Q106 164 84 164 Q60 164 56 148 Z" fill="#e9d3b5" ${stroke}/>
    <rect x="54" y="100" width="62" height="14" rx="6" fill="${c}" ${stroke}/><rect x="56" y="128" width="60" height="14" rx="6" fill="${c}" ${stroke}/></g>`).join(""),
  sneakers: (c) => `<path d="M30 130 Q30 96 56 92 L92 90 Q108 112 140 116 Q170 120 172 142 L172 150 L30 150 Z" fill="${c}" ${stroke}/>
    <rect x="28" y="146" width="146" height="14" rx="6" fill="#ffffff" ${stroke}/>
    ${dots("#ffe14d", [[50, 153, 4], [80, 153, 4], [110, 153, 4], [140, 153, 4]])}<path d="M64 104 l18 -2 M66 116 l20 -2 M70 128 l20 -2" ${stroke}/>`,
  socks: (c) => [0, 1].map((i) => `<g transform="translate(${i * 46 - 18} ${i * 8})">
    <path d="M64 40 h40 v80 Q104 128 112 134 L132 146 Q146 156 136 168 Q130 176 116 170 L74 150 Q64 144 64 130 Z" fill="${i ? shade(c, 0.35) : c}" ${stroke}/>
    <rect x="62" y="38" width="44" height="16" rx="4" fill="#ffffff" ${stroke}/></g>`).join(""),
  sunHat: (c) => `<ellipse cx="100" cy="126" rx="78" ry="26" fill="${shade(c, -0.1)}" ${stroke}/>
    <path d="M58 124 Q56 64 100 62 Q144 64 142 124 Z" fill="${c}" ${stroke}/><path d="M58 108 Q100 118 142 108" fill="none" stroke="#ffffff" stroke-width="7"/>`,
  clips: () => ["#ff9db8", "#ffd166", "#8ecae6", "#b5e48c", "#cdb4db", "#ffadad"].map((f, i) => `<g transform="translate(${52 + (i % 3) * 48} ${76 + Math.floor(i / 3) * 52}) rotate(${(i % 2 ? 1 : -1) * 12})">
    <path d="M0 0 L-20 -13 Q-25 0 -20 13 Z M0 0 L20 -13 Q25 0 20 13 Z" fill="${f}" ${stroke}/>
    <rect x="-6" y="-7" width="12" height="14" rx="4" fill="${f}" ${stroke}/>
    <path d="M-16 19 h32" stroke="#9aa0ad" stroke-width="4" stroke-linecap="round"/></g>`).join(""),
  backpack: (c) => `<circle cx="66" cy="54" r="16" fill="${shade(c, -0.15)}" ${stroke}/><circle cx="134" cy="54" r="16" fill="${shade(c, -0.15)}" ${stroke}/>
    <rect x="48" y="50" width="104" height="120" rx="34" fill="${c}" ${stroke}/>
    <rect x="66" y="116" width="68" height="40" rx="12" fill="${shade(c, 0.3)}" ${stroke}/>
    ${dots(INK, [[84, 88, 4], [116, 88, 4]])}<ellipse cx="100" cy="100" rx="8" ry="6" fill="${INK}"/>`,
  wipes: (c) => `<rect x="34" y="66" width="132" height="84" rx="20" fill="${c}" ${stroke}/>
    <rect x="70" y="74" width="60" height="26" rx="13" fill="#ffffff" ${stroke}/><path d="M86 86 Q100 74 114 86" fill="none" ${stroke}/>
    <text x="100" y="134" text-anchor="middle" font-family="Arial Rounded MT Bold, Arial" font-weight="700" font-size="18" fill="${INK}">80 WIPES</text>`,
  pumpBottle: (c) => `<rect x="64" y="70" width="72" height="100" rx="16" fill="${c}" ${stroke}/>
    <rect x="86" y="50" width="28" height="22" rx="4" fill="#ffffff" ${stroke}/><path d="M100 50 v-14 h26" fill="none" ${stroke}/>
    <rect x="74" y="98" width="52" height="44" rx="8" fill="#ffffff" ${stroke}/><path d="M86 116 q14 12 28 0" fill="none" ${stroke}/>`,
  bottle: (c) => `<path d="M76 70 Q64 80 64 100 L64 160 Q64 172 76 172 L124 172 Q136 172 136 160 L136 100 Q136 80 124 70 Z" fill="${c}" ${stroke}/>
    <rect x="82" y="44" width="36" height="28" rx="6" fill="${shade(c, -0.2)}" ${stroke}/>
    <rect x="76" y="104" width="48" height="40" rx="8" fill="#ffffff" ${stroke}/>${dots(c, [[92, 124, 5], [108, 124, 5]])}`,
  powder: (c) => `<rect x="62" y="64" width="76" height="106" rx="22" fill="${c}" ${stroke}/>
    <path d="M78 64 Q78 42 100 42 Q122 42 122 64 Z" fill="#ffffff" ${stroke}/>${dots(INK, [[92, 52, 2], [100, 50, 2], [108, 52, 2]])}
    <circle cx="100" cy="118" r="22" fill="#ffffff" ${stroke}/><path d="M90 112 a4 4 0 0 1 8 0 M102 112 a4 4 0 0 1 8 0 M92 126 q8 6 16 0" fill="none" ${stroke}/>`,
  tube: (c) => `<g transform="rotate(-30 100 100)"><path d="M70 40 h60 l-6 110 h-48 Z" fill="${c}" ${stroke}/>
    <rect x="80" y="150" width="40" height="22" rx="4" fill="#ffffff" ${stroke}/><path d="M70 48 h60" ${stroke}/>
    <rect x="80" y="80" width="40" height="40" rx="8" fill="#ffffff" ${stroke}/></g>`,
  diapers: (c, opt) => `<path d="M42 58 Q100 44 158 58 L164 166 Q100 178 36 166 Z" fill="${c}" ${stroke}/>
    <path d="M70 92 h60 l-6 30 Q100 150 76 122 Z" fill="#ffffff" ${stroke}/>${dots(c, [[86, 104, 3], [114, 104, 3]])}
    <rect x="58" y="134" width="84" height="22" rx="11" fill="#ffffff" ${stroke}/>
    <text x="100" y="151" text-anchor="middle" font-family="Arial Rounded MT Bold, Arial" font-weight="700" font-size="15" fill="${INK}">${opt.count ?? ""}</text>`,
  feedingBottle: (c) => `<path d="M90 30 Q100 18 110 30 L112 50 L88 50 Z" fill="#f5d0a9" ${stroke}/>
    <rect x="78" y="48" width="44" height="18" rx="5" fill="${c}" ${stroke}/>
    <rect x="72" y="64" width="56" height="108" rx="18" fill="#ffffff" fill-opacity=".85" ${stroke}/>
    <rect x="76" y="110" width="48" height="58" rx="14" fill="#fff7e0"/><path d="M110 84 h12 M110 100 h12 M110 116 h12 M110 132 h12" ${stroke}/>`,
  teats: (c) => [0, 1].map((i) => `<g transform="translate(${i * 56 - 28} ${i * 10})">
    <path d="M86 70 Q100 40 114 70 L122 110 L78 110 Z" fill="#f5d0a9" ${stroke}/><ellipse cx="100" cy="114" rx="34" ry="10" fill="${c}" ${stroke}/></g>`).join(""),
  pacifier: (c) => `<ellipse cx="100" cy="96" rx="58" ry="40" fill="${c}" ${stroke}/>
    <circle cx="100" cy="96" r="16" fill="#ffffff" ${stroke}/><path d="M100 136 Q100 170 100 170" ${stroke}/>
    <circle cx="100" cy="158" r="20" fill="none" stroke="${shade(c, -0.2)}" stroke-width="8"/>`,
  sippyCup: (c) => `<path d="M84 34 Q100 26 116 34 L120 56 L80 56 Z" fill="${shade(c, 0.4)}" ${stroke}/>
    <rect x="68" y="54" width="64" height="22" rx="6" fill="${shade(c, -0.15)}" ${stroke}/>
    <path d="M72 76 L76 166 Q76 172 84 172 L116 172 Q124 172 124 166 L128 76 Z" fill="${c}" ${stroke}/>
    <path d="M128 92 Q156 96 152 120 Q150 138 126 136 M72 92 Q44 96 48 120 Q50 138 74 136" fill="none" ${stroke}/>`,
  can: (c) => `<rect x="56" y="56" width="88" height="116" rx="10" fill="${c}" ${stroke}/>
    <ellipse cx="100" cy="56" rx="44" ry="12" fill="#e8e3f0" ${stroke}/>
    <rect x="66" y="94" width="68" height="50" rx="10" fill="#ffffff" ${stroke}/>
    <text x="100" y="126" text-anchor="middle" font-family="Arial Rounded MT Bold, Arial" font-weight="700" font-size="22" fill="${INK}">1</text>
    <circle cx="100" cy="76" r="8" fill="#ffffff" ${stroke}/>`,
  cereal: (c) => `<path d="M60 52 L140 52 L134 172 L66 172 Z" fill="${c}" ${stroke}/>
    <path d="M60 52 L72 36 L128 36 L140 52" fill="${shade(c, -0.15)}" ${stroke}/>
    <ellipse cx="100" cy="120" rx="30" ry="18" fill="#ffffff" ${stroke}/>${dots("#f2c14e", [[90, 116, 4], [104, 112, 4], [110, 124, 4], [94, 126, 4]])}`,
  rattle: (c) => `<rect x="94" y="96" width="12" height="70" rx="6" fill="#d9a066" ${stroke}/>
    <circle cx="100" cy="72" r="36" fill="#e9c08f" ${stroke}/><circle cx="100" cy="72" r="20" fill="${c}" ${stroke}/>
    <circle cx="100" cy="170" r="12" fill="#e9c08f" ${stroke}/>`,
  elephant: (c) => `<ellipse cx="104" cy="118" rx="56" ry="42" fill="${c}" ${stroke}/>
    <rect x="66" y="140" width="20" height="30" rx="8" fill="${c}" ${stroke}/><rect x="118" y="140" width="20" height="30" rx="8" fill="${c}" ${stroke}/>
    <circle cx="66" cy="86" r="30" fill="${c}" ${stroke}/><ellipse cx="90" cy="80" rx="18" ry="24" fill="${shade(c, 0.35)}" ${stroke}/>
    <path d="M44 96 Q30 120 44 136 Q52 140 54 130 Q46 118 56 104" fill="${c}" ${stroke}/><circle cx="62" cy="80" r="3.5" fill="${INK}"/>`,
  rings: () => `<rect x="94" y="40" width="12" height="120" rx="6" fill="#d9a066" ${stroke}/>
    ${["#ef476f", "#ffd166", "#06d6a0", "#118ab2"].map((f, i) => `<ellipse cx="100" cy="${148 - i * 24}" rx="${52 - i * 9}" ry="13" fill="${f}" ${stroke}/>`).join("")}
    <circle cx="100" cy="42" r="12" fill="#cdb4db" ${stroke}/><rect x="40" y="160" width="120" height="14" rx="6" fill="#d9a066" ${stroke}/>`,
  blocks: () => [["#ef476f", 40, 112, "A"], ["#ffd166", 100, 112, "B"], ["#06d6a0", 70, 56, "C"]].map(([f, x, y, l]) =>
    `<rect x="${x}" y="${y}" width="56" height="56" rx="10" fill="${f}" ${stroke}/>
     <text x="${x + 28}" y="${y + 39}" text-anchor="middle" font-family="Arial Rounded MT Bold, Arial" font-weight="700" font-size="30" fill="#ffffff" stroke="${INK}" stroke-width="1.5">${l}</text>`).join(""),
  ducks: (c) => [[56, 0.75], [140, 0.75], [98, 1]].map(([x, s]) => `<g transform="translate(${x} 118) scale(${s})">
    <path d="M-36 0 Q-40 32 0 34 Q40 32 36 0 Q20 10 6 2 L-30 2 Z" fill="${c}" ${stroke}/><circle cx="-8" cy="-14" r="20" fill="${c}" ${stroke}/>
    <path d="M10 -16 L28 -12 L10 -6 Z" fill="#ff8c42" ${stroke}/><circle cx="-4" cy="-18" r="3" fill="${INK}"/></g>`).join(""),
  stroller: (c) => `<path d="M48 60 Q48 120 108 120 L150 120 L150 60 Q104 40 48 60 Z" fill="${c}" ${stroke}/>
    <path d="M48 60 Q90 70 108 120" fill="none" ${stroke}/><path d="M150 60 L170 40" ${stroke}/>
    <path d="M60 120 L56 150 M140 120 L146 150" ${stroke}/>${[[56, 156], [146, 156]].map(([x, y]) => `<circle cx="${x}" cy="${y}" r="16" fill="#ffffff" ${stroke}/><circle cx="${x}" cy="${y}" r="4" fill="${INK}"/>`).join("")}`,
  carrier: (c) => `<path d="M64 58 L56 34 M136 58 L144 34" stroke="${shade(c, -0.25)}" stroke-width="10" stroke-linecap="round"/>
    <rect x="58" y="56" width="84" height="96" rx="26" fill="${c}" ${stroke}/>
    <rect x="44" y="140" width="112" height="22" rx="10" fill="${shade(c, -0.2)}" ${stroke}/><rect x="80" y="86" width="40" height="34" rx="8" fill="${shade(c, 0.3)}" ${stroke}/>`,
  diaperBag: (c) => `<path d="M76 56 Q76 30 100 30 Q124 30 124 56" fill="none" stroke="${INK}" stroke-width="7"/>
    <rect x="44" y="54" width="112" height="116" rx="26" fill="${c}" ${stroke}/>
    <rect x="60" y="104" width="80" height="50" rx="12" fill="${shade(c, 0.3)}" ${stroke}/><rect x="40" y="96" width="14" height="40" rx="6" fill="${shade(c, -0.15)}" ${stroke}/>
    <rect x="146" y="96" width="14" height="40" rx="6" fill="${shade(c, -0.15)}" ${stroke}/>`,
  giftBox: (c, opt) => `<rect x="44" y="84" width="112" height="84" rx="8" fill="${c}" ${stroke}/>
    <rect x="36" y="64" width="128" height="26" rx="6" fill="${shade(c, -0.1)}" ${stroke}/>
    <rect x="92" y="64" width="16" height="104" fill="${opt.ribbon ?? "#ffd166"}" ${stroke}/>
    <path d="M100 64 Q72 30 70 52 Q70 66 100 64 Q128 30 130 52 Q130 66 100 64 Z" fill="${opt.ribbon ?? "#ffd166"}" ${stroke}/>`,
  camera: (c) => `<rect x="34" y="66" width="132" height="92" rx="16" fill="${c}" ${stroke}/>
    <path d="M74 66 L84 48 L116 48 L126 66" fill="${shade(c, -0.15)}" ${stroke}/><circle cx="100" cy="112" r="30" fill="#ffffff" ${stroke}/>
    <circle cx="100" cy="112" r="16" fill="${INK}"/><circle cx="146" cy="84" r="6" fill="#ffd166" ${stroke}/>`,
  needle: (c) => `<path d="M60 160 L140 40" stroke="${INK}" stroke-width="6" stroke-linecap="round"/><ellipse cx="136" cy="46" rx="4" ry="9" transform="rotate(34 136 46)" fill="#ffffff" stroke="${INK}" stroke-width="2"/>
    <path d="M136 46 Q170 80 120 110 Q70 140 110 170" fill="none" stroke="${c}" stroke-width="5" stroke-linecap="round"/>
    <rect x="34" y="120" width="44" height="50" rx="8" fill="${c}" ${stroke}/><rect x="30" y="116" width="52" height="10" rx="4" fill="#d9a066" ${stroke}/><rect x="30" y="164" width="52" height="10" rx="4" fill="#d9a066" ${stroke}/>`,
  scissors: (c) => `<circle cx="64" cy="146" r="18" fill="none" stroke="${c}" stroke-width="9"/><circle cx="136" cy="146" r="18" fill="none" stroke="${c}" stroke-width="9"/>
    <path d="M76 132 L132 44 M124 132 L68 44" stroke="${INK}" stroke-width="8" stroke-linecap="round"/>
    <path d="M30 70 Q100 90 170 70" fill="none" stroke="#ffd166" stroke-width="8" stroke-dasharray="10 6"/>`,
};

// Which drawing and colour each SKU gets.
function look(o) {
  const s = o.sku;
  const pick = (palette) => palette[[...s].reduce((n, ch) => n + ch.charCodeAt(0), 0) % palette.length];
  const rules = [
    ["BC-BOD", "bodysuit", pick(["#bfe3c8", "#fbe3b6", "#cfe1f7"]), { pack: true }],
    ["BC-SLP", "sleepsuit", "#9fb8e8"], ["BC-HAT", "hat", "#f7c6d4"], ["BC-MIT", "mittens", "#cdeaf2"],
    ["BC-SWD", "blanket", "#cfe6d8"], ["BC-BIB", "bib", "#ffd8a8"],
    ["GC-DRS", "dress", "#f49ac1"], ["GC-LEG", "leggings", "#f7a8c4"],
    ["GC-TEE", "tee", "#e2c6f5", { motif: `<path d="M100 82 l-8 26 h16 Z" fill="#ffd166" stroke="${INK}" stroke-width="2.5"/><path d="M78 124 q22 -22 44 0" fill="none" stroke="#ff9db8" stroke-width="6"/><path d="M84 128 q16 -14 32 0" fill="none" stroke="#8ecae6" stroke-width="6"/>` }],
    ["GC-SKT", "skirt", "#f9b8d0"],
    ["BY-TEE", "tee", "#a8d8a0", { motif: `<path d="M74 134 Q74 110 98 108 L112 96 L118 104 L110 112 Q128 116 126 134 Z" fill="#4c956c" stroke="${INK}" stroke-width="2.5"/><path d="M84 110 l4 -8 l4 8 M94 106 l4 -8 l4 8" fill="#4c956c" stroke="${INK}" stroke-width="2.5"/>` }],
    ["BY-SHT", "shorts", "#6c8ebf"], ["BY-POL", "polo", "#2f4b7c"], ["BY-JOG", "joggers", "#a5a8b3"],
    ["SW-PJS", "pajamas", "#8fb3e0"], ["SH-PRW", "babyShoes", "#f6c9a8"], ["SH-SND", "sandals", "#3d8bd9"],
    ["SH-SNK", "sneakers", "#ff6b6b"], ["AC-SOC", "socks", "#ffb703"], ["AC-HAT", "sunHat", "#f4d58d"],
    ["AC-HCL", "clips", "#ffffff"], ["AC-BAG", "backpack", "#c89f7a"],
    ["CA-WIP", "wipes", "#b8e0f6"], ["CA-LOT", "pumpBottle", "#ffd6e0"], ["CA-SHP", "bottle", "#ffe8a3"],
    ["CA-PWD", "powder", "#cfe8ff"], ["CA-RSH", "tube", "#d8f3dc"],
    ["DP-TAP-S", "diapers", "#a0c4ff", { count: "S · 62" }], ["DP-TAP-M", "diapers", "#9bf6ff", { count: "M · 56" }],
    ["DP-TAP-L", "diapers", "#caffbf", { count: "L · 48" }], ["DP-PNT-XL", "diapers", "#ffd6a5", { count: "XL · 42" }],
    ["FD-BTL", "feedingBottle", "#8ecae6"], ["FD-NIP", "teats", "#bde0fe"], ["FD-PAC", "pacifier", "#ffafcc"],
    ["FD-CUP", "sippyCup", "#90e0ef"], ["FD-FRM", "can", "#ffd166"], ["FD-CER", "cereal", "#f9c74f"],
    ["TY-RTL", "rattle", "#ef476f"], ["TY-PLH", "elephant", "#b8c0ff"], ["TY-STK", "rings", "#ffffff"],
    ["TY-BLK", "blocks", "#ffffff"], ["TY-DCK", "ducks", "#ffd60a"], ["GR-STR", "stroller", "#6d9dc5"],
    ["GR-CAR", "carrier", "#8d99ae"], ["GR-DBG", "diaperBag", "#84a59d"],
    ["SP-GBX", "giftBox", "#f4a6c0"], ["GS-NBN", "giftBox", "#bde0fe", { ribbon: "#ff8fab" }],
    ["GS-BTH", "giftBox", "#caffbf", { ribbon: "#8ecae6" }],
    ["SV-GFT", "giftBox", "#ffc8dd", { ribbon: "#cdb4db" }], ["SV-EMB", "needle", "#ef476f"],
    ["SV-ALT", "scissors", "#118ab2"], ["SV-PHO", "camera", "#b8c0ff"],
  ];
  const r = rules.find(([p]) => s.startsWith(p));
  return r ? { shape: r[1], color: r[2], opt: r[3] ?? {} } : null;
}

const BACKGROUNDS = {
  "Baby Clothing": "#fdf1f4", "Girls Clothing": "#fdeef6", "Boys Clothing": "#eef4fd", Sleepwear: "#eef0fb", Shoes: "#fef3ea",
  Accessories: "#fff8e6", "Baby Care": "#ecf8fb", Diapering: "#eef8f1", Feeding: "#f0f6ff", Toys: "#fff4e8", "Baby Gear": "#eef2f2",
  "Gift Sets": "#fbf0fb", Services: "#f3f0fb", "Store Supplies": "#f8f3ec",
};

/** "(0-3M)" "(2T)" "(EU 24)" from the name; packs get "3 PACK". */
function badge(o) {
  const size = /\(([^)]+)\)\s*$/.exec(o.name)?.[1];
  if (size) return size.replace(/^EU /, "EU ");
  const pack = /(\d+)[- ]Pack/i.exec(o.name);
  return pack ? `${pack[1]} PACK` : null;
}

function svg(o) {
  const l = look(o);
  if (!l) return null;
  const bg = BACKGROUNDS[o.categoryName] ?? "#f6f4fb";
  const b = badge(o);
  const bw = b ? Math.max(44, b.length * 9.5 + 20) : 0;
  const service = o.type === "SERVICE";
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200" width="600" height="600">
  <rect width="200" height="200" fill="${bg}"/>
  <circle cx="100" cy="104" r="78" fill="#ffffff" opacity=".7"/>
  <ellipse cx="100" cy="178" rx="62" ry="7" fill="${INK}" opacity=".08"/>
  <g transform="translate(100 104) scale(.86) translate(-100 -104)">${shapes[l.shape](l.color, l.opt)}</g>
  ${b ? `<g transform="translate(${190 - bw} 30)"><rect width="${bw}" height="26" rx="13" fill="${INK}"/>
    <text x="${bw / 2}" y="18" text-anchor="middle" font-family="Arial Rounded MT Bold, Helvetica, Arial" font-weight="700" font-size="14" fill="#ffffff">${b}</text></g>` : ""}
  ${service ? `<g transform="translate(10 30)"><rect width="66" height="24" rx="12" fill="#ffffff" stroke="${INK}" stroke-width="2"/>
    <text x="33" y="16.5" text-anchor="middle" font-family="Helvetica, Arial" font-weight="700" font-size="11" fill="${INK}">SERVICE</text></g>` : ""}
</svg>`;
}

// ------------------------------------------------------------------------------------- run

async function api(path, init = {}, token) {
  const res = await fetch(API + path, { ...init, headers: { ...(init.headers ?? {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) } });
  if (!res.ok) throw new Error(`${init.method ?? "GET"} ${path} -> ${res.status} ${(await res.text()).slice(0, 200)}`);
  return res.status === 204 ? null : res.json();
}

const { accessToken: token } = await api("/auth/login", {
  method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ username: "admin", password: "admin1234" }),
});
const { items } = await api("/offerings?size=500&type=PRODUCT,ITEM,SERVICE,PACKAGE", {}, token);
const targets = items.filter((o) => o.active && (o.type !== "ITEM" || o.sellable) && (!onlyMissing || !o.hasImage));
if (outDir) mkdirSync(outDir, { recursive: true });

let done = 0;
const skipped = [];
for (const o of targets) {
  const drawing = svg(o);
  if (!drawing) {
    skipped.push(o.sku);
    continue;
  }
  const webp = await sharp(Buffer.from(drawing)).webp({ quality: 90 }).toBuffer();
  if (outDir) {
    writeFileSync(join(outDir, `${o.sku}.webp`), webp);
  } else {
    const form = new FormData();
    form.append("file", new Blob([webp], { type: "image/webp" }), `${o.sku}.webp`);
    await api(`/offerings/${o.id}/image`, { method: "PUT", body: form }, token);
  }
  done++;
}
console.log(`${outDir ? "Wrote" : "Uploaded"} ${done} pictures${skipped.length ? `; no drawing for ${skipped.join(", ")}` : ""}`);
