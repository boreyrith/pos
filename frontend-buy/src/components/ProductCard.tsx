"use client";

import { Package, Plus } from "lucide-react";
import Link from "next/link";
import { useState } from "react";

import { money } from "@pos/shared/utils/format";
import { useT } from "@pos/shared/i18n";

import { addToCart, imageUrl, useCart, type ShopProduct } from "@/lib/shop";

/** A product in the grid: picture, names, price, how many are left, and Add. */
export function ProductCard({ product: p, currency }: { product: ShopProduct; currency: string }) {
  const t = useT();
  const inCart = useCart().lines.find((l) => l.product.id === p.id)?.quantity ?? 0;
  const [full, setFull] = useState(false);
  const out = p.available != null && p.available <= 0;
  return (
    <div className="flex flex-col overflow-hidden rounded-2xl border border-border bg-surface">
      <Link href={`/products/${p.id}`} className="relative block aspect-square bg-surface-2">
        {p.hasImage ? (
          // eslint-disable-next-line @next/next/no-img-element -- served by our API, sized by CSS
          <img src={imageUrl(p)} alt="" className="size-full object-cover" loading="lazy" />
        ) : (
          <span className="grid size-full place-items-center text-muted">
            <Package className="size-10" aria-hidden />
          </span>
        )}
        {out && (
          <span className="absolute top-2 left-2 rounded-full bg-surface/90 px-2 py-0.5 text-xs font-medium text-danger">{t("Sold out")}</span>
        )}
      </Link>
      <div className="flex flex-1 flex-col gap-1 p-3">
        <Link href={`/products/${p.id}`} className="line-clamp-2 text-sm font-medium hover:underline">
          {p.name}
        </Link>
        {p.nameKm && <span lang="km" className="line-clamp-1 text-xs text-muted">{p.nameKm}</span>}
        <div className="mt-auto flex items-center justify-between gap-2 pt-2">
          <span className="font-semibold">
            {money(p.price, currency)}
            {p.unit && <span className="text-xs font-normal text-muted"> /{p.unit}</span>}
          </span>
          <button
            disabled={out || full}
            onClick={() => setFull(!addToCart(p))}
            aria-label={t("Add {name} to the cart", { name: p.name })}
            className="grid size-9 place-items-center rounded-full bg-primary text-on-primary hover:bg-primary-hover disabled:cursor-not-allowed disabled:opacity-40"
          >
            <Plus className="size-5" aria-hidden />
          </button>
        </div>
        {inCart > 0 && <span className="text-xs text-success">{t("{n} in the cart", { n: inCart })}</span>}
        {full && <span className="text-xs text-muted">{t("That's all we have")}</span>}
      </div>
    </div>
  );
}
