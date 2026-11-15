import type { Metadata } from "next";
import { connection } from "next/server";

import "@pos/shared/styles/globals.css";

import { Providers } from "./providers";

export const metadata: Metadata = {
  title: { default: "Welcome", template: "%s" },
  // frontend-nav: the portal.
  description: "Shop online, get the mobile app, or sign in to work",
};

export default async function RootLayout({ children }: LayoutProps<"/">) {
  // Rendered per request, so each page's scripts carry that request's CSP nonce (see proxy.ts).
  await connection();
  return (
    <html lang="en" data-zone="nav" className="h-full antialiased">
      <body className="min-h-full bg-bg font-sans">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
