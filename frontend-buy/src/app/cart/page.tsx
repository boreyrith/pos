import type { Metadata } from "next";

import { CartView } from "./CartView";

export const metadata: Metadata = { title: "Cart" };

export default function CartPage() {
  return <CartView />;
}
