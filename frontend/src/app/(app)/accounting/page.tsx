import type { Metadata } from "next";
import { Suspense } from "react";

import { AccountingView } from "./AccountingView";

export const metadata: Metadata = { title: "Accounting" };

export default function AccountingPage() {
  return (
    <Suspense>
      <AccountingView />
    </Suspense>
  );
}
