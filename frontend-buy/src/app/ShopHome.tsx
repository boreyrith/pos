"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { Search, Store } from "lucide-react";
import { useEffect, useState } from "react";

import { Button, ErrorNote, Input, Spinner, cx } from "@pos/shared/components/ui";
import { useT } from "@pos/shared/i18n";

import { FulfilmentPicker } from "@/components/FulfilmentPicker";
import { ProductCard } from "@/components/ProductCard";
import { ShopHeader } from "@/components/ShopHeader";
import { cartBranch, shopApi, useCart, useShopStore, type ProductPage } from "@/lib/shop";

/** The shop's front page: how to get the order, search, categories and what is for sale. */
export function ShopHome() {
  const t = useT();
  const store = useShopStore();
  const cart = useCart();
  const branch = cartBranch(cart, store.data);
  const [q, setQ] = useState("");
  const [term, setTerm] = useState("");
  const [category, setCategory] = useState<number | null>(null);
  useEffect(() => {
    const id = setTimeout(() => setTerm(q.trim()), 300);
    return () => clearTimeout(id);
  }, [q]);
  const categories = useQuery({
    queryKey: ["shop", "categories"],
    queryFn: () => shopApi<{ id: number; name: string }[]>("/categories"),
    enabled: !!store.data?.open,
  });
  const products = useInfiniteQuery({
    queryKey: ["shop", "products", branch, term, category],
    queryFn: ({ pageParam }) =>
      shopApi<ProductPage>(
        `/products?branchId=${branch}&page=${pageParam}${term ? `&q=${encodeURIComponent(term)}` : ""}${category ? `&categoryId=${category}` : ""}`,
      ),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
    enabled: !!store.data?.open && branch != null,
  });

  return (
    <>
      <ShopHeader />
      <main className="mx-auto max-w-6xl px-4 py-6">
        {store.isPending ? (
          <Spinner />
        ) : store.error ? (
          <ErrorNote error={store.error} />
        ) : !store.data.open ? (
          <div className="mx-auto mt-16 max-w-md text-center">
            <Store className="mx-auto size-12 text-muted" aria-hidden />
            <h1 className="mt-4 text-xl font-semibold">{t("The online shop is closed right now")}</h1>
            <p className="mt-2 text-sm text-muted">{t("Please visit us in store, or come back later.")}</p>
          </div>
        ) : (
          <div className="flex flex-col gap-5">
            <FulfilmentPicker store={store.data} />
            <div className="relative">
              <Search className="pointer-events-none absolute top-1/2 left-3 size-5 -translate-y-1/2 text-muted" aria-hidden />
              <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("Search products")} className="h-12 w-full pl-10 text-base" />
            </div>
            {!!categories.data?.length && (
              <div className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1">
                {[{ id: null as number | null, name: t("All") }, ...categories.data].map((c) => (
                  <button
                    key={c.id ?? "all"}
                    onClick={() => setCategory(c.id)}
                    className={cx(
                      "h-9 shrink-0 rounded-full border px-4 text-sm font-medium",
                      category === c.id ? "border-primary bg-primary text-on-primary" : "border-border bg-surface hover:bg-surface-2",
                    )}
                  >
                    {c.name}
                  </button>
                ))}
              </div>
            )}
            {products.isPending ? (
              <Spinner />
            ) : products.error ? (
              <ErrorNote error={products.error} />
            ) : products.data.pages[0].items.length === 0 ? (
              <p className="py-16 text-center text-sm text-muted">{term || category ? t("Nothing matches.") : t("Nothing for sale online yet.")}</p>
            ) : (
              <>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
                  {products.data.pages.flatMap((p) => p.items).map((p) => (
                    <ProductCard key={p.id} product={p} currency={store.data.currency} />
                  ))}
                </div>
                {products.hasNextPage && (
                  <Button variant="secondary" className="self-center" loading={products.isFetchingNextPage} onClick={() => products.fetchNextPage()}>
                    {t("Show more")}
                  </Button>
                )}
              </>
            )}
          </div>
        )}
      </main>
    </>
  );
}
