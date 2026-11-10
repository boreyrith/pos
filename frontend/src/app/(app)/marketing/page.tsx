import type { Metadata } from "next";
import { Suspense } from "react";

import { MarketingView } from "./MarketingView";

export const metadata: Metadata = { title: "Marketing" };

export default function MarketingPage() {
  return (
    <Suspense>
      <MarketingView />
    </Suspense>
  );
}
