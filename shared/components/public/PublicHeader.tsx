"use client";

import Link from "next/link";

import { StoreLogo } from "../StoreLogo";
import { setLang, useLang } from "../../i18n";

/**
 * The top of the public pages (the portal, the mobile app page, the shop): the store, which leads to the
 * portal (or, with `inApp`, to this app's home, as in the shop), the language, then `children` (the
 * shop's account and cart).
 */
export function PublicHeader({ name, inApp = false, children }: { name: string; inApp?: boolean; children?: React.ReactNode }) {
  const lang = useLang();
  const brand = (
    <>
      <StoreLogo className="size-9 shrink-0 rounded-xl ring-1 ring-border" />
      <span className="truncate font-semibold">{name}</span>
    </>
  );
  return (
    <header className="sticky top-0 z-20 border-b border-border bg-surface/90 backdrop-blur-xl">
      <div className="mx-auto flex h-16 max-w-6xl items-center gap-3 px-4">
        {inApp ? (
          <Link href="/" className="flex min-w-0 items-center gap-2.5">{brand}</Link>
        ) : (
          // The portal is another app: a full page load.
          <a href="/" className="flex min-w-0 items-center gap-2.5">{brand}</a>
        )}
        <div className="flex-1" />
        <button
          onClick={() => setLang(lang === "en" ? "km" : "en")}
          className="h-10 rounded-xl px-3 text-sm font-medium text-muted hover:bg-surface-2 hover:text-text"
          lang={lang === "en" ? "km" : "en"}
        >
          {lang === "en" ? "ខ្មែរ" : "English"}
        </button>
        {children}
      </div>
    </header>
  );
}
