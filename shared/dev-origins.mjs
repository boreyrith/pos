import os from "node:os";

/**
 * For each app's next.config.ts (plain JavaScript, so the config loads without compiling shared/): the
 * addresses whose pages may load the dev server's scripts and live reload, so phones and tablets on the
 * network can use it. This machine's network addresses, *.local, and ALLOWED_DEV_ORIGINS (comma-separated).
 * The dev server only; a production build ignores it.
 */
export function allowedDevOrigins() {
  return [
    "*.local",
    ...Object.values(os.networkInterfaces())
      .flat()
      .filter((i) => i !== undefined && i.family === "IPv4" && !i.internal)
      .map((i) => i.address),
    ...(process.env.ALLOWED_DEV_ORIGINS ?? "").split(",").map((s) => s.trim()).filter(Boolean),
  ];
}
