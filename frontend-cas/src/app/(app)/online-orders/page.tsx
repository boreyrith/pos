import type { Metadata } from "next";
import { Suspense } from "react";

import { OnlineOrdersView } from "@/app/(app)/online-orders/OnlineOrdersView";

export const metadata: Metadata = { title: "Online orders" };

export default function OnlineOrdersPage() {
  return (
    <Suspense>
      <OnlineOrdersView />
    </Suspense>
  );
}
