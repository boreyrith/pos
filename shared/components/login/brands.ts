import { ChartColumn, LayoutDashboard, Package, Receipt, Settings, ShoppingBag, ShoppingCart, Wallet } from "lucide-react";

import { msg } from "../../i18n";
import { ZONE, ZONE_PATHS } from "../../zones";

/** What each app's sign-in page says about itself, and where the other app signs in. */
export type LoginBrand = {
  /** The app's name, shown above the form. */
  app: string;
  icon: typeof ShoppingCart;
  headline: string;
  blurb: string;
  features: { Icon: typeof ShoppingCart; label: string }[];
  /** The other staff app: the link to its sign-in page. */
  other: { label: string; href: string };
};

const BRANDS: Record<"ctl" | "cas", LoginBrand> = {
  cas: {
    app: msg("Till"),
    icon: ShoppingCart,
    headline: msg("Ring up sales and take payments, fast"),
    blurb: msg("Cash and KHQR payments, held sales, receipts and returns at the counter."),
    features: [
      { Icon: ShoppingBag, label: msg("Sell") },
      { Icon: Wallet, label: msg("Payments") },
      { Icon: Receipt, label: msg("Receipts") },
    ],
    other: { label: msg("Open administration"), href: `${ZONE_PATHS.ctl}/login` },
  },
  ctl: {
    app: msg("Administration"),
    icon: LayoutDashboard,
    headline: msg("Run the shop: stock, prices, reports and settings"),
    blurb: msg("Catalog, inventory, purchasing, customers, accounting and reports for every branch."),
    features: [
      { Icon: Package, label: msg("Stock") },
      { Icon: ChartColumn, label: msg("Reports") },
      { Icon: Settings, label: msg("Settings") },
    ],
    other: { label: msg("Open the till"), href: `${ZONE_PATHS.cas}/login` },
  },
};

/** This staff app's sign-in branding (the shop has its own customer sign-in). */
export const brand = BRANDS[ZONE === "cas" ? "cas" : "ctl"];
