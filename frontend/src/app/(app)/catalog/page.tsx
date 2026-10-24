import type { Metadata } from "next";
import { Suspense } from "react";

import { CatalogManager } from "./CatalogManager";

export const metadata: Metadata = { title: "Catalog" };

export default function CatalogPage() {
  return (
    <Suspense>
      <CatalogManager />
    </Suspense>
  );
}
