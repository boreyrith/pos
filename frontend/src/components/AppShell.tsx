"use client";

import { BarChart3, ClipboardList, Check, Settings, LogOut, MapPin, Package, Receipt, ShoppingCart, Users, Warehouse, FileBarChart } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { hasPermission, useAuth } from "@/lib/auth";
import { msg, useT } from "@/lib/i18n";
import { BranchProvider, useBranch } from "@/lib/branch";
import { useStoreSettings } from "@/lib/queries";
import type { Permission, User } from "@/lib/types";

import { AccountDialog } from "./account/AccountDialog";
import { ChangePasswordForm } from "./account/ChangePasswordForm";
import { LanguageSwitch } from "./LanguageSwitch";
import { Card, Modal, Spinner, cx } from "./ui";

/** Each page and the permissions that open it (any one of them). No list means every signed-in user. */
const NAV: { href: string; label: string; icon: typeof ShoppingCart; any?: Permission[]; also?: string[] }[] = [
  { href: "/pos", label: msg("Sell"), icon: ShoppingCart, any: ["SALES_SELL"] },
  { href: "/catalog", label: msg("Catalog"), icon: Package },
  { href: "/inventory", label: msg("Inventory"), icon: Warehouse, any: ["INVENTORY_VIEW"] },
  { href: "/purchasing", label: msg("Purchasing"), icon: ClipboardList, any: ["PURCHASE_VIEW"] },
  // Sales: invoices, returns and refunds, customers (customer pages live under /customers).
  { href: "/orders", label: msg("Sales"), icon: Receipt, any: ["ORDERS_VIEW", "SALES_SELL", "CUSTOMERS_VIEW"], also: ["/customers"] },
  { href: "/dashboard", label: msg("Dashboard"), icon: BarChart3, any: ["REPORTS_DASHBOARD"] },
  { href: "/reports", label: msg("Reports"), icon: FileBarChart, any: ["REPORTS_SALES", "REPORTS_INVENTORY", "REPORTS_PURCHASING", "REPORTS_TAX"] },
  {
    href: "/settings",
    label: msg("Settings"),
    icon: Settings,
    // Everyone: the user manual lives here; the other sections check their own permissions.
  },
  { href: "/users", label: msg("Users"), icon: Users, any: ["USERS_VIEW", "ROLES_MANAGE", "AUDIT_VIEW"] },
];

const allowed = (user: User, item: (typeof NAV)[number]) => !item.any || hasPermission(user, ...item.any);

/** Whether a page belongs to a menu item (its own path, or one it also covers). */
const covers = (item: (typeof NAV)[number], pathname: string) =>
  [item.href, ...(item.also ?? [])].some((p) => pathname === p || pathname.startsWith(`${p}/`));

/** Where each role starts, in order of preference (the catalog is open to everyone, so it comes last). */
const LANDING = ["/pos", "/orders", "/inventory", "/purchasing", "/dashboard", "/reports", "/settings", "/users", "/catalog"];

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

function Shell({ children, onSignOut }: { children: React.ReactNode; onSignOut: () => void }) {
  const { state } = useAuth();
  const pathname = usePathname();
  const user = state.user!;
  const taxMode = useStoreSettings().data?.taxMode;
  const items = NAV.filter(
    (item) =>
      allowed(user, item) &&
      // Reports for someone who can only see the tax report, while the store charges no VAT, would be empty.
      !(item.href === "/reports" && taxMode === "NONE" && !hasPermission(user, "REPORTS_SALES", "REPORTS_INVENTORY", "REPORTS_PURCHASING")),
  );
  const [accountOpen, setAccountOpen] = useState(false);
  const t = useT();

  return (
    <div className="flex min-h-screen flex-col md:flex-row">
      <nav
        aria-label={t("Main")}
        className="sticky top-0 z-10 flex shrink-0 items-center gap-1 border-b border-border bg-surface px-2 py-2 md:h-screen md:w-20 md:flex-col md:border-r md:border-b-0 md:py-4 print:hidden"
      >
        <div className="mr-2 grid size-10 place-items-center rounded-xl bg-primary text-sm font-bold text-on-primary md:mr-0 md:mb-4">
          POS
        </div>
        <div className="flex min-w-0 flex-1 gap-1 overflow-x-auto md:min-h-0 md:flex-col md:overflow-x-visible md:overflow-y-auto">
          {items.map((item) => {
            const { href, label, icon: Icon } = item;
            const active = covers(item, pathname);
            return (
              <Link
                key={href}
                href={href}
                aria-current={active ? "page" : undefined}
                className={cx(
                  "flex min-w-16 flex-col items-center gap-1 rounded-lg px-2 py-2 text-[11px] font-medium",
                  active ? "bg-primary-soft text-primary" : "text-muted hover:bg-surface-2 hover:text-text",
                )}
              >
                <Icon className="size-5" aria-hidden />
                {t(label)}
              </Link>
            );
          })}
        </div>
        <div className="flex items-center gap-1 md:flex-col">
          <BranchBadge />
          <LanguageSwitch compact />
          <button
            onClick={() => setAccountOpen(true)}
            className="hidden size-9 place-items-center rounded-full bg-surface-2 text-xs font-semibold hover:ring-2 hover:ring-primary md:grid"
            title={t("{name} ({role}): my account", { name: user.fullName, role: user.role.name })}
            aria-label={t("My account")}
          >
            {user.fullName
              .split(" ")
              .map((part) => part[0])
              .slice(0, 2)
              .join("")}
          </button>
          <button
            onClick={onSignOut}
            className="flex flex-col items-center gap-1 rounded-lg px-2 py-2 text-[11px] font-medium text-muted hover:bg-surface-2 hover:text-text"
          >
            <LogOut className="size-5" aria-hidden />
            {t("Sign out")}
          </button>
        </div>
      </nav>
      <main className="min-w-0 flex-1">
        <PageGuard user={user}>{children}</PageGuard>
      </main>
      {accountOpen && <AccountDialog onClose={() => setAccountOpen(false)} />}
    </div>
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
function BranchBadge() {
  const { current, branches, canSwitch, switchBranch } = useBranch();
  const [open, setOpen] = useState(false);
  const t = useT();
  if (!current) return null;
  const label = (
    <>
      <MapPin className="size-5" aria-hidden />
      <span className="max-w-16 truncate">{current.code}</span>
    </>
  );
  const className = "flex flex-col items-center gap-1 rounded-lg px-2 py-2 text-[11px] font-medium text-muted";
  if (!canSwitch) {
    return (
      <div className={className} title={current.name}>
        {label}
      </div>
    );
  }
  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className={cx(className, "hover:bg-surface-2 hover:text-text")}
        title={t("Working for {branch}. Click to switch.", { branch: current.name })}
        aria-label={t("Branch: {branch}. Switch branch", { branch: current.name })}
      >
        {label}
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
  const forward = blocked && item?.href === "/pos" && home !== "/pos";
  useEffect(() => {
    if (forward) router.replace(home);
  }, [forward, home, router]);
  if (!blocked) return <>{children}</>;
  if (forward) return <Spinner />;
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
