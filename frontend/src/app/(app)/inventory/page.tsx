import type { Metadata } from "next";
import { Suspense } from "react";

import { InventoryView } from "./InventoryView";

export const metadata: Metadata = { title: "Inventory" };

export default function InventoryPage() {
  return (
    <Suspense>
      <InventoryView />
    </Suspense>
  );
}
