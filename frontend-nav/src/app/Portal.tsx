"use client";

import { ArrowRight, LayoutDashboard, Lock, MapPin, ShoppingBag, ShoppingCart, Smartphone } from "lucide-react";

import { StoreLogo } from "@pos/shared/components/StoreLogo";
import { PublicHeader } from "@pos/shared/components/public/PublicHeader";
import { Badge, cx } from "@pos/shared/components/ui";
import { useMobileApp, usePublicStore } from "@pos/shared/api/public";
import { useT } from "@pos/shared/i18n";
import { ZONE_PATHS } from "@pos/shared/zones";

/**
 * The site's front door: the store, then one tile per app. Customers: the online shop and the mobile
 * app. Staff: the till and administration (each asks for a staff sign-in). Status comes from the
 * public endpoints, so a closed shop says so here.
 */
export function Portal() {
  const t = useT();
  const store = usePublicStore().data;
  const app = useMobileApp().data;
  const name = store?.name ?? "";
  const appReady = !!(app && (app.apk || app.playStoreUrl || app.appStoreUrl || app.testflightUrl));

  return (
    <>
      <PublicHeader name={name} />
      <main className="mx-auto max-w-6xl px-4 pb-16">
        <section className="flex flex-col items-center gap-4 py-12 text-center sm:py-16">
          <StoreLogo className="size-20 rounded-3xl shadow-lg ring-1 ring-border" />
          <h1 className="text-3xl font-semibold tracking-tight text-balance sm:text-4xl">{name ? t("Welcome to {store}", { store: name }) : " "}</h1>
          <p className="max-w-xl text-muted">{t("Shop online and pick up or get it delivered, get our mobile app, or sign in to work.")}</p>
        </section>

        <h2 className="mb-3 text-xs font-semibold tracking-wide text-muted uppercase">{t("For customers")}</h2>
        <div className="grid gap-4 md:grid-cols-2">
          <Tile
            href={ZONE_PATHS.buy}
            icon={ShoppingBag}
            tone="primary"
            title={t("Shop online")}
            text={t("Order from our catalog, pay by KHQR, then pick up at a branch or have it delivered.")}
            badge={store ? store.open ? <Badge tone="success">{t("Open")}</Badge> : <Badge>{t("Closed")}</Badge> : null}
            action={t("Start shopping")}
          />
          <Tile
            href={ZONE_PATHS.app}
            icon={Smartphone}
            tone="primary"
            title={t("Mobile app")}
            text={t("The POS app for Android and iPhone: how to download and install it.")}
            badge={app?.versionName ? <Badge tone="primary">{t("Version {version}", { version: app.versionName })}</Badge> : appReady ? null : <Badge>{t("Coming soon")}</Badge>}
            action={t("Get the app")}
          />
        </div>

        <h2 className="mt-10 mb-3 flex items-center gap-1.5 text-xs font-semibold tracking-wide text-muted uppercase">
          <Lock className="size-3.5" aria-hidden />
          {t("For staff")}
        </h2>
        <div className="grid gap-4 md:grid-cols-2">
          <Tile
            href={ZONE_PATHS.cas}
            icon={ShoppingCart}
            tone="success"
            title={t("Till")}
            text={t("Sell, take cash and KHQR payments, print receipts, handle returns and online orders.")}
            badge={<Badge>{t("Staff sign-in")}</Badge>}
            action={t("Open the till")}
          />
          <Tile
            href={ZONE_PATHS.ctl}
            icon={LayoutDashboard}
            tone="neutral"
            title={t("Administration")}
            text={t("Catalog, stock, purchasing, customers, marketing, reports, accounting and settings.")}
            badge={<Badge>{t("Staff sign-in")}</Badge>}
            action={t("Open administration")}
          />
        </div>

        {!!store?.pickupBranches.length && (
          <section className="mt-12">
            <h2 className="mb-3 text-xs font-semibold tracking-wide text-muted uppercase">{t("Visit us")}</h2>
            <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {store.pickupBranches.map((b) => (
                <li key={b.id} className="flex gap-3 rounded-2xl border border-border bg-surface p-4">
                  <MapPin className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden />
                  <div className="min-w-0">
                    <div className="font-medium">{b.name}</div>
                    {b.address && <div className="text-sm text-muted">{b.address}</div>}
                  </div>
                </li>
              ))}
            </ul>
          </section>
        )}
      </main>
      <footer className="border-t border-border py-6 text-center text-xs text-muted">
        © {new Date().getFullYear()} {name}
      </footer>
    </>
  );
}

const TONES = {
  primary: "bg-primary-soft text-primary",
  success: "bg-success-soft text-success",
  neutral: "bg-surface-2 text-text",
};

/** One app: a whole-card link (another app, so a full page load). */
function Tile({ href, icon: Icon, tone, title, text, badge, action }: {
  href: string;
  icon: typeof ShoppingBag;
  tone: keyof typeof TONES;
  title: string;
  text: string;
  badge: React.ReactNode;
  action: string;
}) {
  return (
    <a
      href={href}
      className="group flex flex-col gap-4 rounded-3xl border border-border bg-surface p-6 transition-[box-shadow,border-color] hover:border-primary hover:shadow-lg focus-visible:outline-2 focus-visible:outline-primary"
    >
      <div className="flex items-start justify-between gap-3">
        <span className={cx("grid size-12 place-items-center rounded-2xl", TONES[tone])}>
          <Icon className="size-6" aria-hidden />
        </span>
        {badge}
      </div>
      <div>
        <h3 className="text-lg font-semibold">{title}</h3>
        <p className="mt-1 text-sm text-muted">{text}</p>
      </div>
      <span className="mt-auto inline-flex items-center gap-1.5 text-sm font-medium text-primary">
        {action}
        <ArrowRight className="size-4 transition-transform group-hover:translate-x-0.5" aria-hidden />
      </span>
    </a>
  );
}
