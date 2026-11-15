import { redirect } from "next/navigation";

/** The Products page became the Catalog page; keep old links working. */
export default function ProductsPage() {
  redirect("/catalog");
}
