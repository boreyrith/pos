"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import { useState } from "react";

import {
  Button,
  ErrorNote,
  Field,
  Input,
  Modal,
  Select,
  Spinner,
  cx,
} from "@/components/ui";
import { ApiError, api } from "@/lib/api";
import { useBranch } from "@/lib/branch";

import { BarcodesField } from "./BarcodesField";
import { ImageField, type ImageChange } from "./ImageField";
import { type ContentRow, PackageContents } from "./PackageContents";
import { qty } from "@/lib/format";
import { taxLabel } from "@/lib/pricing";
import {
  offeringImageUrl,
  useCategories,
  useStoreSettings,
  useTaxRates,
  useUnits,
} from "@/lib/queries";
import type { Offering, OfferingType, Page, PricingMode } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

const NEW_TITLES: Record<OfferingType, string> = {
  PRODUCT: msg("New product"),
  SERVICE: msg("New service"),
  ITEM: msg("New item"),
  PACKAGE: msg("New package"),
};
const ADD_LABELS: Record<OfferingType, string> = {
  PRODUCT: msg("Add product"),
  SERVICE: msg("Add service"),
  ITEM: msg("Add item"),
  PACKAGE: msg("Add package"),
};

/** Create or edit a catalog entry. Editing loads the full entry first, for a service's parts. */
export function OfferingDialog({
  type,
  offeringId,
  onClose,
}: {
  type: OfferingType;
  offeringId: number | null;
  onClose: () => void;
}) {
  const existing = useQuery({
    queryKey: ["offerings", "detail", offeringId],
    queryFn: () => api<Offering>(`/offerings/${offeringId}`),
    enabled: offeringId !== null,
  });
  const t = useT();
  const title = existing.data
    ? t("Edit {name}", { name: existing.data.name })
    : t(NEW_TITLES[type]);

  return (
    <Modal open onClose={onClose} title={title} wide>
      {offeringId !== null && existing.isPending ? (
        <Spinner />
      ) : existing.error ? (
        <ErrorNote error={existing.error} />
      ) : (
        <OfferingForm
          type={existing.data?.type ?? type}
          offering={existing.data ?? null}
          onClose={onClose}
        />
      )}
    </Modal>
  );
}

interface PartRow {
  itemId: number;
  quantity: string;
}

interface PackRow {
  unitId: number;
  factor: string;
}

function OfferingForm({
  type,
  offering,
  onClose,
}: {
  type: OfferingType;
  offering: Offering | null;
  onClose: () => void;
}) {
  const t = useT();
  const queryClient = useQueryClient();
  const categories = useCategories();
  const { current: branch, sellingStorage } = useBranch();
  const store = useStoreSettings();
  const taxRates = useTaxRates();
  const service = type === "SERVICE";
  const pkg = type === "PACKAGE";
  const stocked = type === "PRODUCT" || type === "ITEM";
  const [contents, setContents] = useState<ContentRow[]>(
    offering?.packageInfo?.lines?.map((l) => ({
      componentId: l.componentId,
      name: l.name,
      type: l.type,
      quantity: String(l.quantity),
      unitId: l.unitId,
    })) ?? [],
  );

  const [form, setForm] = useState({
    name: offering?.name ?? "",
    nameKm: offering?.nameKm ?? "",
    description: offering?.description ?? "",
    sku: offering?.sku ?? "",
    unitId: offering?.unitId ? String(offering.unitId) : "",
    categoryId: offering?.categoryId ? String(offering.categoryId) : "",
    price: offering ? String(offering.price) : "",
    cost: offering ? String(offering.cost) : "",
    stock: "0",
    lowStockThreshold: String(offering?.lowStockThreshold ?? 5),
    taxRateId: offering ? String(offering.taxRateId) : "",
    active: offering?.active ?? true,
    sellable: offering?.sellable ?? false,
    pricing: (offering?.pricing ?? "FIXED") as PricingMode,
    durationMinutes: offering?.durationMinutes
      ? String(offering.durationMinutes)
      : "",
  });
  const [packs, setPacks] = useState<PackRow[]>(
    offering?.packs?.map((p) => ({
      unitId: p.unitId,
      factor: String(p.factor),
    })) ?? [],
  );
  const units = useUnits();
  const unitList = units.data ?? [];
  const unitId =
    form.unitId ||
    String(
      unitList.find((u) => u.symbol === (pkg ? "set" : "pcs"))?.id ??
        unitList.find((u) => u.symbol === "pcs")?.id ??
        "",
    );
  const baseSymbol =
    unitList.find((u) => String(u.id) === unitId)?.symbol ?? "";
  const unusedPackUnits = unitList.filter(
    (u) => String(u.id) !== unitId && !packs.some((p) => p.unitId === u.id),
  );
  const [parts, setParts] = useState<PartRow[]>(
    offering?.components?.map((c) => ({
      itemId: c.itemId,
      quantity: String(c.quantity),
    })) ?? [],
  );
  const set = (key: keyof typeof form) => (value: string | boolean) =>
    setForm((f) => ({ ...f, [key]: value }));
  // Every barcode, the main one (printed on labels) first.
  const [barcodes, setBarcodes] = useState<string[]>(offering?.barcodes ?? (offering?.barcode ? [offering.barcode] : []));
  const [image, setImage] = useState<ImageChange>({ kind: "keep" });

  const items = useQuery({
    queryKey: ["offerings", "items-for-parts"],
    queryFn: () =>
      api<Page<Offering>>("/offerings?type=ITEM&activeOnly=false&size=200"),
    enabled: service,
  });
  const itemsById = new Map(items.data?.items.map((i) => [i.id, i]));
  const unusedItems =
    items.data?.items.filter((i) => !parts.some((p) => p.itemId === i.id)) ??
    [];

  const save = useMutation({
    mutationFn: async () => {
      const body = {
        type,
        name: form.name,
        nameKm: form.nameKm,
        description: form.description || null,
        sku: form.sku || null,
        barcodes: barcodes.map((c) => c.trim()).filter(Boolean),
        ...(stocked
          ? {
              unitId: unitId ? Number(unitId) : null,
              packs: packs.map((p) => ({
                unitId: p.unitId,
                factor: Number(p.factor),
              })),
            }
          : {}),
        ...(pkg
          ? {
              unitId: unitId ? Number(unitId) : null,
              contents: contents.map((c) => ({
                componentId: c.componentId,
                quantity: Number(c.quantity),
                unitId: c.unitId,
              })),
            }
          : {}),
        categoryId: form.categoryId ? Number(form.categoryId) : null,
        price: Number(form.price),
        cost: form.cost ? Number(form.cost) : 0,
        taxRateId: form.taxRateId ? Number(form.taxRateId) : null,
        active: form.active,
        ...(stocked
          ? { lowStockThreshold: Number(form.lowStockThreshold || 0) }
          : {}),
        ...(stocked && !offering ? { stock: Number(form.stock || 0) } : {}),
        ...(type === "ITEM" ? { sellable: form.sellable } : {}),
        ...(service
          ? {
              pricing: form.pricing,
              durationMinutes: form.durationMinutes
                ? Number(form.durationMinutes)
                : null,
              components: parts.map((p) => ({
                itemId: p.itemId,
                quantity: Number(p.quantity),
              })),
            }
          : {}),
      };
      const saved = await (offering
        ? api<Offering>(`/offerings/${offering.id}`, {
            method: "PUT",
            json: body,
          })
        : api<Offering>("/offerings", { method: "POST", json: body }));
      // The image goes up after the entry exists (a new entry has no id until now).
      if (image.kind === "upload") {
        const data = new FormData();
        data.append("file", image.blob, "image");
        await api<Offering>(`/offerings/${saved.id}/image`, {
          method: "PUT",
          body: data,
        });
      } else if (image.kind === "remove" && saved.hasImage) {
        await api<Offering>(`/offerings/${saved.id}/image`, {
          method: "DELETE",
        });
      }
      return saved;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["offerings"] });
      onClose();
    },
  });
  const errors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  const contentsInvalid =
    pkg &&
    (contents.length === 0 ||
      contents.some(
        (c) =>
          !(Number(c.quantity) > 0) ||
          (c.type === "PACKAGE" && !Number.isInteger(Number(c.quantity))),
      ));
  const partsInvalid =
    parts.some((p) => !(Number(p.quantity) >= 1)) ||
    packs.some((p) => !(Number(p.factor) >= 2) || String(p.unitId) === unitId);

  const rateList = (taxRates.data ?? []).filter(
    (x) => x.active || String(x.id) === form.taxRateId,
  );
  const defaultRate = taxRates.data?.find((x) => x.defaultRate);
  // The tax mode is store-wide (Settings > Taxes); it decides what the listed price means.
  const vat = store.data?.taxLabel ?? "VAT";
  const mode = store.data?.taxMode ?? "EXCLUSIVE";
  const taxNote =
    mode === "NONE"
      ? ""
      : mode === "INCLUSIVE"
        ? ` (${t("incl. {vat}", { vat })})`
        : ` (${t("excl. {vat}", { vat })})`;
  const priceLabel =
    (pkg
      ? t("Package price")
      : !service
        ? t("Price")
        : form.pricing === "HOURLY"
          ? t("Price per hour")
          : t("Price per job")) + taxNote;
  const chosenRate = rateList.find(
    (x) => String(x.id) === (form.taxRateId || String(defaultRate?.id ?? "")),
  );
  const taxHint =
    mode === "NONE"
      ? t("No {vat} is charged while the store is set to No {vat}.", { vat })
      : chosenRate && chosenRate.rate === 0
        ? t("Exempt: no {vat} on this entry.", { vat })
        : mode === "INCLUSIVE"
          ? t(
              "The price already includes {vat}; the tax is worked out of it.",
              { vat },
            )
          : t("{vat} is added on top of the price at checkout.", { vat });

  return (
    <form
      className="grid gap-4 sm:grid-cols-2"
      onSubmit={(e) => {
        e.preventDefault();
        if (!partsInvalid && !contentsInvalid) save.mutate();
      }}
    >
      <div className="sm:col-span-2">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t("Name (English)")} error={errors.name}>
            <Input
              autoFocus
              required
              maxLength={150}
              value={form.name}
              onChange={(e) => set("name")(e.target.value)}
            />
          </Field>
          <Field
            label={t("Name (Khmer)")}
            error={errors.nameKm}
            hint={
              offering && !offering.nameKm
                ? t("Needed before this can be saved")
                : undefined
            }
          >
            <Input
              required
              lang="km"
              maxLength={150}
              value={form.nameKm}
              onChange={(e) => set("nameKm")(e.target.value)}
              placeholder="ឈ្មោះជាភាសាខ្មែរ"
            />
          </Field>
        </div>
      </div>
      <div className="sm:col-span-2">
        <Field
          label={t("Description")}
          error={errors.description}
          hint={t("Optional, up to 1,000 characters")}
        >
          <textarea
            value={form.description}
            onChange={(e) => set("description")(e.target.value)}
            maxLength={1000}
            rows={2}
            className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text focus:outline-2 focus:outline-primary"
          />
        </Field>
      </div>
      <div className="sm:col-span-2">
        <ImageField
          currentUrl={offering ? offeringImageUrl(offering) : null}
          change={image}
          onChange={setImage}
        />
      </div>

      {service && (
        <div className="flex flex-col gap-1.5 text-sm sm:col-span-2">
          <span className="font-medium">{t("Charged")}</span>
          <div
            className="grid w-full max-w-sm grid-cols-2 overflow-hidden rounded-lg border border-border"
            role="group"
            aria-label={t("Pricing")}
          >
            {(
              [
                ["FIXED", msg("Per job")],
                ["HOURLY", msg("Per hour")],
              ] as const
            ).map(([mode, label]) => (
              <button
                type="button"
                key={mode}
                onClick={() => set("pricing")(mode)}
                aria-pressed={form.pricing === mode}
                className={cx(
                  "h-10",
                  form.pricing === mode
                    ? "bg-primary text-on-primary"
                    : "hover:bg-surface-2",
                )}
              >
                {t(label)}
              </button>
            ))}
          </div>
          {form.pricing === "HOURLY" && (
            <span className="text-xs text-muted">
              {t("The cashier enters the hours worked at checkout.")}
            </span>
          )}
        </div>
      )}

      <Field label={priceLabel} error={errors.price}>
        <Input
          required
          inputMode="decimal"
          value={form.price}
          onChange={(e) => set("price")(e.target.value)}
        />
      </Field>
      <Field
        label={service ? t("Labour cost") : t("Cost")}
        error={errors.cost}
        hint={
          service
            ? t("Optional, for margin reports. Parts are costed separately.")
            : t("What you pay the supplier")
        }
      >
        <Input
          inputMode="decimal"
          value={form.cost}
          onChange={(e) => set("cost")(e.target.value)}
        />
      </Field>

      {/* Hidden while the store charges no VAT; the entry keeps its rate for when it does. */}
      {mode !== "NONE" && (
        <Field label={t("Tax rate")} hint={taxHint}>
          <Select
            value={form.taxRateId || String(defaultRate?.id ?? "")}
            onChange={(e) => set("taxRateId")(e.target.value)}
          >
            {rateList.map((x) => (
              <option key={x.id} value={x.id}>
                {taxLabel(x)}
                {x.defaultRate ? t(" (default)") : ""}
              </option>
            ))}
          </Select>
        </Field>
      )}

      <Field label={t("Category")}>
        <Select
          value={form.categoryId}
          onChange={(e) => set("categoryId")(e.target.value)}
        >
          <option value="">{t("None")}</option>
          {categories.data?.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </Select>
      </Field>
      <Field
        label="SKU"
        error={errors.sku}
        hint={offering ? undefined : t("Leave empty to generate one")}
      >
        <Input value={form.sku} onChange={(e) => set("sku")(e.target.value)} />
      </Field>

      {service ? (
        <Field
          label={t("Usual duration (minutes)")}
          error={errors.durationMinutes}
          hint={t("Optional. Shown on the sell screen and order.")}
        >
          <Input
            inputMode="numeric"
            value={form.durationMinutes}
            onChange={(e) =>
              set("durationMinutes")(e.target.value.replace(/\D/g, ""))
            }
          />
        </Field>
      ) : (
        <>
          <BarcodesField value={barcodes} onChange={setBarcodes} error={errors.barcodes} />
          <Field
            label={pkg ? t("Sold per") : t("Base unit")}
            error={errors.unitId}
            hint={
              pkg
                ? t(
                    "The package's unit on the sell screen and receipt, e.g. set, box, combo",
                  )
                : t("Stock is counted and sold in this unit")
            }
          >
            <Select
              value={unitId}
              onChange={(e) => set("unitId")(e.target.value)}
            >
              {unitList.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.symbol} · {u.name}
                </option>
              ))}
            </Select>
          </Field>
          {stocked && (
            <Field
              label={t("Low-stock alert at")}
              error={errors.lowStockThreshold}
            >
              <Input
                inputMode="numeric"
                value={form.lowStockThreshold}
                onChange={(e) => set("lowStockThreshold")(e.target.value)}
              />
            </Field>
          )}
          {stocked && !offering && (
            <Field
              label={
                t("Opening stock") + (baseSymbol ? ` (${baseSymbol})` : "")
              }
              error={errors.stock}
              hint={
                branch && sellingStorage
                  ? t("Goes into {place}", {
                      place: `${branch.name} / ${sellingStorage.name}`,
                    })
                  : undefined
              }
            >
              <Input
                inputMode="numeric"
                value={form.stock}
                onChange={(e) => set("stock")(e.target.value)}
              />
            </Field>
          )}
        </>
      )}

      {pkg && (
        <div className="sm:col-span-2">
          <PackageContents
            packageId={offering?.id ?? null}
            rows={contents}
            onChange={setContents}
            price={Number(form.price) || 0}
          />
        </div>
      )}

      {stocked && (
        <div className="sm:col-span-2">
          <div className="mb-1.5 text-sm font-medium">{t("Pack sizes")}</div>
          <p className="mb-2 text-xs text-muted">
            {t(
              "Optional. Lets you receive and count stock in packs, e.g. 1 box = 20 {unit}.",
              { unit: baseSymbol || t("pcs") },
            )}
          </p>
          <div className="flex flex-col gap-2">
            {packs.map((pack, index) => {
              const unit = unitList.find((u) => u.id === pack.unitId);
              const clash = String(pack.unitId) === unitId;
              return (
                <div
                  key={pack.unitId}
                  className="flex flex-wrap items-center gap-2 rounded-lg border border-border px-3 py-2 text-sm"
                >
                  <span>1</span>
                  <span className="min-w-12 font-medium">
                    {unit?.symbol ?? `#${pack.unitId}`}
                  </span>
                  <span>=</span>
                  <div className="w-24 shrink-0">
                    <Input
                      aria-label={t("Base units in one {unit}", {
                        unit: unit?.symbol ?? "pack",
                      })}
                      inputMode="numeric"
                      value={pack.factor}
                      onChange={(e) =>
                        setPacks((ps) =>
                          ps.map((p, i) =>
                            i === index
                              ? {
                                  ...p,
                                  factor: e.target.value.replace(/\D/g, ""),
                                }
                              : p,
                          ),
                        )
                      }
                      invalid={!(Number(pack.factor) >= 2)}
                      className="text-right tabular-nums"
                    />
                  </div>
                  <span className="text-muted">{baseSymbol}</span>
                  {clash && (
                    <span className="text-xs text-danger">
                      {t("Same as the base unit")}
                    </span>
                  )}
                  <button
                    type="button"
                    onClick={() =>
                      setPacks((ps) => ps.filter((_, i) => i !== index))
                    }
                    className="ml-auto rounded-md p-1.5 text-muted hover:bg-danger-soft hover:text-danger"
                    aria-label={t("Remove {unit}", {
                      unit: unit?.symbol ?? "pack",
                    })}
                  >
                    <Trash2 className="size-4" />
                  </button>
                </div>
              );
            })}
            {unusedPackUnits.length > 0 && (
              <Select
                aria-label={t("Add a pack size")}
                value=""
                onChange={(e) => {
                  if (e.target.value)
                    setPacks((ps) => [
                      ...ps,
                      { unitId: Number(e.target.value), factor: "" },
                    ]);
                }}
              >
                <option value="">{t("+ Add a pack size…")}</option>
                {unusedPackUnits.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.symbol} · {u.name}
                  </option>
                ))}
              </Select>
            )}
          </div>
        </div>
      )}

      {service && (
        <div className="sm:col-span-2">
          <div className="mb-1.5 text-sm font-medium">
            {t("Parts used per job")}
          </div>
          <p className="mb-2 text-xs text-muted">
            {t("Each sale of this service takes these items out of stock.")}
          </p>
          {items.isPending ? (
            <Spinner label={t("Loading items")} />
          ) : (
            <div className="flex flex-col gap-2">
              {parts.length === 0 && (
                <p className="text-sm text-muted">
                  {t("No parts. This service uses no stock.")}
                </p>
              )}
              {parts.map((part, index) => {
                const item = itemsById.get(part.itemId);
                return (
                  <div
                    key={part.itemId}
                    className="flex items-center gap-2 rounded-lg border border-border px-3 py-2"
                  >
                    <div className="min-w-0 flex-1 text-sm">
                      <div className="truncate font-medium">
                        {item?.name ??
                          t("Item #{itemId}", { itemId: part.itemId })}
                      </div>
                      <div className="text-xs text-muted">
                        {item
                          ? t("{qty} in stock at this branch", {
                              qty: qty(item.stock ?? 0, item.unit),
                            })
                          : ""}
                        {item && !item.active && t(" · hidden")}
                      </div>
                    </div>
                    <div className="w-20 shrink-0">
                      <Input
                        aria-label={t("Quantity of {item}", {
                          item: item?.name ?? "part",
                        })}
                        inputMode="numeric"
                        value={part.quantity}
                        onChange={(e) =>
                          setParts((ps) =>
                            ps.map((p, i) =>
                              i === index
                                ? {
                                    ...p,
                                    quantity: e.target.value.replace(/\D/g, ""),
                                  }
                                : p,
                            ),
                          )
                        }
                        invalid={!(Number(part.quantity) >= 1)}
                        className="text-right tabular-nums"
                      />
                    </div>
                    <span className="w-8 shrink-0 text-xs text-muted">
                      {item?.unit ?? ""}
                    </span>
                    <button
                      type="button"
                      onClick={() =>
                        setParts((ps) => ps.filter((_, i) => i !== index))
                      }
                      className="rounded-md p-1.5 text-muted hover:bg-danger-soft hover:text-danger"
                      aria-label={t("Remove {item}", {
                        item: item?.name ?? "part",
                      })}
                    >
                      <Trash2 className="size-4" />
                    </button>
                  </div>
                );
              })}
              {unusedItems.length > 0 ? (
                <Select
                  aria-label={t("Add a part")}
                  value=""
                  onChange={(e) => {
                    if (e.target.value)
                      setParts((ps) => [
                        ...ps,
                        { itemId: Number(e.target.value), quantity: "1" },
                      ]);
                  }}
                >
                  <option value="">{t("+ Add a part…")}</option>
                  {unusedItems.map((i) => (
                    <option key={i.id} value={i.id}>
                      {t("{name} ({qty} in stock)", {
                        name: i.name,
                        qty: qty(i.stock ?? 0, i.unit),
                      })}
                    </option>
                  ))}
                </Select>
              ) : (
                items.data?.items.length === 0 && (
                  <p className="text-xs text-muted">
                    {t(
                      "Add items on the Items tab first, then pick them here.",
                    )}
                  </p>
                )
              )}
            </div>
          )}
        </div>
      )}

      <div className="flex flex-col gap-2 text-sm sm:col-span-2">
        {type === "ITEM" && (
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={form.sellable}
              onChange={(e) => set("sellable")(e.target.checked)}
            />
            {t("Can be sold on its own at the till")}
          </label>
        )}
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={form.active}
            onChange={(e) => set("active")(e.target.checked)}
          />
          {type === "ITEM"
            ? t("Active (unticked: hidden everywhere)")
            : t("Show on the sell screen")}
        </label>
      </div>

      <div className="sm:col-span-2">
        <ErrorNote error={save.error} />
      </div>
      <div className="flex justify-end gap-2 sm:col-span-2">
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("Cancel")}
        </Button>
        <Button
          type="submit"
          loading={save.isPending}
          disabled={partsInvalid || contentsInvalid}
        >
          {offering ? t("Save changes") : t(ADD_LABELS[type])}
        </Button>
      </div>
    </form>
  );
}
