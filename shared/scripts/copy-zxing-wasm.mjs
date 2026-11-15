// Copies the barcode reader's WebAssembly file into the app's public/, so the camera scanner loads it
// from that app instead of a CDN (works offline and behind firewalls). Each app runs it, from its own
// folder, before `dev` and `build`; npm keeps the packages in the repository's node_modules.
import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import path from "node:path";

const candidates = ["node_modules", path.join("..", "node_modules")].flatMap((modules) => [
  path.join(modules, "zxing-wasm", "dist", "reader", "zxing_reader.wasm"),
  path.join(modules, "barcode-detector", "node_modules", "zxing-wasm", "dist", "reader", "zxing_reader.wasm"),
]);
const from = candidates.find((c) => existsSync(c));
if (!from) {
  throw new Error("zxing_reader.wasm not found; run npm install");
}
const dir = path.join("public", "zxing");
mkdirSync(dir, { recursive: true });
copyFileSync(from, path.join(dir, "zxing_reader.wasm"));
