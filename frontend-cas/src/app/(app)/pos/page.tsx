import type { Metadata } from "next";

import { PosScreen } from "@/app/(app)/pos/PosScreen";

export const metadata: Metadata = { title: "Sell" };

export default function PosPage() {
  return <PosScreen />;
}
