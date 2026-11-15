import path from "node:path";

import type { NextConfig } from "next";

import { allowedDevOrigins } from "../shared/dev-origins.mjs";

const dev = process.env.NODE_ENV !== "production";

/**
 * frontend-nav: the portal, at the root of the site: what the store offers and the way into each app.
 * On the site the proxy in front sends /api to the backend, /buy, /cas, /ctl and /app to their apps and
 * the rest here. In development this app does that itself, so http://localhost:3000 works like the real
 * site (administration 3001, the till 3002, the shop 3003, the app page 3004).
 */
const nextConfig: NextConfig = {
  env: { NEXT_PUBLIC_ZONE: "nav" },
  // Self-contained server for the Docker image; the code it needs includes ../shared.
  output: "standalone",
  outputFileTracingRoot: path.join(__dirname, ".."),
  transpilePackages: ["@pos/shared"],
  devIndicators: false,
  poweredByHeader: false,
  // Development: requests to /api pass through this server (rewrites), whose proxy.ts buffers bodies up to
  // this size and would cut the mobile app's APK (up to 100 MB, Settings > Mobile app) short. On a server
  // /api never reaches Next.js.
  experimental: { proxyClientMaxBodySize: "110mb" },
  // Addresses from before the apps moved: staff pages, and the shop's pages from when it was at /.
  async redirects() {
    const old = (page: string, zone: string) => [
      { source: page, destination: `/${zone}${page}`, permanent: false },
      { source: `${page}/:rest*`, destination: `/${zone}${page}/:rest*`, permanent: false },
    ];
    return [
      ...["/pos", "/orders", "/online-orders"].flatMap((p) => old(p, "cas")),
      ...["/login", "/dashboard", "/catalog", "/inventory", "/purchasing", "/marketing", "/customers", "/reports",
        "/accounting", "/settings", "/currencies", "/users", "/help", "/manual"].flatMap((p) => old(p, "ctl")),
      ...["/products", "/cart", "/track", "/account"].flatMap((p) => old(p, "buy")),
    ];
  },
  // Development only: the backend and the other apps' dev servers behind this one origin.
  async rewrites() {
    if (!dev) return [];
    const api = process.env.API_URL ?? "http://localhost:8081";
    const apps: [string, string][] = [
      ["ctl", process.env.CTL_URL ?? "http://localhost:3001"],
      ["cas", process.env.CAS_URL ?? "http://localhost:3002"],
      ["buy", process.env.BUY_URL ?? "http://localhost:3003"],
      ["app", process.env.APP_URL ?? "http://localhost:3004"],
    ];
    return [
      { source: "/api/:path*", destination: `${api}/api/:path*` },
      ...apps.flatMap(([zone, url]) => [
        { source: `/${zone}`, destination: `${url}/${zone}` },
        { source: `/${zone}/:path*`, destination: `${url}/${zone}/:path*` },
      ]),
    ];
  },
  // Dev server only: let phones and tablets on the network load the app's scripts and live reload.
  allowedDevOrigins: allowedDevOrigins(),
};

export default nextConfig;
