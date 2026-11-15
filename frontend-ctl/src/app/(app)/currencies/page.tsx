import { redirect } from "next/navigation";

/** Currencies moved into Settings; keep old links working. */
export default function CurrenciesPage() {
  redirect("/settings?tab=payments");
}
