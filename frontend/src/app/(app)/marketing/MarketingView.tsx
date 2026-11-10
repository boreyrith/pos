"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { cx } from "@/components/ui";
import { msg, useT } from "@/lib/i18n";

import { CampaignsTab } from "./CampaignsTab";
import { LoyaltyTab } from "./LoyaltyTab";
import { PromotionsTab } from "./PromotionsTab";
import { SegmentsTab } from "./SegmentsTab";

const TABS = [
  { key: "promotions", label: msg("Promotions"), hint: msg("Deals that apply at checkout on their own, or with a coupon code") },
  { key: "loyalty", label: msg("Loyalty"), hint: msg("Customers on file earn points on what they pay and use them as a discount") },
  { key: "segments", label: msg("Segments"), hint: msg("Groups of customers by what, how often and how recently they buy; always up to date") },
  { key: "campaigns", label: msg("Campaigns"), hint: msg("Messages to a segment, with a coupon code for each customer so you can see what they bring in") },
] as const;

/** Marketing: promotions and coupon codes, loyalty points, customer segments and campaigns. */
export function MarketingView() {
  const t = useT();
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const tab = TABS.find((x) => x.key === params.get("tab")) ?? TABS[0];

  return (
    <div className="mx-auto max-w-[1400px] px-4 py-6 lg:px-8 lg:py-8">
      <h1 className="text-[28px] leading-tight font-semibold tracking-tight">{t("Marketing")}</h1>
      <div className="mt-4 mb-1 flex gap-1 overflow-x-auto border-b border-border" role="tablist" aria-label={t("Marketing")}>
        {TABS.map((x) => (
          <button
            key={x.key}
            role="tab"
            aria-selected={tab.key === x.key}
            onClick={() => router.replace(`${pathname}?tab=${x.key}`)}
            className={cx(
              "-mb-px border-b-2 px-4 py-2 text-sm font-medium whitespace-nowrap",
              tab.key === x.key ? "border-primary text-primary" : "border-transparent text-muted hover:text-text",
            )}
          >
            {t(x.label)}
          </button>
        ))}
      </div>
      <p className="mb-5 text-xs text-muted">{t(tab.hint)}.</p>
      {tab.key === "promotions" && <PromotionsTab />}
      {tab.key === "loyalty" && <LoyaltyTab />}
      {tab.key === "segments" && <SegmentsTab />}
      {tab.key === "campaigns" && <CampaignsTab />}
    </div>
  );
}
