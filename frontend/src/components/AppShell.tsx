"use client";

import {
  BarChart3,
  Check,
  Megaphone,
  ChevronsUpDown,
  CircleHelp,
  ClipboardList,
  Contact,
  FileBarChart,
  Landmark,
  MapPin,
  Menu,
  Package,
  PanelLeftClose,
  PanelLeftOpen,
  Receipt,
  Settings,
  ShieldCheck,
  ShoppingCart,
  Warehouse,
  X,
} from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";

import { hasPermission, useAuth } from "@/lib/auth";
import { msg, useT } from "@/lib/i18n";
import { BranchProvider, useBranch } from "@/lib/branch";
import { useStoreSettings } from "@/lib/queries";
import type { Permission, User } from "@/lib/types";

import { AccountDialog } from "./account/AccountDialog";
import { UserAvatar } from "./account/UserAvatar";
import { ChangePasswordForm } from "./account/ChangePasswordForm";
import { LanguageSwitch } from "./LanguageSwitch";
import { StoreLogo } from "./StoreLogo";
import { Card, Modal, Spinner, cx } from "./ui";

/** The menu's sections, in order, named for the work done in them. */
const GROUPS = [
  { key: "selling", label: msg("Checkout & sales") },
  { key: "customers", label: msg("Customers") },
  { key: "products", label: msg("Products & stock") },
  { key: "finance", label: msg("Reports & finance") },
  { key: "admin", label: msg("Administration") },
  { key: "help", label: msg("Help") },
] as const;

/** Each page, its menu section, and the permissions that open it (any one of them). No list means every signed-in user. */
const NAV: {
  href: string;
  label: string;
  icon: typeof ShoppingCart;
  group: (typeof GROUPS)[number]["key"];
  any?: Permission[];
  also?: string[];
}[] = [
  { href: "/pos", label: msg("Sell"), icon: ShoppingCart, group: "selling", any: ["SALES_SELL"] },
  // Sales: invoices, returns and refunds.
  { href: "/orders", label: msg("Sales"), icon: Receipt, group: "selling", any: ["ORDERS_VIEW"] },
  { href: "/customers", label: msg("Customers"), icon: Contact, group: "customers", any: ["CUSTOMERS_VIEW"] },
  { href: "/marketing", label: msg("Marketing"), icon: Megaphone, group: "customers", any: ["MARKETING_VIEW"] },
  { href: "/catalog", label: msg("Catalog"), icon: Package, group: "products" },
  { href: "/inventory", label: msg("Inventory"), icon: Warehouse, group: "products", any: ["INVENTORY_VIEW"] },
  { href: "/purchasing", label: msg("Purchasing"), icon: ClipboardList, group: "products", any: ["PURCHASE_VIEW"] },
  { href: "/dashboard", label: msg("Dashboard"), icon: BarChart3, group: "finance", any: ["REPORTS_DASHBOARD"] },
  {
    href: "/reports",
    label: msg("Reports"),
    icon: FileBarChart,
    group: "finance",
    any: ["REPORTS_SALES", "REPORTS_INVENTORY", "REPORTS_PURCHASING", "REPORTS_ACCOUNTING", "REPORTS_TAX"],
  },
  { href: "/accounting", label: msg("Accounting"), icon: Landmark, group: "finance", any: ["ACCOUNTING_VIEW"] },
  // The permissions of the Settings sections (SettingsView); each section checks its own.
  {
    href: "/settings",
    label: msg("Settings"),
    icon: Settings,
    group: "admin",
    any: ["SETTINGS_VIEW", "BRANCHES_MANAGE", "STORAGES_MANAGE", "CURRENCY_RATES", "SETTINGS_CURRENCY"],
  },
  { href: "/users", label: msg("Users & access"), icon: ShieldCheck, group: "admin", any: ["USERS_VIEW", "ROLES_MANAGE", "AUDIT_VIEW"] },
  // Everyone: the user manual.
  { href: "/help", label: msg("User manual"), icon: CircleHelp, group: "help" },
];

const allowed = (user: User, item: (typeof NAV)[number]) => !item.any || hasPermission(user, ...item.any);

/** Whether a page belongs to a menu item (its own path, or one it also covers). */
const covers = (item: (typeof NAV)[number], pathname: string) =>
  [item.href, ...(item.also ?? [])].some((p) => pathname === p || pathname.startsWith(`${p}/`));

/**
 * Where each role starts, in order of preference: the dashboard for those who run the shop, else
 * the till, else their own work (the catalog is open to everyone, so it comes last).
 */
const LANDING = ["/dashboard", "/pos", "/orders", "/inventory", "/purchasing", "/customers", "/marketing", "/reports", "/accounting", "/settings", "/users", "/catalog"];

/** Where to send someone after sign-in: the first page their role opens. */
export function homePath(user: User) {
  return LANDING.find((href) => NAV.some((item) => item.href === href && allowed(user, item))) ?? "/catalog";
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const { state, logout } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const t = useT();

  useEffect(() => {
    if (state.status === "signed-out") {
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    }
  }, [state.status, router, pathname]);

  if (state.status !== "signed-in") {
    return (
      <div className="grid min-h-screen place-items-center">
        <Spinner label={state.status === "loading" ? t("Loading") : t("Redirecting to sign in")} />
      </div>
    );
  }

  if (state.user.mustChangePassword) {
    return <ForcedPasswordChange onSignOut={() => logout().then(() => router.replace("/login"))} />;
  }

  return (
    <BranchProvider>
      <Shell onSignOut={() => logout().then(() => router.replace("/login"))}>{children}</Shell>
    </BranchProvider>
  );
}

/**
 * The frame around every page. Large screens: a sidebar with the menu in sections, which collapses to a
 * rail (remembered on this device; always a rail on the sell screen, which needs the width). Tablets: the
 * rail. Phones: a top bar whose menu button slides the full menu in.
 */
function Shell({ children, onSignOut }: { children: React.ReactNode; onSignOut: () => void }) {
  const { state } = useAuth();
  const pathname = usePathname();
  const user = state.user!;
  const taxMode = useStoreSettings().data?.taxMode;
  const items = NAV.filter(
    (item) =>
      allowed(user, item) &&
      // Reports for someone who can only see the tax report, while the store charges no VAT, would be empty.
      !(item.href === "/reports" && taxMode === "NONE" && !hasPermission(user, "REPORTS_SALES", "REPORTS_INVENTORY", "REPORTS_PURCHASING", "REPORTS_ACCOUNTING")),
  );
  const [accountOpen, setAccountOpen] = useState(false);
  // The phone menu belongs to the page it was opened on, so picking a page closes it.
  const [menuOpenOn, setMenuOpenOn] = useState<string | null>(null);
  const drawerOpen = menuOpenOn === pathname;
  const [collapsed, setCollapsed] = useStoredFlag("pos.sidebar.collapsed");
  const wide = useMediaQuery("(min-width: 1024px)");
  const onSellScreen = pathname === "/pos" || pathname.startsWith("/pos/");
  const rail = !wide || onSellScreen || collapsed;
  const t = useT();
  const current = items.find((item) => covers(item, pathname));

  const nav = (asRail: boolean) => (
    <NavContent
      items={items}
      rail={asRail}
      onAccount={() => setAccountOpen(true)}
      onToggle={wide && !onSellScreen ? () => setCollapsed(!collapsed) : undefined}
    />
  );

  return (
    <div className="flex min-h-screen flex-col md:flex-row">
      {/* Phones: a slim top bar. */}
      <header className="sticky top-0 z-20 flex items-center gap-2 border-b border-border bg-surface/85 px-2 py-2 backdrop-blur-xl md:hidden print:hidden">
        <button
          onClick={() => setMenuOpenOn(pathname)}
          aria-label={t("Open menu")}
          aria-expanded={drawerOpen}
          aria-controls="mobile-menu"
          className="grid size-10 place-items-center rounded-xl text-text hover:bg-surface-2 focus-visible:outline-2 focus-visible:outline-primary"
        >
          <Menu className="size-5" aria-hidden />
        </button>
        <StoreLogo className="size-8 rounded-lg ring-1 ring-border" />
        <span className="min-w-0 flex-1 truncate font-semibold">{current ? t(current.label) : ""}</span>
        <BranchSwitcher rail />
        <AccountButton user={user} onClick={() => setAccountOpen(true)} />
      </header>
      {drawerOpen && <MobileMenu onClose={() => setMenuOpenOn(null)}>{nav(false)}</MobileMenu>}

      {/* Tablets and up: the sidebar or rail. */}
      <nav
        aria-label={t("Main")}
        className={cx(
          "sticky top-0 hidden h-screen shrink-0 flex-col border-r border-border bg-surface transition-[width] duration-300 ease-out md:flex print:hidden",
          rail ? "w-20" : "w-64",
        )}
      >
        {nav(rail)}
      </nav>

      <main className="min-w-0 flex-1">
        <PageGuard user={user}>{children}</PageGuard>
      </main>
      {accountOpen && <AccountDialog onClose={() => setAccountOpen(false)} onSignOut={onSignOut} />}
    </div>
  );
}

/** The menu itself: store, branch, the sections, then language and the account (which has Sign out). */
function NavContent({
  items,
  rail,
  onAccount,
  onToggle,
}: {
  items: typeof NAV;
  rail: boolean;
  onAccount: () => void;
  onToggle?: () => void;
}) {
  const t = useT();
  const pathname = usePathname();
  const { state } = useAuth();
  const user = state.user!;
  const store = useStoreSettings().data;
  const groups = GROUPS.map((g) => ({ ...g, items: items.filter((item) => item.group === g.key) })).filter((g) => g.items.length > 0);

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className={cx("flex items-center gap-3 pt-3 pb-2.5", rail ? "justify-center px-2" : "px-4")}>
        <StoreLogo className="size-10 shrink-0 rounded-xl shadow-sm ring-1 ring-border" />
        {!rail && (
          <div className="min-w-0">
            <div className="truncate text-[15px] font-semibold tracking-tight">{store?.name ?? "POS"}</div>
            <div className="truncate text-xs text-muted">{t("Point of sale")}</div>
          </div>
        )}
      </div>

      <div className={cx("pb-2", rail ? "flex justify-center px-2" : "px-3")}>
        <BranchSwitcher rail={rail} />
      </div>

      <div className={cx("flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto pb-2", rail ? "px-1.5" : "px-3")}>
        {groups.map((g, n) => (
          <div key={g.key} role="group" aria-label={t(g.label)} className="flex flex-col gap-0.5">
            {rail ? (
              n > 0 && <div className="mx-3 my-1 h-px bg-border" aria-hidden />
            ) : (
              <div className="px-3 pt-2 pb-1 text-[11px] font-semibold tracking-wider text-muted uppercase">{t(g.label)}</div>
            )}
            {g.items.map((item) => (
              <NavLink key={item.href} item={item} rail={rail} active={covers(item, pathname)} />
            ))}
          </div>
        ))}
      </div>

      <div className={cx("flex flex-col gap-2 border-t border-border py-3", rail ? "items-center px-2" : "px-3")}>
        {/* Language, with the collapse button at the end of the row (stacked in the rail). */}
        <div className={cx("flex gap-2", rail ? "flex-col items-center" : "items-center justify-between")}>
          {rail ? <LanguageSwitch compact /> : <LanguageSwitch segmented />}
          {onToggle && (
            <button
              onClick={onToggle}
              title={rail ? t("Expand sidebar") : t("Collapse sidebar")}
              aria-label={rail ? t("Expand sidebar") : t("Collapse sidebar")}
              className="grid size-9 shrink-0 place-items-center rounded-xl text-muted transition-colors hover:bg-surface-2 hover:text-text focus-visible:outline-2 focus-visible:outline-primary"
            >
              {rail ? <PanelLeftOpen className="size-[18px]" aria-hidden /> : <PanelLeftClose className="size-[18px]" aria-hidden />}
            </button>
          )}
        </div>
        {/* Signing out is in My account. */}
        {rail ? (
          <AccountButton user={user} onClick={onAccount} />
        ) : (
          <button
            onClick={onAccount}
            className="flex items-center gap-3 rounded-xl p-2 text-left transition-colors hover:bg-surface-2 focus-visible:outline-2 focus-visible:outline-primary"
            aria-label={t("My account")}
          >
            <Avatar user={user} />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm font-medium">{user.fullName}</span>
              <span className="block truncate text-xs text-muted">{user.role.name}</span>
            </span>
          </button>
        )}
      </div>
    </div>
  );
}

/** One page in the menu: icon and name, or icon over a short name in the rail. */
function NavLink({ item, rail, active }: { item: (typeof NAV)[number]; rail: boolean; active: boolean }) {
  const t = useT();
  const { href, label, icon: Icon } = item;
  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      title={rail ? t(label) : undefined}
      className={cx(
        "group relative flex items-center rounded-xl font-medium transition-colors focus-visible:outline-2 focus-visible:outline-primary",
        rail ? "flex-col gap-1 px-0.5 py-1.5 text-center text-[11px] leading-tight" : "gap-3 px-3 py-1.5 text-sm",
        active ? "bg-primary-soft text-primary" : "text-muted hover:bg-surface-2 hover:text-text",
      )}
    >
      {active && !rail && <span className="absolute top-2 bottom-2 -left-3 w-1 rounded-r-full bg-primary" aria-hidden />}
      <Icon className={cx("shrink-0", rail ? "size-5" : "size-[18px]")} aria-hidden />
      <span>{t(label)}</span>
    </Link>
  );
}

/** The phone menu: the full sidebar sliding in from the left, over a dimmed page. Escape or the backdrop closes it. */
function MobileMenu({ onClose, children }: { onClose: () => void; children: React.ReactNode }) {
  const t = useT();
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    panel.current?.querySelector<HTMLElement>("a, button")?.focus();
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div className="fixed inset-0 z-40 md:hidden print:hidden">
      <div className="absolute inset-0 animate-[fade-in_0.2s_ease-out] bg-black/40 backdrop-blur-[2px]" onClick={onClose} aria-hidden />
      <div
        id="mobile-menu"
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-label={t("Main")}
        className="absolute inset-y-0 left-0 flex w-[min(18rem,85vw)] animate-[slide-in_0.25s_cubic-bezier(0.2,0.8,0.2,1)] flex-col bg-surface shadow-2xl motion-reduce:animate-none"
      >
        <button
          onClick={onClose}
          aria-label={t("Close menu")}
          className="absolute top-4 right-3 z-10 grid size-9 place-items-center rounded-xl text-muted hover:bg-surface-2 hover:text-text"
        >
          <X className="size-5" aria-hidden />
        </button>
        {children}
      </div>
    </div>
  );
}

function Avatar({ user }: { user: User }) {
  return <UserAvatar user={user} />;
}

function AccountButton({ user, onClick }: { user: User; onClick: () => void }) {
  const t = useT();
  return (
    <button
      onClick={onClick}
      className="rounded-full transition-shadow hover:ring-2 hover:ring-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      title={t("{name} ({role}): my account", { name: user.fullName, role: user.role.name })}
      aria-label={t("My account")}
    >
      <Avatar user={user} />
    </button>
  );
}

/** Shown instead of the app until someone with a temporary password chooses their own. */
function ForcedPasswordChange({ onSignOut }: { onSignOut: () => void }) {
  const { state } = useAuth();
  const t = useT();
  return (
    <div className="grid min-h-screen place-items-center px-4">
      <Card className="w-full max-w-sm p-6 shadow-sm">
        <h1 className="text-lg font-semibold">{t("Choose a new password")}</h1>
        <p className="mb-5 text-sm text-muted">
          {t(
            "Hi {name}. Your password was set by an admin. Choose your own to continue. Use the password you were given as the current password.",
            { name: state.user?.fullName },
          )}
        </p>
        <ChangePasswordForm submitLabel={t("Save and continue")} />
        <button onClick={onSignOut} className="mt-4 w-full text-center text-sm text-muted hover:text-text">
          {t("Sign out")}
        </button>
      </Card>
    </div>
  );
}

/** The branch the app is working for. Admins click it to switch. */
function BranchSwitcher({ rail }: { rail: boolean }) {
  const { current, branches, canSwitch, switchBranch } = useBranch();
  const [open, setOpen] = useState(false);
  const t = useT();
  if (!current) return null;
  const content = rail ? (
    <>
      <MapPin className="size-5" aria-hidden />
      <span className="max-w-16 truncate">{current.code}</span>
    </>
  ) : (
    <>
      <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-primary-soft text-primary">
        <MapPin className="size-4" aria-hidden />
      </span>
      <span className="min-w-0 flex-1 text-left">
        <span className="block text-[11px] text-muted">{t("Branch")}</span>
        <span className="block truncate text-sm font-medium text-text">{current.name}</span>
      </span>
      {canSwitch && <ChevronsUpDown className="size-4 shrink-0 text-muted" aria-hidden />}
    </>
  );
  const className = rail
    ? "flex flex-col items-center gap-1 rounded-xl px-2 py-2 text-[11px] font-medium text-muted"
    : "flex w-full items-center gap-2.5 rounded-xl border border-border bg-surface-2/60 p-2";
  if (!canSwitch) {
    return (
      <div className={className} title={current.name}>
        {content}
      </div>
    );
  }
  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className={cx(className, "transition-colors hover:bg-surface-2 hover:text-text focus-visible:outline-2 focus-visible:outline-primary")}
        title={t("Working for {branch}. Click to switch.", { branch: current.name })}
        aria-label={t("Branch: {branch}. Switch branch", { branch: current.name })}
      >
        {content}
      </button>
      <Modal open={open} onClose={() => setOpen(false)} title={t("Switch branch")}>
        <p className="mb-3 text-sm text-muted">{t("Sales, stock and the catalog will act for the branch you pick.")}</p>
        <ul className="flex flex-col gap-1">
          {branches
            .filter((b) => b.active)
            .map((b) => (
              <li key={b.id}>
                <button
                  onClick={() => {
                    switchBranch(b.id);
                    setOpen(false);
                  }}
                  className={cx(
                    "flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left text-sm hover:bg-surface-2",
                    b.id === current.id && "bg-primary-soft text-primary",
                  )}
                >
                  <span className="w-14 font-mono text-xs">{b.code}</span>
                  <span className="flex-1">{b.name}</span>
                  {b.id === current.id && <Check className="size-4" aria-hidden />}
                </button>
              </li>
            ))}
        </ul>
      </Modal>
    </>
  );
}

/** Tabs that became pages of their own, so old links and bookmarks still arrive (whatever the role). */
const MOVED_TABS = [
  { path: "/settings", tab: "manual", to: "/help" },
  { path: "/orders", tab: "customers", to: "/customers" },
];

function movedTo(pathname: string, params: URLSearchParams) {
  return MOVED_TABS.find((m) => m.path === pathname && params.get("tab") === m.tab)?.to;
}

/**
 * Shows the page only if the user's role opens it. The sell screen (the default landing page)
 * forwards to the first page they can use; elsewhere they get a short notice.
 */
function PageGuard({ user, children }: { user: User; children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const t = useT();
  const item = NAV.find((n) => covers(n, pathname));
  const blocked = item !== undefined && !allowed(user, item);
  const home = homePath(user);
  const moved = typeof window === "undefined" ? undefined : movedTo(pathname, new URLSearchParams(window.location.search));
  const forward = moved ?? (blocked && item?.href === "/pos" && home !== "/pos" ? home : undefined);
  useEffect(() => {
    if (forward) router.replace(forward);
  }, [forward, router]);
  if (forward) return <Spinner />;
  if (!blocked) return <>{children}</>;
  return (
    <div className="p-8 text-sm">
      <p className="font-medium">{t("Your role ({role}) doesn't include this page.", { role: user.role.name })}</p>
      <p className="mt-1 text-muted">
        {t("Ask an administrator if you need it, or go to")}{" "}
        <Link href={home} className="text-primary hover:underline">
          {t(NAV.find((n) => n.href === home)?.label ?? "Catalog")}
        </Link>
        .
      </p>
    </div>
  );
}

function useMediaQuery(query: string) {
  return useSyncExternalStore(
    (onChange) => {
      const list = window.matchMedia(query);
      list.addEventListener("change", onChange);
      return () => list.removeEventListener("change", onChange);
    },
    () => window.matchMedia(query).matches,
    () => true,
  );
}

const flagListeners = new Set<() => void>();
/** Used where browser storage is blocked (a private window, say): the choice then lasts until reload. */
const flagFallback = new Map<string, boolean>();

/** A yes/no preference kept on this device in browser storage. */
function useStoredFlag(key: string): [boolean, (value: boolean) => void] {
  const value = useSyncExternalStore(
    (onChange) => {
      flagListeners.add(onChange);
      return () => flagListeners.delete(onChange);
    },
    () => {
      try {
        return localStorage.getItem(key) === "1";
      } catch {
        return flagFallback.get(key) ?? false;
      }
    },
    () => false,
  );
  const set = (next: boolean) => {
    flagFallback.set(key, next);
    try {
      localStorage.setItem(key, next ? "1" : "0");
    } catch {}
    flagListeners.forEach((listener) => listener());
  };
  return [value, set];
}
