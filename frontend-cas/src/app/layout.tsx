import type { Metadata } from "next";
import { connection } from "next/server";

import { Providers } from "@pos/shared/components/Providers";
import "@pos/shared/styles/globals.css";

export const metadata: Metadata = {
  title: { default: "POS", template: "%s · POS" },
  // frontend-cas: the till (Sell, Sales).
  description: "Point of sale for retail and food & beverage",
};

export default async function RootLayout({ children }: LayoutProps<"/">) {
  // Rendered per request, so each page's scripts carry that request's CSP nonce (see proxy.ts).
  await connection();
  return (
    <html lang="en" data-zone="cas" className="h-full antialiased">
      <body className="min-h-full font-sans">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
