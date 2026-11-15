import type { Metadata } from "next";

import { TaxReportView } from "@/app/(app)/reports/tax/TaxReportView";

export const metadata: Metadata = { title: "Tax report" };

export default function TaxReportPage() {
  return <TaxReportView />;
}
