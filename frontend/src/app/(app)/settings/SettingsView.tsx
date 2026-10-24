"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { CompanyPanel } from "@/components/settings/CompanyPanel";
import { CurrenciesPanel } from "@/components/settings/CurrenciesPanel";
import { KhqrPanel } from "@/components/settings/KhqrPanel";
import { LocationsPanel } from "@/components/settings/LocationsPanel";
import { SystemPanel } from "@/components/settings/SystemPanel";
import { TaxesPanel } from "@/components/settings/TaxesPanel";
import { PageHeader, cx } from "@/components/ui";
import { hasPermission, useUser } from "@/lib/auth";
import type { Permission } from "@/lib/types";
import { msg, useT } from "@/lib/i18n";

const SECTIONS = [
  {
    key: "general",
    label: msg("General"),
    hint: msg("Company details, receipts and time zone"),
    any: ["SETTINGS_VIEW"] as Permission[],
  },
  {
    key: "branches",
    label: msg("Branches & storages"),
    hint: msg("Where you sell and keep stock"),
    any: [
      "SETTINGS_VIEW",
      "BRANCHES_MANAGE",
      "STORAGES_MANAGE",
    ] as Permission[],
  },
  {
    key: "payments",
    label: msg("Taxes & payments"),
    hint: msg("How VAT is charged, the currencies you accept and their rates, and the Bakong account KHQR payments go into"),
    any: [
      "SETTINGS_VIEW",
      "CURRENCY_RATES",
      "SETTINGS_CURRENCY",
    ] as Permission[],
  },
  {
    key: "system",
    label: msg("System"),
    hint: msg("Version, database, sign-in and sessions, sales timers and background jobs"),
    any: ["SETTINGS_VIEW"] as Permission[],
  },
  {
    key: "manual",
    label: msg("User manual"),
    hint: msg("How to sell, take payment, look after stock, buy from vendors, read the reports and run the system"),
    // Everyone signed in can read it.
    any: [] as Permission[],
  },
];

/** Tabs that were merged into another, so old links still land in the right place. */
const MOVED: Record<string, string> = {
  company: "general",
  taxes: "payments",
  currencies: "payments",
  khqr: "payments",
};

/**
 * One place to set up the system. Each section shows if the role can open it; changes need their own
 * permission. Units and categories are managed from the Catalog.
 */
export function SettingsView() {
  const t = useT();
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const user = useUser();
  const sections = SECTIONS.filter((s) => s.any.length === 0 || hasPermission(user, ...s.any));
  const tab = params.get("tab") ?? "";
  const section =
    sections.find((s) => s.key === (MOVED[tab] ?? tab)) ?? sections[0];
  if (!section) return null;

  return (
    <div className="mx-auto max-w-6xl p-4 lg:p-8">
      <PageHeader title={t("Settings")} />
      <div className="grid gap-6 md:grid-cols-[220px_1fr]">
        <nav
          aria-label={t("Settings sections")}
          className="flex gap-1 overflow-x-auto md:flex-col"
        >
          {sections.map((s) => (
            <button
              key={s.key}
              onClick={() => router.replace(`${pathname}?tab=${s.key}`)}
              aria-current={section.key === s.key ? "page" : undefined}
              className={cx(
                "shrink-0 rounded-lg px-3 py-2 text-left text-sm font-medium",
                section.key === s.key
                  ? "bg-primary-soft text-primary"
                  : "text-muted hover:bg-surface-2 hover:text-text",
              )}
            >
              {t(s.label)}
            </button>
          ))}
        </nav>
        <section aria-label={t(section.label)} className="min-w-0">
          <h2 className="text-base font-semibold">{t(section.label)}</h2>
          <p className="mb-4 text-sm text-muted">{t(section.hint)}</p>
          {section.key === "general" && <CompanyPanel />}
          {section.key === "branches" && <LocationsPanel />}
          {section.key === "system" && <SystemPanel />}
          {section.key === "manual" && (
            <div className="flex flex-col gap-2">
              <a href="/manual/index.html" target="_blank" rel="noopener" className="self-end text-sm text-primary hover:underline">
                {t("Open in a new tab")}
              </a>
              {/* The manual is its own page (public/manual, built from docs/user-manual.html). */}
              <iframe
                src="/manual/index.html"
                title={t("User manual")}
                className="h-[calc(100vh-14rem)] min-h-[32rem] w-full rounded-xl border border-border bg-surface"
              />
            </div>
          )}
          {section.key === "payments" && (
            <div className="flex flex-col gap-8">
              {hasPermission(user, "SETTINGS_VIEW") && (
                <div>
                  <h3 className="text-sm font-semibold">{t("Taxes")}</h3>
                  <p className="mb-3 text-sm text-muted">
                    {t("How VAT is charged, its rate, and which entries it applies to.")}
                  </p>
                  <TaxesPanel />
                </div>
              )}
              <div>
                <h3 className="text-sm font-semibold">{t("Currencies")}</h3>
                <p className="mb-3 text-sm text-muted">
                  {t("Accepted currencies and exchange rates.")}
                </p>
                <CurrenciesPanel />
              </div>
              {/* Taxes and the KHQR account are store configuration: shown to roles that can see settings. */}
              {hasPermission(user, "SETTINGS_VIEW") && (
                <div>
                  <h3 className="text-sm font-semibold">{t("KHQR payments")}</h3>
                  <p className="mb-3 text-sm text-muted">
                    {t("The Bakong account customers pay into, and the Bakong API connection.")}
                  </p>
                  <KhqrPanel />
                </div>
              )}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
