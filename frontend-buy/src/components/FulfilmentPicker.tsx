"use client";

import { Bike, ShoppingBag } from "lucide-react";

import { Select, cx } from "@pos/shared/components/ui";
import { money } from "@pos/shared/utils/format";
import { useT } from "@pos/shared/i18n";

import { cartBranch, setCart, useCart, type Fulfilment, type ShopStore } from "@/lib/shop";

/** Pickup at a branch or delivery: it decides which branch's stock the shop shows. */
export function FulfilmentPicker({ store }: { store: ShopStore }) {
  const t = useT();
  const cart = useCart();
  const options: { key: Fulfilment; label: string; icon: typeof Bike; on: boolean }[] = [
    { key: "PICKUP", label: "Pick up", icon: ShoppingBag, on: store.pickupEnabled },
    { key: "DELIVERY", label: "Delivery", icon: Bike, on: store.delivery.enabled },
  ];
  const available = options.filter((o) => o.on);
  const fulfilment = available.some((o) => o.key === cart.fulfilment) ? cart.fulfilment : available[0]?.key;
  const branch = cartBranch(cart, store);
  return (
    <div className="flex flex-wrap items-center gap-2">
      {available.length > 1 && (
        <div className="inline-flex rounded-xl border border-border bg-surface p-1" role="radiogroup" aria-label={t("How you get your order")}>
          {available.map((o) => (
            <button
              key={o.key}
              role="radio"
              aria-checked={fulfilment === o.key}
              onClick={() => setCart((c) => ({ ...c, fulfilment: o.key }))}
              className={cx(
                "flex h-9 items-center gap-1.5 rounded-lg px-3 text-sm font-medium",
                fulfilment === o.key ? "bg-primary-soft text-primary" : "text-muted hover:text-text",
              )}
            >
              <o.icon className="size-4" aria-hidden />
              {t(o.label)}
            </button>
          ))}
        </div>
      )}
      {fulfilment === "PICKUP" && store.pickupBranches.length > 1 && (
        <Select
          aria-label={t("Pick up at")}
          value={branch ?? ""}
          onChange={(e) => setCart((c) => ({ ...c, branchId: Number(e.target.value) }))}
        >
          {store.pickupBranches.map((b) => (
            <option key={b.id} value={b.id}>
              {t("Pick up at {branch}", { branch: b.name })}
            </option>
          ))}
        </Select>
      )}
      {fulfilment === "PICKUP" && store.pickupBranches.length === 1 && (
        <span className="text-sm text-muted">{t("Pick up at {branch}", { branch: store.pickupBranches[0].name })}</span>
      )}
      {fulfilment === "DELIVERY" && store.delivery.fee != null && (
        <span className="text-sm text-muted">
          {t("Delivery {fee}", { fee: money(store.delivery.fee, store.currency) })}
          {store.delivery.freeFrom != null && ` · ${t("free from {amount}", { amount: money(store.delivery.freeFrom, store.currency) })}`}
        </span>
      )}
    </div>
  );
}
