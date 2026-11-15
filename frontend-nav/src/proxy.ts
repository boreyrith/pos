import type { NextRequest } from "next/server";

import { cspProxy } from "@pos/shared/csp";

/** Every page carries the Content Security Policy with a fresh nonce (shared/csp.ts). */
export function proxy(request: NextRequest) {
  return cspProxy(request);
}

export const config = {
  matcher: [
    {
      // Pages only: not the API proxy, built files, the manual or the scanner's engine.
      source: "/((?!api/|_next/static|_next/image|favicon.ico|manual/|zxing/).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
