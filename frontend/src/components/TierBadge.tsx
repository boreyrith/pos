"use client";

import { Crown } from "lucide-react";

import { msg, useT } from "@/lib/i18n";
import type { Tier } from "@/lib/types";

import { cx } from "./ui";

export const TIERS: { code: Tier; label: string }[] = [
  { code: "BRONZE", label: msg("Bronze") },
  { code: "SILVER", label: msg("Silver") },
  { code: "GOLD", label: msg("Gold") },
  { code: "PLATINUM", label: msg("Platinum") },
];

/** Metal-like colours that read in light and dark themes; the name is always shown, never colour alone. */
const STYLES: Record<Tier, string> = {
  BRONZE: "bg-[#f6e3d3] text-[#8a4b1f] ring-[#e2b991] dark:bg-[#3b2618] dark:text-[#e8b98c] dark:ring-[#6e4425]",
  SILVER: "bg-[#eceef2] text-[#4b5563] ring-[#c8cdd6] dark:bg-[#2a2d33] dark:text-[#d1d5db] dark:ring-[#4b5160]",
  GOLD: "bg-[#fdf1c7] text-[#8a6100] ring-[#e8c860] dark:bg-[#3a2f0c] dark:text-[#f2cd5c] dark:ring-[#6e5a14]",
  PLATINUM: "bg-[#e7e5f7] text-[#3f3a8a] ring-[#bdb7ea] dark:bg-[#24223f] dark:text-[#c9c4fa] dark:ring-[#4c468a]",
};

export const tierLabel = (tier: Tier) => TIERS.find((x) => x.code === tier)!.label;

/** A customer's loyalty tier as a small badge. */
export function TierBadge({ tier, className }: { tier: Tier | null | undefined; className?: string }) {
  const t = useT();
  if (!tier) return null;
  return (
    <span className={cx("inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold ring-1", STYLES[tier], className)}>
      <Crown className="size-3" aria-hidden />
      {t(tierLabel(tier))}
    </span>
  );
}
