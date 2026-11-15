"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import { Button, Card, ErrorNote, Field, Input, Select, Spinner } from "@pos/shared/components/ui";
import { ApiError, api } from "@pos/shared/api/client";
import { hasPermission, useUser } from "@pos/shared/auth/auth";
import { useBranch } from "@pos/shared/auth/branch";
import { useStoreSettings } from "@pos/shared/api/queries";
import { dateTime } from "@pos/shared/utils/format";
import type { ShopSettings } from "@pos/shared/types";
import { useT } from "@pos/shared/i18n";

/**
 * Settings > Online shop: open or closed, pickup, and delivery (from which branch, its fee, free from
 * how much). What is sold online is chosen per product in the Catalog ("Sell in the online shop").
 */
export function ShopPanel() {
  const settings = useQuery({ queryKey: ["settings", "shop"], queryFn: () => api<ShopSettings>("/settings/shop") });
  if (settings.isPending) return <Spinner />;
  if (settings.error) return <ErrorNote error={settings.error} />;
  return <ShopForm key={settings.data.updatedAt} settings={settings.data} />;
}

function ShopForm({ settings }: { settings: ShopSettings }) {
  const t = useT();
  const queryClient = useQueryClient();
  const admin = hasPermission(useUser(), "SETTINGS_COMPANY");
  const { branches } = useBranch();
  const currency = useStoreSettings().data?.currency ?? "";
  const sellingBranches = branches.filter((b) => b.active && b.storages.some((s) => s.selling && s.active));
  const [form, setForm] = useState({
    enabled: settings.enabled,
    pickupEnabled: settings.pickupEnabled,
    deliveryEnabled: settings.deliveryEnabled,
    deliveryBranchId: settings.deliveryBranchId ? String(settings.deliveryBranchId) : "",
    deliveryFee: settings.deliveryFee == null ? "0" : String(settings.deliveryFee),
    freeDeliveryFrom: settings.freeDeliveryFrom == null ? "" : String(settings.freeDeliveryFrom),
  });
  const set = <K extends keyof typeof form>(k: K, v: (typeof form)[K]) => setForm((f) => ({ ...f, [k]: v }));
  const save = useMutation({
    mutationFn: () =>
      api<ShopSettings>("/settings/shop", {
        method: "PUT",
        json: {
          enabled: form.enabled,
          pickupEnabled: form.pickupEnabled,
          deliveryEnabled: form.deliveryEnabled,
          deliveryBranchId: form.deliveryBranchId ? Number(form.deliveryBranchId) : null,
          deliveryFee: Number(form.deliveryFee || 0),
          freeDeliveryFrom: form.freeDeliveryFrom === "" ? null : Number(form.freeDeliveryFrom),
        },
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["settings", "shop"] }),
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  const check = (label: string, hint: string, key: "enabled" | "pickupEnabled" | "deliveryEnabled") => (
    <label className="flex items-start gap-2.5 text-sm">
      <input type="checkbox" className="mt-0.5 size-4 accent-primary" checked={form[key]} disabled={!admin}
        onChange={(e) => set(key, e.target.checked)} />
      <span>
        <span className="font-medium">{t(label)}</span>
        <span className="block text-xs text-muted">{t(hint)}</span>
      </span>
    </label>
  );

  return (
    <form
      className="flex flex-col gap-5"
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate();
      }}
    >
      {!admin && <p className="rounded-lg bg-surface-2 px-3 py-2 text-sm text-muted">{t("Your role can view these settings but not change them.")}</p>}
      <Card className="flex flex-col gap-4 p-5">
        <h2 className="text-sm font-semibold">{t("Online shop")}</h2>
        {check("Open for orders", "Customers can order and pay by KHQR. Off: the shop shows that it is closed.", "enabled")}
        {check("Pickup", "Customers pick a branch and collect their order there.", "pickupEnabled")}
        <p className="text-xs text-muted">
          {t("What the shop sells: tick \"Sell in the online shop\" on each product in the Catalog. Prices, promotions and coupon codes are the same as at the till.")}
        </p>
      </Card>

      <Card className="flex flex-col gap-4 p-5">
        <h2 className="text-sm font-semibold">{t("Delivery")}</h2>
        {check("Delivery", "Orders are sent to the customer's address from one branch.", "deliveryEnabled")}
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label={t("Sent from")} error={errors.deliveryBranchId}>
            <Select value={form.deliveryBranchId} disabled={!admin} onChange={(e) => set("deliveryBranchId", e.target.value)}>
              <option value="">{t("Choose a branch")}</option>
              {sellingBranches.map((b) => (
                <option key={b.id} value={b.id}>{b.name}</option>
              ))}
            </Select>
          </Field>
          <Field label={t("Delivery fee ({currency})", { currency })} error={errors.deliveryFee}>
            <Input type="number" min="0" step="0.01" inputMode="decimal" value={form.deliveryFee} disabled={!admin}
              onChange={(e) => set("deliveryFee", e.target.value)} />
          </Field>
          <Field label={t("Free delivery from ({currency})", { currency })} hint={t("Empty: never free")} error={errors.freeDeliveryFrom}>
            <Input type="number" min="0" step="0.01" inputMode="decimal" value={form.freeDeliveryFrom} disabled={!admin}
              onChange={(e) => set("freeDeliveryFrom", e.target.value)} />
          </Field>
        </div>
        <p className="text-xs text-muted">
          {t("The fee is charged as the \"Delivery\" service, so it is taxed, receipted and refunded like any line.")}
        </p>
      </Card>

      <ErrorNote error={save.error} />
      <div className="flex items-center justify-between gap-3">
        <span className="text-xs text-muted">
          {t("Last changed {updatedAt}", { updatedAt: dateTime(settings.updatedAt) })}
          {settings.updatedBy ? t(" by {updatedBy}", { updatedBy: settings.updatedBy }) : ""}
        </span>
        {admin && (
          <Button type="submit" loading={save.isPending}>
            {t("Save settings")}
          </Button>
        )}
      </div>
    </form>
  );
}
