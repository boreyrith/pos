import { redirect } from "next/navigation";

/** Customers are a tab of Sales; keep old links working. */
export default function CustomersPage() {
  redirect("/orders?tab=customers");
}
