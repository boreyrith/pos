import { redirect } from "next/navigation";

/** The till opens on Sell; roles that can't sell are sent on to their own first page. */
export default function Home() {
  redirect("/pos");
}
