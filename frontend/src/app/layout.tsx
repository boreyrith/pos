import type { Metadata } from "next";
import { connection } from "next/server";

import { Providers } from "./providers";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "POS", template: "%s · POS" },
  description: "Point of sale for retail and food & beverage",
};

export default async function RootLayout({ children }: LayoutProps<"/">) {
  // Rendered per request, so each page's scripts carry that request's CSP nonce (see proxy.ts).
  await connection();
  return (
    <html lang="en" className="h-full antialiased">
      <body className="min-h-full font-sans">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
