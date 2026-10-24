import type { Metadata } from "next";

import { PurchaseOrderView } from "./PurchaseOrderView";

export const metadata: Metadata = { title: "Purchase order" };

export default async function PurchaseOrderPage({ params }: PageProps<"/purchasing/orders/[id]">) {
  const { id } = await params;
  return <PurchaseOrderView id={Number(id)} />;
}
