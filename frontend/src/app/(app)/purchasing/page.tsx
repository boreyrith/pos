import type { Metadata } from "next";
import { Suspense } from "react";

import { PurchasingView } from "./PurchasingView";

export const metadata: Metadata = { title: "Purchasing" };

export default function PurchasingPage() {
  return (
    <Suspense>
      <PurchasingView />
    </Suspense>
  );
}
