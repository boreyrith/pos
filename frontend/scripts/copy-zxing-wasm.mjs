// Copies the barcode reader's WebAssembly file into public/, so the camera scanner loads it from
// this app instead of a CDN (works offline and behind firewalls). Runs before `dev` and `build`.
import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import path from "node:path";

const candidates = [
  path.join("node_modules", "zxing-wasm", "dist", "reader", "zxing_reader.wasm"),
  path.join("node_modules", "barcode-detector", "node_modules", "zxing-wasm", "dist", "reader", "zxing_reader.wasm"),
];
const from = candidates.find((c) => existsSync(c));
if (!from) {
  throw new Error("zxing_reader.wasm not found; run npm install");
}
const dir = path.join("public", "zxing");
mkdirSync(dir, { recursive: true });
copyFileSync(from, path.join(dir, "zxing_reader.wasm"));
