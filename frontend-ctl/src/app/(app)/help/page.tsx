import type { Metadata } from "next";

import { HelpView } from "@/app/(app)/help/HelpView";

export const metadata: Metadata = { title: "Help" };

export default function HelpPage() {
  return <HelpView />;
}
