"use client";

import { useQuery } from "@tanstack/react-query";
import { Plus, Printer, Ruler, Tags } from "lucide-react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Fragment, useState } from "react";

import {
  Badge,
  Button,
  Card,
  ErrorNote,
  Input,
  PageHeader,
  Pagination,
  Select,
  Spinner,
  cx,
} from "@/components/ui";
import { api, queryString } from "@/lib/api";
import { hasPermission, useUser } from "@/lib/auth";
import { useBranch } from "@/lib/branch";
import { duration, money } from "@/lib/format";
import { useCategories, useStoreSettings } from "@/lib/queries";
import { offeringImageUrl } from "@/lib/queries";
import type { Offering, OfferingType, Page } from "@/lib/types";

import { LabelsTab } from "./LabelsTab";

/** A small square of the entry's image, or a neutral placeholder. */
function Thumb({ offering }: { offering: Offering }) {
  const url = offeringImageUrl(offering);
  return (
    <div className="grid size-10 shrink-0 place-items-center overflow-hidden rounded-lg bg-surface-2 text-xs text-muted">
      {url ? (
        // eslint-disable-next-line @next/next/no-img-element -- our own API image, tiny
        <img
          src={url}
          alt=""
          loading="lazy"
          className="size-full object-cover"
        />
      ) : (
        offering.name.slice(0, 1).toUpperCase()
      )}
    </div>
  );
}

import { CategoriesDialog } from "@/components/settings/CategoriesPanel";
import { OfferingDialog } from "./OfferingDialog";
import { StockDialog } from "./StockDialog";
import { UnitsDialog } from "@/components/settings/UnitsPanel";
import { msg, useT } from "@/lib/i18n";

const TABS: { type: OfferingType; label: string; hint: string }[] = [
  {
    type: "PRODUCT",
    label: msg("Products"),
    hint: msg("Goods you sell and keep in stock"),
  },
  {
    type: "ITEM",
    label: msg("Items"),
    hint: msg("Parts and materials used by services, or sold on their own"),
  },
  {
    type: "SERVICE",
    label: msg("Services"),
    hint: msg("Work you bill for, such as repairs or installation"),
  },
  {
    type: "PACKAGE",
    label: msg("Packages"),
    hint: msg("Bundles of products, services and items sold together at their own price"),
  },
];

/** The "New ..." button for each tab. */
const NEW_LABEL: Record<OfferingType, string> = {
  PRODUCT: msg("New product"),
  SERVICE: msg("New service"),
  ITEM: msg("New item"),
  PACKAGE: msg("New package"),
};

export function CatalogManager() {
  const taxMode = useStoreSettings().data?.taxMode;
  const t = useT();
  const user = useUser();
  const manager = hasPermission(user, "CATALOG_MANAGE");
  const adjusts = hasPermission(user, "STOCK_ADJUST");
  const categories = useCategories();
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();

  const labelsTab = params.get("type") === "labels";
  const type = (TABS.find((x) => x.type.toLowerCase() === params.get("type"))
    ?.type ?? "PRODUCT") as OfferingType;
  const stocked = type === "PRODUCT" || type === "ITEM";

  const [q, setQ] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [lowStock, setLowStock] = useState(params.get("lowStock") === "1");
  const [showInactive, setShowInactive] = useState(false);
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Offering | "new" | null>(null);
  const [adjusting, setAdjusting] = useState<Offering | null>(null);
  const [categoriesOpen, setCategoriesOpen] = useState(false);
  const { branches, current } = useBranch();
  // "" = all storages of the current branch; "b:<id>" = a branch; "s:<id>" = one storage.
  const [scope, setScope] = useState("");
  const scopeParams = scope.startsWith("s:")
    ? { storageId: scope.slice(2) }
    : scope.startsWith("b:")
      ? { branchId: scope.slice(2) }
      : {};
  const [unitsOpen, setUnitsOpen] = useState(false);

  const offerings = useQuery({
    queryKey: [
      "offerings",
      "manage",
      { type, q, categoryId, lowStock, showInactive, page, scope },
    ],
    queryFn: () =>
      api<Page<Offering>>(
        `/offerings${queryString({
          type,
          q: q.trim(),
          categoryId,
          lowStock: stocked && lowStock,
          activeOnly: !showInactive,
          page,
          size: 50,
          ...scopeParams,
        })}`,
      ),
    placeholderData: (previous) => previous,
  });

  const filter =
    <T,>(set: (v: T) => void) =>
    (v: T) => {
      set(v);
      setPage(0);
    };
  const switchTab = (x: OfferingType) => {
    setPage(0);
    router.replace(`${pathname}?type=${x.toLowerCase()}`);
  };
  const tab = TABS.find((x) => x.type === type)!;

  return (
    <div className="mx-auto max-w-6xl p-4 lg:p-8">
      <PageHeader
        title={t("Catalog")}
        actions={
          manager &&
          !labelsTab && (
            <>
              <Button variant="secondary" onClick={() => setUnitsOpen(true)}>
                <Ruler className="size-4" aria-hidden />{" "}{t("Units")}
              </Button>
              <Button
                variant="secondary"
                onClick={() => setCategoriesOpen(true)}
              >
                <Tags className="size-4" aria-hidden />{" "}{t("Categories")}
              </Button>
              <Button onClick={() => setEditing("new")}>
                <Plus className="size-4" aria-hidden />{" "}{t(NEW_LABEL[type])}
              </Button>
            </>
          )
        }
      />

      <div
        className="mb-1 flex gap-1 border-b border-border"
        role="tablist"
        aria-label={t("Catalog type")}
      >
        {TABS.map((x) => (
          <Fragment key={x.type}>
            {/* Packages bundle the other three, so they sit apart. */}
            {x.type === "PACKAGE" && (
              <span aria-hidden className="mx-2 my-2 w-px bg-border" />
            )}
            <button
              role="tab"
              aria-selected={!labelsTab && type === x.type}
              onClick={() => switchTab(x.type)}
              className={cx(
                "-mb-px border-b-2 px-4 py-2 text-sm font-medium",
                !labelsTab && type === x.type
                  ? "border-primary text-primary"
                  : "border-transparent text-muted hover:text-text",
              )}
            >
              {t(x.label)}
            </button>
          </Fragment>
        ))}
        <button
          role="tab"
          aria-selected={labelsTab}
          onClick={() => router.replace(`${pathname}?type=labels`)}
          className={cx(
            "-mb-px ml-auto flex items-center gap-1.5 border-b-2 px-4 py-2 text-sm font-medium",
            labelsTab
              ? "border-primary text-primary"
              : "border-transparent text-muted hover:text-text",
          )}
        >
          <Printer className="size-4" aria-hidden />{" "}{t("Print labels")}
        </button>
      </div>
      {labelsTab ? (
        <>
          <p className="mb-4 text-xs text-muted print:hidden">
            {t("Shelf and price labels with barcodes or QR codes, for anything for sale.")}
          </p>
          <LabelsTab />
        </>
      ) : (
        <>
          <p className="mb-4 text-xs text-muted">{t(tab.hint)}</p>

          <div className="mb-4 flex flex-wrap items-center gap-2">
            <Input
              placeholder={
                stocked ? t("Search name, SKU or barcode") : t("Search name or SKU")
              }
              aria-label={t("Search {kind}", { kind: t(tab.label).toLowerCase() })}
              value={q}
              onChange={(e) => filter(setQ)(e.target.value)}
              className="w-64"
            />
            <Select
              aria-label={t("Category")}
              value={categoryId}
              onChange={(e) => filter(setCategoryId)(e.target.value)}
            >
              <option value="">{t("All categories")}</option>
              {categories.data?.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </Select>
            {stocked && (
              <>
                <Select
                  aria-label={t("Stock in")}
                  value={scope}
                  onChange={(e) => filter(setScope)(e.target.value)}
                >
                  <option value="">
                    {t("Stock in:")}{" "}{current?.name ?? t("this branch")}{" "}{t("(all storages)")}
                  </option>
                  {branches
                    .filter((b) => b.active)
                    .map((b) => (
                      <optgroup key={b.id} label={b.name}>
                        {b.id !== current?.id && (
                          <option value={`b:${b.id}`}>
                            {t("{name}: all storages", { name: b.name })}
                          </option>
                        )}
                        {b.storages
                          .filter((st) => st.active)
                          .map((st) => (
                            <option key={st.id} value={`s:${st.id}`}>
                              {b.name} / {st.name}
                              {st.selling ? t(" (selling)") : ""}
                            </option>
                          ))}
                      </optgroup>
                    ))}
                </Select>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={lowStock}
                    onChange={(e) => filter(setLowStock)(e.target.checked)}
                  />
                  {t("Low stock only")}
                </label>
              </>
            )}
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={showInactive}
                onChange={(e) => filter(setShowInactive)(e.target.checked)}
              />
              {t("Include hidden")}
            </label>
          </div>

          <Card className="overflow-x-auto">
            {offerings.isPending ? (
              <Spinner />
            ) : offerings.error ? (
              <div className="p-4">
                <ErrorNote error={offerings.error} />
              </div>
            ) : offerings.data.items.length === 0 ? (
              <p className="p-8 text-center text-sm text-muted">
                {t("No {kind} match these filters.", { kind: t(tab.label).toLowerCase() })}
              </p>
            ) : (
              <table className="w-full text-sm">
                <thead className="border-b border-border text-left text-xs text-muted">
                  <tr>
                    <th className="px-4 py-3 font-medium">{t("Name")}</th>
                    <th className="px-4 py-3 font-medium">{t("Category")}</th>
                    <th className="px-4 py-3 text-right font-medium">{t("Price")}</th>
                    <th className="px-4 py-3 text-right font-medium">
                      {type === "SERVICE"
                        ? t("Labour cost")
                        : type === "PACKAGE"
                          ? t("Bought separately")
                          : t("Cost")}
                    </th>
                    <th className="px-4 py-3 text-right font-medium">
                      {type === "SERVICE"
                        ? t("Duration")
                        : type === "PACKAGE"
                          ? t("Can make")
                          : t("Stock")}
                    </th>
                    {(manager || adjusts) && (
                      <th className="px-4 py-3" aria-label={t("Actions")} />
                    )}
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {offerings.data.items.map((o) => (
                    <tr key={o.id} className="hover:bg-surface-2">
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-3">
                          <Thumb offering={o} />
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-2 font-medium">
                              {o.name}
                              {!o.nameKm && (
                                <Badge tone="warning">{t("Khmer name needed")}</Badge>
                              )}
                              {!o.active && <Badge>{t("Hidden")}</Badge>}
                              {o.effectiveTaxRate === 0 && taxMode !== "NONE" && (
                                <Badge>{o.effectiveTaxName}</Badge>
                              )}
                              {o.type === "ITEM" &&
                                (o.sellable ? (
                                  <Badge tone="primary">{t("Sold at till")}</Badge>
                                ) : (
                                  <Badge>{t("Parts only")}</Badge>
                                ))}
                            </div>
                            {o.nameKm && (
                              <div lang="km" className="text-sm">
                                {o.nameKm}
                              </div>
                            )}
                            <div className="text-xs text-muted">
                              {o.sku}
                              {o.barcode && ` · ${o.barcode}`}
                              {o.barcodes.length > 1 && (
                                <span title={o.barcodes.slice(1).join(", ")}>{` ${t("+{n} more", { n: o.barcodes.length - 1 })}`}</span>
                              )}
                            </div>
                          </div>
                        </div>
                      </td>
                      <td className="px-4 py-3 text-muted">
                        {o.categoryName ?? "—"}
                      </td>
                      <td className="px-4 py-3 text-right whitespace-nowrap tabular-nums">
                        {money(o.price)}
                        {o.pricing === "HOURLY" ? (
                          <span className="text-xs text-muted"> / h</span>
                        ) : (
                          o.unit && (
                            <span className="text-xs text-muted">
                              {" "}
                              / {o.unit}
                            </span>
                          )
                        )}
                      </td>
                      <td className="px-4 py-3 text-right text-muted tabular-nums">
                        {o.packageInfo ? (
                          <>
                            {money(o.packageInfo.separatePrice)}
                            {o.packageInfo.separatePrice > o.price && (
                              <div className="text-xs text-success">
                                {t("saves")}{" "}
                                {money(o.packageInfo.separatePrice - o.price)}
                              </div>
                            )}
                          </>
                        ) : (
                          money(o.cost)
                        )}
                      </td>
                      <td className="px-4 py-3 text-right whitespace-nowrap">
                        {o.type === "PACKAGE" ? (
                          o.packageInfo?.available === null ||
                          o.packageInfo?.available === undefined ? (
                            <span className="text-muted">—</span>
                          ) : o.packageInfo.available <= 0 ? (
                            <Badge tone="danger">{t("None")}</Badge>
                          ) : (
                            <span className="tabular-nums">
                              {o.packageInfo.available}
                            </span>
                          )
                        ) : o.type === "SERVICE" ? (
                          <span className="text-muted">
                            {o.durationMinutes
                              ? duration(o.durationMinutes)
                              : "—"}
                          </span>
                        ) : (o.stock ?? 0) <= 0 ? (
                          <Badge tone="danger">{t("Out of stock")}</Badge>
                        ) : (o.stock ?? 0) <= (o.lowStockThreshold ?? 0) ? (
                          <Badge tone="warning">{t("Low · {stock}", { stock: o.stock })}</Badge>
                        ) : (
                          <span className="tabular-nums">
                            {o.stock}
                            {o.unit && (
                              <span className="text-xs text-muted">
                                {" "}
                                {o.unit}
                              </span>
                            )}
                          </span>
                        )}
                      </td>
                      {(manager || adjusts) && (
                        <td className="px-4 py-3 text-right whitespace-nowrap">
                          {stocked && adjusts && (
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => setAdjusting(o)}
                            >
                              {t("Stock")}
                            </Button>
                          )}
                          {manager && (
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => setEditing(o)}
                            >
                              {t("Edit")}
                            </Button>
                          )}
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
          {offerings.data && (
            <Pagination
              page={page}
              totalPages={offerings.data.totalPages}
              onChange={setPage}
            />
          )}
        </>
      )}

      {editing && (
        <OfferingDialog
          type={type}
          offeringId={editing === "new" ? null : editing.id}
          onClose={() => setEditing(null)}
        />
      )}
      {adjusting && (
        <StockDialog product={adjusting} onClose={() => setAdjusting(null)} />
      )}
      {categoriesOpen && (
        <CategoriesDialog onClose={() => setCategoriesOpen(false)} />
      )}
      {unitsOpen && <UnitsDialog onClose={() => setUnitsOpen(false)} />}
    </div>
  );
}
