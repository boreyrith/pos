import Link from "next/link";

import { ZONE, hrefFor, zoneOf } from "../zones";

/**
 * A link to a page of either web app, by its path within the apps (e.g. "/orders/12"): Next's Link
 * within this app, a plain link (a full page load, with the other app's prefix) to the other one.
 */
export function PageLink({ href, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) {
  return zoneOf(href) === ZONE ? <Link href={href} {...props} /> : <a href={hrefFor(href)} {...props} />;
}
