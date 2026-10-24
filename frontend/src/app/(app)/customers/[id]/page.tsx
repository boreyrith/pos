import type { Metadata } from "next";

import { CustomerView } from "./CustomerView";

export const metadata: Metadata = { title: "Customer" };

export default async function CustomerPage({ params }: PageProps<"/customers/[id]">) {
  const { id } = await params;
  return <CustomerView id={Number(id)} />;
}
