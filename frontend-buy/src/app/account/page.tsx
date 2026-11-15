import type { Metadata } from "next";
import { Suspense } from "react";

import { AccountView } from "./AccountView";

export const metadata: Metadata = { title: "My account" };

export default function AccountPage() {
  return (
    <Suspense>
      <AccountView />
    </Suspense>
  );
}
