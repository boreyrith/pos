import { AppShell } from "@pos/shared/components/AppShell";

import { PrinterButton } from "@/components/pos/PrinterSettings";

export default function AppLayout({ children }: LayoutProps<"/">) {
  return <AppShell tillTools={<PrinterButton />}>{children}</AppShell>;
}
