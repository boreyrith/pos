import path from "node:path";

import type { NextConfig } from "next";

import { allowedDevOrigins } from "../shared/dev-origins.mjs";

const dev = process.env.NODE_ENV !== "production";

/**
 * frontend-cas: the cashier app (Sell, Sales), at /cas. On the shop's domain the proxy in front sends
 * /cas here and /api to the backend. In development open http://localhost:3000 (frontend-nav passes
 * /cas on to this server), or this one directly on port 3002.
 */
const nextConfig: NextConfig = {
  basePath: "/cas",
  env: { NEXT_PUBLIC_ZONE: "cas" },
  // Self-contained server for the Docker image; the code it needs includes ../shared.
  output: "standalone",
  outputFileTracingRoot: path.join(__dirname, ".."),
  transpilePackages: ["@pos/shared"],
  devIndicators: false,
  poweredByHeader: false,
  // Development only: the backend behind this origin, as the proxy does on the real site.
  async rewrites() {
    if (!dev) return [];
    const api = process.env.API_URL ?? "http://localhost:8081";
    return [{ source: "/api/:path*", destination: `${api}/api/:path*`, basePath: false }];
  },
  // Dev server only: let phones and tablets on the network load the app's scripts and live reload.
  allowedDevOrigins: allowedDevOrigins(),
};

export default nextConfig;
