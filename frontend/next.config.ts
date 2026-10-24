import os from "node:os";

import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Self-contained server for the Docker image.
  output: "standalone",
  // The dev badge sits on top of the sidebar's Sign out button.
  devIndicators: false,
  poweredByHeader: false,
  // Dev server only: let phones and tablets on the network load the app's scripts and live reload.
  // Add more hostnames or IPs with ALLOWED_DEV_ORIGINS (comma-separated).
  allowedDevOrigins: [
    "*.local",
    ...Object.values(os.networkInterfaces())
      .flat()
      .filter((i) => i !== undefined && i.family === "IPv4" && !i.internal)
      .map((i) => i!.address),
    ...(process.env.ALLOWED_DEV_ORIGINS ?? "").split(",").map((s) => s.trim()).filter(Boolean),
  ],
};

export default nextConfig;
