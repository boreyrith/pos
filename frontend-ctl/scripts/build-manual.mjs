// Turns the user manual (docs/user-manual.html, a page fragment) into a full page the app serves
// at /manual/, shown under Settings > User manual. Runs before `dev` and `build`. The output is
// kept in the repo, so builds without the docs folder (the Docker image) still have it.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

const source = path.join("..", "docs", "user-manual.html");
const target = path.join("public", "manual", "index.html");
if (!existsSync(source)) {
  if (!existsSync(target)) throw new Error(`${source} not found and no built manual in ${target}`);
  process.exit(0); // keep the copy already in public/
}
const html = readFileSync(source, "utf8");
const cut = html.indexOf("</style>") + "</style>".length;
const page = `<!doctype html>
<!-- Generated from docs/user-manual.html by scripts/build-manual.mjs. Edit the source, not this file. -->
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
${html.slice(0, cut).trim()}
<style>body { margin: 0; } [hidden] { display: none !important; } img { max-width: 100%; }</style>
</head>
<body>
${html.slice(cut).trim()}
</body>
</html>
`;
mkdirSync(path.dirname(target), { recursive: true });
writeFileSync(target, page);
console.log(`Built ${target}`);
