"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useState } from "react";

import {
  Badge,
  Button,
  Card,
  ErrorNote,
  Field,
  Input,
  Modal,
  Spinner,
} from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { taxLabel } from "@/lib/pricing";
import { useStoreSettings, useTaxRates } from "@/lib/queries";
import type { TaxRate } from "@/lib/types";
import { translate, useT } from "@/lib/i18n";

import { TaxSettingsCard } from "./TaxSettingsCard";

/** Tax rates. Admins add and edit; a new rate applies to sales from then on. */
export function TaxesPanel() {
  const t = useT();
  const admin = hasPermission(useUser(), "SETTINGS_TAX");
  const rates = useTaxRates();
  const store = useStoreSettings();
  const noVat = store.data?.taxMode === "NONE";
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<TaxRate | "new" | null>(null);

  const makeDefault = useMutation({
    mutationFn: (x: TaxRate) =>
      api<TaxRate>(`/tax-rates/${x.id}`, {
        method: "PUT",
        json: { name: x.name, rate: x.rate, defaultRate: true },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["tax-rates"] });
      queryClient.invalidateQueries({ queryKey: ["settings", "tax"] });
    },
  });

  return (
    <div className="flex flex-col gap-4">
      <TaxSettingsCard />
      {/* Tax rates are hidden while the store charges no VAT. */}
      {!noVat && (
        <>
          <div>
            <h3 className="text-sm font-semibold">{t("Tax rates")}</h3>
            <p className="text-sm text-muted">
              {t(
                "Each catalog entry has one rate; the default rate is the {store} rate above. Use a 0% rate for exempt or zero-rated goods. Orders keep the rates they were sold at.",
                { store: store.data?.taxLabel ?? "VAT" },
              )}
            </p>
          </div>
          {admin && (
            <div className="flex justify-end">
              <Button onClick={() => setEditing("new")}>
                <Plus className="size-4" aria-hidden /> {t("Add tax rate")}
              </Button>
            </div>
          )}
          <ErrorNote error={makeDefault.error} />
          <Card className="overflow-x-auto">
            {rates.isPending ? (
              <Spinner />
            ) : (
              <table className="w-full text-sm">
                <thead className="border-b border-border text-left text-xs text-muted">
                  <tr>
                    <th className="px-4 py-3 font-medium">{t("Name")}</th>
                    <th className="px-4 py-3 text-right font-medium">
                      {t("Rate")}
                    </th>
                    <th className="px-4 py-3 text-right font-medium">
                      {t("Used by")}
                    </th>
                    <th className="px-4 py-3" aria-label={t("Actions")} />
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {rates.data?.map((x) => (
                    <tr key={x.id} className={x.active ? "" : "text-muted"}>
                      <td className="px-4 py-3">
                        <span className="flex items-center gap-2 font-medium">
                          {x.name}
                          {x.defaultRate && (
                            <Badge tone="primary">{t("Default")}</Badge>
                          )}
                          {!x.active && <Badge>{t("Off")}</Badge>}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-right tabular-nums">
                        {Number((x.rate * 100).toFixed(2))}%
                      </td>
                      <td className="px-4 py-3 text-right text-muted tabular-nums">
                        {x.usage === 1
                          ? t("1 entry")
                          : t("{n} entries", { n: x.usage })}
                      </td>
                      <td className="px-4 py-3 text-right whitespace-nowrap">
                        {admin && !x.defaultRate && x.active && (
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => makeDefault.mutate(x)}
                          >
                            {t("Make default")}
                          </Button>
                        )}
                        {admin && (
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => setEditing(x)}
                          >
                            {t("Edit")}
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        </>
      )}
      {editing && (
        <TaxRateDialog
          rate={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
        />
      )}
    </div>
  );
}

function TaxRateDialog({
  rate,
  onClose,
}: {
  rate: TaxRate | null;
  onClose: () => void;
}) {
  const t = useT();
  const queryClient = useQueryClient();
  const [name, setName] = useState(rate?.name ?? "");
  const [percent, setPercent] = useState(
    rate ? String(Number((rate.rate * 100).toFixed(2))) : "",
  );
  const [active, setActive] = useState(rate?.active ?? true);
  const value = Number(percent);
  const changed = rate !== null && value / 100 !== rate.rate;

  const save = useMutation({
    mutationFn: () => {
      const body = { name, rate: value / 100, active };
      return rate
        ? api<TaxRate>(`/tax-rates/${rate.id}`, { method: "PUT", json: body })
        : api<TaxRate>("/tax-rates", { method: "POST", json: body });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["tax-rates"] });
      queryClient.invalidateQueries({ queryKey: ["offerings"] });
      queryClient.invalidateQueries({ queryKey: ["settings", "tax"] });
      onClose();
    },
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};

  return (
    <Modal
      open
      onClose={onClose}
      title={rate ? t("Edit {name}", { name: rate.name }) : t("Add tax rate")}
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (
            changed &&
            rate.usage > 0 &&
            !confirm(
              translate(
                "{usage} catalog entries use {name}. New sales will charge {value}%. Continue?",
                { usage: rate.usage, name: rate.name, value },
              ),
            )
          )
            return;
          save.mutate();
        }}
      >
        <Field
          label={t("Name")}
          error={errors.name}
          hint={t("e.g. VAT, Zero-rated, Specific tax, Exempt")}
        >
          <Input
            autoFocus
            required
            maxLength={50}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
        <Field
          label={t("Rate (%)")}
          error={errors.rate}
          hint={t("0 for exempt or zero-rated goods")}
        >
          <Input
            required
            inputMode="decimal"
            value={percent}
            onChange={(e) => setPercent(e.target.value.replace(/[^\d.]/g, ""))}
          />
        </Field>
        {name && percent !== "" && (
          <p className="text-sm text-muted">
            {t("Shown as “{taxLabel}”", {
              taxLabel: taxLabel({ name, rate: value / 100 }),
            })}
          </p>
        )}
        {rate && !rate.defaultRate && (
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={active}
              onChange={(e) => setActive(e.target.checked)}
            />
            {t(
              "Can be chosen for catalog entries (entries already using it keep it)",
            )}
          </label>
        )}
        <ErrorNote error={save.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("Cancel")}
          </Button>
          <Button
            type="submit"
            loading={save.isPending}
            disabled={
              !name.trim() || percent === "" || !(value >= 0 && value < 100)
            }
          >
            {rate ? t("Save") : t("Add tax rate")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
