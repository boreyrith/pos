#!/usr/bin/env node
/**
 * Lists interface text without a Khmer translation: every t("..."), msg("...") and translate("...")
 * in src, compared with src/lib/i18n/km.ts. Exits 1 if anything is missing (or, with --unused,
 * also lists translations nothing uses).
 *   npm run i18n:check            # missing translations
 *   npm run i18n:check -- --json  # missing ones as a JSON object to fill in
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const root = new URL("../src", import.meta.url).pathname;
const files = [];
(function walk(dir) {
  for (const f of readdirSync(dir)) {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) walk(p);
    else if (/\.(tsx?|mjs)$/.test(f) && !/\.test\./.test(f) && !p.includes("/lib/i18n/")) files.push(p);
  }
})(root);

const CALL = /(?<![\w.])(?:t|msg|translate)\(\s*(["'])((?:\\.|(?!\1)[^\\])*)\1/g;
const used = new Map();
for (const f of files) {
  const src = readFileSync(f, "utf8");
  for (const m of src.matchAll(CALL)) {
    const text = m[2].replace(/\\(["'\\])/g, "$1").replace(/\\n/g, "\n");
    if (!used.has(text)) used.set(text, f.slice(root.length + 1));
  }
}

const kmSrc = readFileSync(join(root, "lib/i18n/km.ts"), "utf8");
const KM = new Function(kmSrc.replace(/^[\s\S]*?=\s*/, "return ").replace(/;\s*$/, ""))();
const missing = [...used.keys()].filter((k) => !(k in KM));
const unused = Object.keys(KM).filter((k) => !used.has(k));

if (process.argv.includes("--json")) {
  console.log(JSON.stringify(Object.fromEntries(missing.map((k) => [k, ""])), null, 2));
} else {
  for (const k of missing) console.log(`missing  ${used.get(k)}: ${JSON.stringify(k)}`);
  if (process.argv.includes("--unused")) for (const k of unused) console.log(`unused   ${JSON.stringify(k)}`);
  console.log(`${used.size} texts, ${used.size - missing.length} translated, ${missing.length} missing`);
}
process.exit(missing.length ? 1 : 0);
