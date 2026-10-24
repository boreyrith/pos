import type { Metadata } from "next";
import { Suspense } from "react";

import { ReportsView } from "./ReportsView";

export const metadata: Metadata = { title: "Reports" };

export default function ReportsPage() {
  return (
    <Suspense>
      <ReportsView />
    </Suspense>
  );
}
