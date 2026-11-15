import path from "node:path";

import type { NextConfig } from "next";

import { allowedDevOrigins } from "../shared/dev-origins.mjs";

const dev = process.env.NODE_ENV !== "production";

/**
 * frontend-ctl: the administration app, at /ctl. On the shop's domain the proxy in front (Caddy, nginx,
 * Apache) sends /ctl here, /cas to frontend-cas, /api to the backend and the rest to the other apps
 * (frontend-nav, which also redirects addresses from before the split). In development open
 * http://localhost:3000/ctl (frontend-nav passes /ctl on to this server), or this one on port 3001.
 */
const nextConfig: NextConfig = {
  basePath: "/ctl",
  env: { NEXT_PUBLIC_ZONE: "ctl" },
  // Self-contained server for the Docker image; the code it needs includes ../shared.
  output: "standalone",
  outputFileTracingRoot: path.join(__dirname, ".."),
  transpilePackages: ["@pos/shared"],
  // The dev badge sits on top of the sidebar's Sign out button.
  devIndicators: false,
  poweredByHeader: false,
  // Development: requests to /api pass through this server (rewrites), whose proxy.ts buffers bodies up to
  // this size and would cut the mobile app's APK (up to 100 MB, Settings > Mobile app) short. On a server
  // /api never reaches Next.js.
  experimental: { proxyClientMaxBodySize: "110mb" },
  async headers() {
    return [
      {
        // The manual is a static page with its own small inline script and Google Fonts; pages get
        // their policy from proxy.ts.
        source: "/manual/:path*",
        headers: [
          {
            key: "Content-Security-Policy",
            value: "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; img-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'none'; frame-ancestors 'self'",
          },
        ],
      },
    ];
  },
  // Development only: the backend and the cashier app's dev server behind this one origin.
  async rewrites() {
    if (!dev) return [];
    const api = process.env.API_URL ?? "http://localhost:8081";
    const cas = process.env.CAS_URL ?? "http://localhost:3002";
    return [
      { source: "/api/:path*", destination: `${api}/api/:path*`, basePath: false },
      { source: "/cas", destination: `${cas}/cas`, basePath: false },
      { source: "/cas/:path*", destination: `${cas}/cas/:path*`, basePath: false },
    ];
  },
  // Dev server only: let phones and tablets on the network load the app's scripts and live reload.
  allowedDevOrigins: allowedDevOrigins(),
};

export default nextConfig;
