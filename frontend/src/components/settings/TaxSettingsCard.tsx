"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import {
  Button,
  Card,
  ErrorNote,
  Field,
  Input,
  Spinner,
  cx,
} from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { dateTime, money } from "@/lib/format";
import { calculateTotals } from "@/lib/pricing";
import { useStoreSettings } from "@/lib/queries";
import type { TaxMode, TaxSettings } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

const MODES: { mode: TaxMode; title: string; body: string }[] = [
  {
    mode: "NONE",
    title: msg("No VAT"),
    body: msg(
      "No tax is calculated or charged. Customers pay the listed price.",
    ),
  },
  {
    mode: "INCLUSIVE",
    title: msg("VAT included"),
    body: msg(
      "Listed prices already include VAT. The tax is worked out of the price: VAT = price × rate ÷ (100 + rate).",
    ),
  },
  {
    mode: "EXCLUSIVE",
    title: msg("VAT setup value"),
    body: msg(
      "Listed prices exclude VAT. VAT = taxable subtotal × rate ÷ 100 is added on top at checkout.",
    ),
  },
];

/** Settings > Taxes: the store-wide tax mode, VAT rate, label, maximum rate and scope. Admins change it. */
export function TaxSettingsCard() {
  const settings = useQuery({
    queryKey: ["settings", "tax"],
    queryFn: () => api<TaxSettings>("/settings/tax"),
  });
  if (settings.isPending) return <Spinner />;
  if (settings.error) return <ErrorNote error={settings.error} />;
  return (
    <TaxSettingsForm
      key={settings.data.updatedAt + settings.data.vatRate}
      settings={settings.data}
    />
  );
}

const pct = (rate: number) => String(Number((rate * 100).toFixed(2)));

function TaxSettingsForm({ settings }: { settings: TaxSettings }) {
  const t = useT();
  const admin = hasPermission(useUser(), "SETTINGS_TAX");
  const store = useStoreSettings();
  const queryClient = useQueryClient();
  const [form, setForm] = useState({
    mode: settings.mode,
    label: settings.label,
    vatRate: pct(settings.vatRate),
    maxRate: pct(settings.maxRate),
    taxProducts: settings.taxProducts,
    taxServices: settings.taxServices,
    taxItems: settings.taxItems,
    taxPackages: settings.taxPackages,
    taxId: settings.taxId ?? "",
  });
  const set = <K extends keyof typeof form>(k: K, v: (typeof form)[K]) =>
    setForm((f) => ({ ...f, [k]: v }));

  const rate = Number(form.vatRate);
  const max = Number(form.maxRate);
  const rateError =
    form.vatRate.trim() === "" || !Number.isFinite(rate)
      ? t("Enter a number")
      : rate < 0
        ? t("Can't be negative")
        : rate > max
          ? t("At most the maximum rate ({max}%)", { max: form.maxRate })
          : undefined;
  const maxError = !(max > 0 && max < 100) ? t("Between 0 and 100") : undefined;
  const invalid =
    !form.label.trim() || (form.mode !== "NONE" && !!rateError) || !!maxError;

  const save = useMutation({
    mutationFn: () =>
      api<TaxSettings>("/settings/tax", {
        method: "PUT",
        json: {
          ...form,
          label: form.label.trim(),
          vatRate: Number((rate / 100).toFixed(4)),
          maxRate: Number((max / 100).toFixed(4)),
          taxId: form.taxId.trim() || null,
        },
      }),
    onSuccess: () => {
      for (const key of ["settings", "store", "tax-rates", "offerings"])
        queryClient.invalidateQueries({ queryKey: [key] });
    },
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};

  const changedMode = form.mode !== settings.mode;
  const changedRate = form.mode !== "NONE" && rate / 100 !== settings.vatRate;
  const vat = form.label.trim() || "VAT";

  // A worked example, using the same calculation as checkout.
  const example = (mode: TaxMode) => {
    const r = mode === "NONE" ? 0 : (rateError ? 0 : rate) / 100;
    const x = calculateTotals(
      [
        {
          unitPrice: 10,
          quantity: 1,
          tax: {
            rateId: 0,
            name: vat,
            rate: r,
            included: mode === "INCLUSIVE",
          },
        },
      ],
      null,
      0,
    );
    const currency = store.data?.currency ?? "USD";
    const vars = {
      price: money(10, currency),
      total: money(x.total, currency),
      vat,
      tax: money(x.taxAdded + x.taxIncluded, currency),
    };
    if (mode === "NONE")
      return t("Price {price} → customer pays {total}, no {vat}", vars);
    return t(
      "Price {price} → customer pays {total}, of which {vat} {tax}",
      vars,
    );
  };

  return (
    <Card className="p-5">
      <form
        className="flex flex-col gap-5"
        onSubmit={(e) => {
          e.preventDefault();
          if (
            (changedMode || changedRate) &&
            !confirm(
              (form.mode === "NONE"
                ? t("New sales will use {mode}.", {
                    mode: t(MODES.find((m) => m.mode === form.mode)!.title),
                  })
                : t("New sales will use {mode} at {rate}%.", {
                    mode: t(MODES.find((m) => m.mode === form.mode)!.title),
                    rate,
                  })) +
                " " +
                t(
                  "Orders already made keep the tax they were sold with. Continue?",
                ),
            )
          )
            return;
          save.mutate();
        }}
      >
        {!admin && (
          <p className="rounded-lg bg-surface-2 px-3 py-2 text-sm text-muted">
            {t("Your role can view tax settings but not change them.")}
          </p>
        )}
        <fieldset disabled={!admin} className="flex flex-col gap-5">
          <div>
            <legend className="mb-2 text-sm font-semibold">
              {t("Tax mode")}
            </legend>
            <div role="radiogroup" className="grid gap-3 md:grid-cols-3">
              {MODES.map((m) => (
                <label
                  key={m.mode}
                  className={cx(
                    "flex cursor-pointer flex-col gap-1 rounded-xl border p-4 text-sm",
                    form.mode === m.mode
                      ? "border-primary bg-primary-soft"
                      : "border-border hover:bg-surface-2",
                  )}
                >
                  <span className="flex items-center gap-2 font-medium">
                    <input
                      type="radio"
                      name="taxMode"
                      checked={form.mode === m.mode}
                      onChange={() => set("mode", m.mode)}
                    />
                    {t(m.title)}
                  </span>
                  <span className="text-xs text-muted">{t(m.body)}</span>
                  <span className="mt-1 text-xs tabular-nums">
                    {example(m.mode)}
                  </span>
                </label>
              ))}
            </div>
          </div>

          {/* VAT details are hidden while the store charges no VAT; their values are kept for when it does. */}
          {form.mode !== "NONE" && (
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <Field
                label={t("Display name")}
                error={errors.label}
                hint={t("On the sell screen, receipts and reports")}
              >
                <Input
                  value={form.label}
                  maxLength={30}
                  onChange={(e) => set("label", e.target.value)}
                />
              </Field>
              {
                <Field
                  label={t("{vat} rate (%)", { vat })}
                  error={errors.vatRate ?? rateError}
                  hint={
                    settings.defaultRateName
                      ? t("The default rate (“{defaultRateName}”)", {
                          defaultRateName: settings.defaultRateName,
                        })
                      : undefined
                  }
                >
                  <Input
                    inputMode="decimal"
                    value={form.vatRate}
                    onChange={(e) =>
                      set("vatRate", e.target.value.replace(/[^\d.-]/g, ""))
                    }
                  />
                </Field>
              }
              <Field
                label={t("Maximum rate (%)")}
                error={errors.maxRate ?? maxError}
                hint={t("No tax rate can be set above this")}
              >
                <Input
                  inputMode="decimal"
                  value={form.maxRate}
                  onChange={(e) =>
                    set("maxRate", e.target.value.replace(/[^\d.]/g, ""))
                  }
                />
              </Field>
              <Field
                label={t("VAT TIN / registration no.")}
                error={errors.taxId}
                hint={t("Printed on receipts")}
              >
                <Input
                  value={form.taxId}
                  maxLength={50}
                  onChange={(e) => set("taxId", e.target.value)}
                />
              </Field>
            </div>
          )}

          {form.mode !== "NONE" && (
            <div>
              <div className="mb-2 text-sm font-semibold">
                {t("Applies to")}
              </div>
              <div className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
                {(
                  [
                    ["taxProducts", msg("Products")],
                    ["taxServices", msg("Services")],
                    ["taxItems", msg("Items sold at the till")],
                    ["taxPackages", msg("Packages")],
                  ] as const
                ).map(([k, label]) => (
                  <label key={k} className="flex items-center gap-2">
                    <input
                      type="checkbox"
                      checked={form[k]}
                      onChange={(e) => set(k, e.target.checked)}
                    />
                    {t(label)}
                  </label>
                ))}
              </div>
              <p className="mt-2 text-xs text-muted">
                {t(
                  "Unticked types are sold without {vat}. Entries with a 0% rate (e.g. Exempt) are never taxed. A package is taxed once, at its own rate; what is inside it is not taxed again.",
                  { vat },
                )}
              </p>
            </div>
          )}
        </fieldset>

        <ErrorNote error={save.error} />
        {save.isSuccess && !save.isPending && (
          <p className="text-sm text-success">
            {t("Saved. New sales use these settings.")}
          </p>
        )}
        <div className="flex items-center justify-between gap-3">
          <span className="text-xs text-muted">
            {t("Last changed {updatedAt}", {
              updatedAt: dateTime(settings.updatedAt, store.data?.timezone),
            })}
            {settings.updatedBy
              ? t(" by {updatedBy}", { updatedBy: settings.updatedBy })
              : ""}
          </span>
          {admin && (
            <Button type="submit" loading={save.isPending} disabled={invalid}>
              {t("Save tax settings")}
            </Button>
          )}
        </div>
      </form>
    </Card>
  );
}
