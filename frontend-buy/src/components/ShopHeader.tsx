"use client";

import { ShoppingCart, User } from "lucide-react";
import Link from "next/link";

import { PublicHeader } from "@pos/shared/components/public/PublicHeader";
import { useT } from "@pos/shared/i18n";

import { useAccount, useCart, useShopStore } from "@/lib/shop";

/** The top of every shop page: the public header (store, language) with the account and the cart and its count. */
export function ShopHeader() {
  const t = useT();
  const store = useShopStore().data;
  const account = useAccount().data;
  const count = useCart().lines.reduce((n, l) => n + l.quantity, 0);
  return (
    <PublicHeader name={store?.name ?? ""} inApp>
      <Link
        href="/account"
        className="flex h-10 items-center gap-2 rounded-xl px-3 text-sm font-medium text-muted hover:bg-surface-2 hover:text-text"
        aria-label={account ? t("My account") : t("Sign in")}
      >
        <User className="size-5" aria-hidden />
        <span className="hidden max-w-32 truncate sm:inline">{account ? account.name : t("Sign in")}</span>
      </Link>
      <Link
        href="/cart"
        className="relative flex h-10 items-center gap-2 rounded-xl bg-primary px-3.5 text-sm font-medium text-on-primary hover:bg-primary-hover"
      >
        <ShoppingCart className="size-5" aria-hidden />
        <span className="hidden sm:inline">{t("Cart")}</span>
        {count > 0 && (
          <span className="grid h-5 min-w-5 place-items-center rounded-full bg-surface px-1 text-[11px] font-bold text-primary">{count}</span>
        )}
      </Link>
    </PublicHeader>
  );
}
