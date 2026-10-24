import type { Metadata } from "next";

import { PosScreen } from "./PosScreen";

export const metadata: Metadata = { title: "Sell" };

export default function PosPage() {
  return <PosScreen />;
}
