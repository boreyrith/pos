import type { Metadata } from "next";
import { Suspense } from "react";

import { SalesView } from "./SalesView";

export const metadata: Metadata = { title: "Sales" };

export default function SalesPage() {
  return (
    <Suspense>
      <SalesView />
    </Suspense>
  );
}
