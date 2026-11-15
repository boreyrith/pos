import type { Metadata } from "next";

import { CustomersPage } from "@/app/(app)/customers/CustomersPage";

export const metadata: Metadata = { title: "Customers" };

export default function Customers() {
  return <CustomersPage />;
}
