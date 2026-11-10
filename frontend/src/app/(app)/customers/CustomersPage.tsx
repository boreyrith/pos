"use client";

import { PageHeader } from "@/components/ui";
import { useT } from "@/lib/i18n";

import { CustomersView } from "./CustomersView";

/** Customers: everyone on file, with their purchases, points and tier (Marketing works with the same customers). */
export function CustomersPage() {
  const t = useT();
  return (
    <div className="mx-auto max-w-5xl p-4 lg:p-8">
      <PageHeader title={t("Customers")} />
      <CustomersView />
    </div>
  );
}
