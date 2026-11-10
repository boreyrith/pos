import { NextResponse, type NextRequest } from "next/server";

/**
 * Content Security Policy: the browser runs only this app's own scripts, each page's carrying a
 * fresh nonce (Next.js adds it to its scripts when it renders the page), so injected HTML can't run
 * code. Pages are therefore rendered per request (see app/layout.tsx).
 * - wasm-unsafe-eval: the barcode scanner's zxing engine is WebAssembly (served from /zxing).
 * - style 'unsafe-inline': React renders style attributes; styles can't run code.
 * - blob:/data: images: product photos picked before upload, QR codes and barcode labels.
 * - frame-ancestors 'self': only this app may frame its pages (the manual in Settings).
 * The manual (/manual) is a static page with its own policy in next.config.ts.
 */
export function proxy(request: NextRequest) {
  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");
  const dev = process.env.NODE_ENV === "development";
  const policy = [
    "default-src 'self'",
    // React uses eval for better error stacks in development only.
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic' 'wasm-unsafe-eval'${dev ? " 'unsafe-eval'" : ""}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' blob: data:",
    "font-src 'self' data:",
    // Development: the live-reload websocket (older Safari doesn't count ws: as 'self').
    `connect-src 'self'${dev ? " ws: wss:" : ""}`,
    "media-src 'self' blob:",
    "worker-src 'self' blob:",
    "frame-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'self'",
  ].join("; ");

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("Content-Security-Policy", policy);
  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set("Content-Security-Policy", policy);
  return response;
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
