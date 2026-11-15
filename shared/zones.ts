/**
 * The web apps share one domain, each under its own path (its next.config basePath): the portal
 * (frontend-nav) at /, the online shop (frontend-buy) at /buy, the till (frontend-cas) at /cas,
 * administration (frontend-ctl) at /ctl and the mobile app's download page (frontend-app) at /app;
 * /api is the backend.
 * Within an app, Next's Link and router add the prefix themselves. A page of the other app is a
 * plain <a> or a full page load, with that app's prefix.
 */
export type Zone = "ctl" | "cas" | "buy" | "app" | "nav";

export const ZONE_PATHS: Record<Zone, string> = { ctl: "/ctl", cas: "/cas", buy: "/buy", app: "/app", nav: "" };

/** The app this code runs in, set by its next.config (NEXT_PUBLIC_ZONE). */
export const ZONE: Zone = (Object.keys(ZONE_PATHS) as Zone[]).find((z) => z === process.env.NEXT_PUBLIC_ZONE) ?? "ctl";

/**
 * The platform this app signs in as: each has its own sessions and sign-in cookie, so signing in or out
 * of the till leaves administration as it is (and the other way round).
 */
export const PLATFORM: "CTL" | "CAS" = ZONE === "cas" ? "CAS" : "CTL";

/** This app's prefix, for files in its public/ folder (Next adds it only to links and routes). */
export const BASE_PATH = ZONE_PATHS[ZONE];

/** The pages of the cashier app; every other page is in the administration app. */
const CAS_PAGES = ["/pos", "/orders", "/online-orders"];

/** Which staff app serves a page, from its path within the apps (e.g. "/orders/12" or "/catalog"). */
export function zoneOf(path: string): Zone {
  const p = path.split(/[?#]/)[0];
  return CAS_PAGES.some((page) => p === page || p.startsWith(`${page}/`)) ? "cas" : "ctl";
}

/** The href of a page from this app: as it is within the app, with the other app's prefix outside it. */
export function hrefFor(path: string) {
  const zone = zoneOf(path);
  return zone === ZONE ? path : ZONE_PATHS[zone] + path;
}

/** Goes to a page of either app, replacing the current entry: by the router within this app, else a page load. */
export function goTo(path: string, router: { replace(href: string): void }) {
  const zone = zoneOf(path);
  if (zone === ZONE) router.replace(path);
  else window.location.replace(ZONE_PATHS[zone] + path);
}
