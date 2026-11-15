"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, Minus, Package, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { Button, ErrorNote, Spinner } from "@pos/shared/components/ui";
import { money } from "@pos/shared/utils/format";
import { useT } from "@pos/shared/i18n";

import { ShopHeader } from "@/components/ShopHeader";
import { addToCart, cartBranch, imageUrl, shopApi, useCart, useShopStore, type ShopProduct } from "@/lib/shop";

/** One product: picture, names, description, price, what is left, and how many to add. */
export function ProductView({ id }: { id: number }) {
  const t = useT();
  const router = useRouter();
  const store = useShopStore();
  const branch = cartBranch(useCart(), store.data);
  const product = useQuery({
    queryKey: ["shop", "product", id, branch],
    queryFn: () => shopApi<ShopProduct>(`/products/${id}?branchId=${branch}`),
    enabled: branch != null,
  });
  const [quantity, setQuantity] = useState(1);
  const p = product.data;
  const max = Math.min(p?.available ?? 50, 50);

  return (
    <>
      <ShopHeader />
      <main className="mx-auto max-w-5xl px-4 py-6">
        <Link href="/" className="mb-4 inline-flex items-center gap-1 text-sm text-muted hover:text-text">
          <ArrowLeft className="size-4" aria-hidden /> {t("Back to the shop")}
        </Link>
        {product.isPending || store.isPending ? (
          <Spinner />
        ) : product.error ? (
          <ErrorNote error={product.error} />
        ) : (
          p && store.data && (
            <div className="grid gap-8 md:grid-cols-2">
              <div className="aspect-square overflow-hidden rounded-3xl border border-border bg-surface-2">
                {p.hasImage ? (
                  // eslint-disable-next-line @next/next/no-img-element -- served by our API
                  <img src={imageUrl(p)} alt="" className="size-full object-cover" />
                ) : (
                  <span className="grid size-full place-items-center text-muted">
                    <Package className="size-16" aria-hidden />
                  </span>
                )}
              </div>
              <div className="flex flex-col gap-4">
                <div>
                  {p.categoryName && <p className="text-xs font-medium tracking-wide text-muted uppercase">{p.categoryName}</p>}
                  <h1 className="mt-1 text-2xl font-semibold">{p.name}</h1>
                  {p.nameKm && <p lang="km" className="text-muted">{p.nameKm}</p>}
                </div>
                <p className="text-2xl font-semibold">
                  {money(p.price, store.data.currency)}
                  {p.unit && <span className="text-base font-normal text-muted"> /{p.unit}</span>}
                </p>
                {p.description && <p className="text-sm whitespace-pre-line text-muted">{p.description}</p>}
                {p.available != null && (
                  <p className={p.available > 0 ? "text-sm text-success" : "text-sm text-danger"}>
                    {p.available > 0 ? t("{n} available", { n: p.available }) : t("Sold out")}
                  </p>
                )}
                {(p.available == null || p.available > 0) && (
                  <div className="flex items-center gap-3">
                    <div className="flex items-center rounded-xl border border-border bg-surface">
                      <button className="grid size-11 place-items-center disabled:opacity-40" aria-label={t("Fewer")}
                        disabled={quantity <= 1} onClick={() => setQuantity((q) => q - 1)}>
                        <Minus className="size-4" aria-hidden />
                      </button>
                      <span className="w-10 text-center font-semibold tabular-nums">{quantity}</span>
                      <button className="grid size-11 place-items-center disabled:opacity-40" aria-label={t("More")}
                        disabled={quantity >= max} onClick={() => setQuantity((q) => q + 1)}>
                        <Plus className="size-4" aria-hidden />
                      </button>
                    </div>
                    <Button size="lg" className="flex-1" onClick={() => {
                      addToCart(p, quantity);
                      router.push("/cart");
                    }}>
                      {t("Add to cart")}
                    </Button>
                  </div>
                )}
              </div>
            </div>
          )
        )}
      </main>
    </>
  );
}
