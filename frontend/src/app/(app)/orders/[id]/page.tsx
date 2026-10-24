import type { Metadata } from "next";

import { OrderDetail } from "./OrderDetail";

export const metadata: Metadata = { title: "Order" };

export default async function OrderPage({ params }: PageProps<"/orders/[id]">) {
  const { id } = await params;
  return <OrderDetail id={Number(id)} />;
}
